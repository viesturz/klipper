package machine.impl

import config.MachineConfig
import config.McuConfig
import config.PartConfig
import config.SerialConnection
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import machine.Machine
import machine.Machine.State
import machine.QueueManager
import mcu.Mcu
import mcu.McuSetup
import mcu.connection.McuConnection
import mcu.connection.connectSerial
import mcu.impl.McuSetupImpl
import parts.GCodeHandler
import parts.MachinePart
import parts.MachineRuntime
import parts.MachineSetup
import parts.buildPart

private val logger = KotlinLogging.logger("MachineImpl")

class MachineImpl(val config: MachineConfig) : Machine, MachineRuntime, MachineSetup {
    private var _state = MutableStateFlow(State.NEW)
    override val state: StateFlow<State>
        get() = _state

    override val reactor = Reactor()
    override val gCode = GCode()
    override val queueManager: QueueManager = QueueManagerImpl(reactor)
    var _shutdownReason = ""
    override val shutdownReason: String
        get() = _shutdownReason
    val partsList = ArrayList<MachinePart<PartConfig>>()
    val parts = HashMap<PartConfig, MachinePart<PartConfig>>()
    val mcuList = ArrayList<Mcu>()
    val mcuSetups = HashMap<McuConfig, McuSetup>()

    override suspend fun run() {
        _state.value = State.CONFIGURING
        for (p in config.parts) acquirePart(p)
        for (mcu in mcuSetups.values){
            println("Initializing MCU: ${mcu.config.name}")
            mcuList.add(mcu.start(reactor))
            println("Initializing MCU: ${mcu.config.name} done")
        }

        println("Starting event loop")
        // Schedule the setup call - the first one to run when reactor starts.
        reactor.runNow(this::runPartsSetup)
        reactor.runEventLoop()
        println("Event loop done, shutting down")
        mcuList.reversed().forEach { it.shutdown("machine shutting down") }
        _state.value = State.SHUTDOWN
    }

    suspend fun runPartsSetup() {
        _state.value = State.STARTING
        // Start in inverse order so smaller, rested parts are started first.
        partsList.reversed().forEach { it.onStart(this) }
        _state.value = State.RUNNING
    }

    override fun shutdown(reason: String) {
        println("Machine Shutting down")
        partsList.reversed().forEach { it.shutdown() }
        mcuList.reversed().forEach { it.shutdown(reason) }
        reactor.shutdown()
        println("Shutdown method done, will continue cleanup in the run.")
    }

    override val status: Map<String, String>
        get() = buildMap {
            val time = reactor.now
            for (part in partsList) {
                put(part.config.name, part.status(time).toString())
            }
        }

    override fun acquireMcu(config: McuConfig): McuSetup {
        val existing = mcuSetups[config]
        if (existing != null) {
            return existing
        }
        logger.info { "Acquire mcu $config" }
        val mcu = McuSetupImpl(config, connection = acquireConnection(config.connection))
        mcuSetups[config] = mcu
        return mcu
    }

    override fun acquirePart(config: PartConfig) = parts.getOrElse(config) {
        logger.info { "Acquire part $config" }
        val p = buildPart(config, this)
        partsList.add(p)
        parts[config] = p
        p
    }

    override fun registerCommand(command: String, handler: GCodeHandler) {
        gCode.registerCommand(command, handler)
    }
    override fun registerMuxCommand(command: String, muxParam: String, muxValue: String, handler: GCodeHandler) {
        gCode.registerMuxCommand(command, muxParam, muxValue, handler)
    }

    private fun acquireConnection(config: config.Connection): McuConnection {
        logger.debug { "Acquire connection $config" }
        val connection = when (config) {
            is SerialConnection -> McuConnection(connectSerial(config.file, config.baud))
            else -> throw RuntimeException("Unsupported connection ${config}")
        }
        logger.debug { "Identifying connection $config" }
        runBlocking { connection.identify() }
        return connection
    }
}