package machine.impl

import config.MachineConfig
import config.McuConfig
import config.PartConfig
import config.SerialConnection
import config.TemperatureSensorPart
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import machine.Machine
import machine.Machine.State
import machine.QueueManager
import mcu.Mcu
import mcu.McuSetup
import mcu.McuState
import mcu.connection.McuConnection
import mcu.connection.connectSerial
import mcu.impl.McuSetupImpl
import parts.GCodeHandler
import parts.MachinePart
import parts.MachinePartLifecycle
import parts.MachineRuntime
import parts.MachineSetup
import parts.TemperatureSensor
import parts.buildPart
import parts.buildTemperatureSensor

private val logger = KotlinLogging.logger("MachineImpl")

class MachineImpl(val config: MachineConfig) : Machine, MachineRuntime, MachineSetup {
    private var _state = MutableStateFlow(State.NEW)
    override val state: StateFlow<State>
        get() = _state

    override val reactor = Reactor()
    override val scope = reactor.scope
    override val gCode = GCode()
    override val queueManager: QueueManager = QueueManagerImpl(reactor)
    var _shutdownReason = ""
    override val shutdownReason: String
        get() = _shutdownReason
    val partsList = ArrayList<MachinePartLifecycle>()
    val parts = HashMap<PartConfig, MachinePartLifecycle>()
    val mcuList = ArrayList<Mcu>()
    val mcuSetups = HashMap<McuConfig, McuSetup>()

    override suspend fun start() {
        _state.value = State.CONFIGURING
        for (p in config.parts) acquirePart(p)
        for (mcu in mcuSetups.values){
            logger.info { "Initializing MCU: ${mcu.config.name}" }
            val mcu = mcu.start(reactor)
            mcuList.add(mcu)
            reactor.launch {
                mcu.state.first { it == McuState.ERROR }
                shutdown(mcu.stateReason)
            }
            logger.info { "Initializing MCU: ${mcu.config.name} done" }
        }
        _state.value = State.STARTING
        reactor.launch {
            partsList.forEach { it.onStart(this@MachineImpl) }
        }
        if (state.value != State.STARTING) return
        _state.value = State.RUNNING
    }

    override fun shutdown(reason: String) {
        if (_state.getAndUpdate { State.SHUTDOWN } != State.RUNNING) return
        logger.warn { "Shutting down the machine, reason: $reason" }
        reactor.launch {
            partsList.reversed().forEach { it.shutdown() }
            mcuList.reversed().forEach { it.shutdown("machine shutting down") }
            logger.warn { "Machine shutdown finished" }
            reactor.shutdown()
        }
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
        partsList.add(p as MachinePartLifecycle)
        parts[config] = p as MachinePartLifecycle
        p
    } as MachinePart

    override fun acquirePart(config: TemperatureSensorPart) = parts.getOrElse(config) {
        logger.info { "Acquire part $config" }
        val p = buildTemperatureSensor(config, this)
        partsList.add(p as MachinePartLifecycle)
        parts[config] = p as MachinePartLifecycle
        p
    } as TemperatureSensor

    override fun registerCommand(command: String, handler: GCodeHandler) {
        gCode.registerCommand(command, handler)
    }
    override fun registerMuxCommand(command: String, muxParam: String, muxValue: String, handler: GCodeHandler) {
        gCode.registerMuxCommand(command, muxParam, muxValue, handler)
    }

    private fun acquireConnection(config: config.Connection): McuConnection {
        logger.debug { "Acquire connection $config" }
        val connection = when (config) {
            is SerialConnection -> McuConnection(connectSerial(config.file, config.baud), reactor)
            else -> throw RuntimeException("Unsupported connection ${config}")
        }
        logger.debug { "Identifying connection $config" }
        runBlocking { connection.identify() }
        return connection
    }
}