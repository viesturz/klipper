package machine.impl

import MachineTime
import machine.MachineBuilder
import machine.MachinePart
import machine.MachineRuntime
import config.McuConfig
import config.SerialConnection
import buildMachine
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import machine.GCodeHandler
import machine.Machine
import machine.Machine.State
import machine.QueueManager
import mcu.Mcu
import mcu.McuSetup
import mcu.McuState
import mcu.connection.McuConnection
import mcu.connection.connectSerial
import mcu.impl.McuSetupImpl
import parts.Stats

private val logger = KotlinLogging.logger("MachineImpl")

/** API for part lifecycle management. */
interface PartLifecycle: MachinePart {
    fun status(): Map<String, Any> = mapOf()
    // Called when all MCUs are configured and parts components initialized.
    suspend fun onStart(runtime: MachineRuntime){}
    // Called when printer session is over and it enters idle state.
    fun onSessionEnd(){}
    fun shutdown(){}
}

class MachineImpl : Machine, MachineRuntime, MachineBuilder {
    private var _state = MutableStateFlow(State.NEW)
    override val state: StateFlow<State>
        get() = _state

    override val reactor = Reactor()
    override val gCode = GCode()
    override val queueManager: QueueManager = QueueManagerImpl(reactor)
    var _shutdownReason = ""
    override val shutdownReason: String
        get() = _shutdownReason
    val partsList = ArrayList<PartLifecycle>()
    override val parts
        get() = partsList
    val mcuList = ArrayList<Mcu>()
    val mcuSetups = HashMap<McuConfig, McuSetup>()

    override suspend fun start() {
        _state.value = State.CONFIGURING
        buildMachine()
        addCommonParts()
        for (mSetup in mcuSetups.values){
            logger.info { "Initializing MCU: ${mSetup.config.name}" }
            val mcu = mSetup.start(reactor)
            mcuList.add(mcu)
            reactor.launch {
                mcu.state.first { it == McuState.ERROR }
                shutdown(mcu.stateReason)
            }
            logger.info { "Initializing MCU: ${mcu.config.name} done" }
        }
        _state.value = State.STARTING
        partsList.forEach { it.onStart(this@MachineImpl) }
        if (state.value != State.STARTING) return
        _state.value = State.RUNNING
    }

    private fun addCommonParts() {
        addPart(Stats())
    }

    override fun shutdown(reason: String) {
        if (state.value != State.RUNNING) return
        _state.value = State.STOPPING
        logger.warn { "Shutting down the machine, reason: $reason" }
        reactor.launch {
            partsList.reversed().forEach { it.shutdown() }
            mcuList.reversed().forEach { it.shutdown(reason) }
            logger.warn { "Machine shutdown finished" }
            _state.value = State.SHUTDOWN
            reactor.shutdown()
        }
    }

    override val status: Map<String, String>
        get() = buildMap {
            for (part in partsList) {
                put(part.name, part.status().toString())
            }
        }

    override fun setupMcu(config: McuConfig): McuSetup {
        val existing = mcuSetups[config]
        if (existing != null) {
            return existing
        }
        logger.info { "Acquire mcu $config" }
        val mcu = McuSetupImpl(config, connection = acquireConnection(config.connection))
        logger.info { "Mcu ${config.name} firmware config:\n ${mcu.connection.commands.identify.rawJson}" }
        mcuSetups[config] = mcu
        return mcu
    }

    val STEPCOMPRESS_FLUSH_TIME = 0.050
    val SDS_CHECK_TIME = 0.001 // step+dir+step filter in stepcompress.c

    override fun flushMoves(machineTime: MachineTime) {
        val flushDelay = STEPCOMPRESS_FLUSH_TIME + SDS_CHECK_TIME
        val flushTime = machineTime - flushDelay
        val clearHistoryTime = flushTime - 30.0
        for (mcu in mcuList) {
            mcu.flushMoves(flushTime, clearHistoryTime);
        }
    }

    override fun addPart(part: PartLifecycle) {
        partsList.add(part)
    }
    override fun registerCommand(command: String, rawText: Boolean,  handler: GCodeHandler) {
        gCode.registerCommand(command, rawText, handler)
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
        logger.info { "Identifying connection $config" }
        runBlocking { connection.identify() }
        return connection
    }
}