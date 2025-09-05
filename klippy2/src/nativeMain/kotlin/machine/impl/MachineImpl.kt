package machine.impl

import GCodeHandler
import MachineTime
import MachineBuilder
import MachineRuntime
import config.McuConfig
import config.SerialConnection
import buildMachine
import config.Connection
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import Machine.State
import PartLifecycle
import machine.QueueManager
import machine.Reactor
import Mcu
import McuSetup
import McuState
import machine.MOVE_HISTORY_TIME
import mcu.connection.McuConnection
import mcu.connection.connectSerial
import mcu.McuSetupImpl
import parts.Stats

class MachineImpl : MachineRuntime, MachineBuilder {
    override val state = MutableStateFlow(State.NEW)
    override val reactor = Reactor()
    override val logger = KotlinLogging.logger("MachineImpl")
    val gCode = GCodeImpl()
    val queueManager: QueueManager = QueueManagerImpl(reactor)
    override var shutdownReason = ""
    val partsList = ArrayList<PartLifecycle>()
    override val parts
        get() = partsList
    val mcuList = ArrayList<Mcu>()
    val mcuSetups = HashMap<McuConfig, McuSetup>()
    private val nameGenerator = HashMap<String, Int>()
    private val commandsQueue = queueManager.newQueue()

    override suspend fun start() {
        state.value = State.CONFIGURING
        try {
            buildMachine()
            addCommonParts()
            for (mSetup in mcuSetups.values){
                logger.info { "Initializing MCU: ${mSetup.config.name}" }
                val mcu = mSetup.start(reactor)
                mcuList.add(mcu)
                // Monitor the MCU state and shutdown on error.
                reactor.launch {
                    mcu.state.first { it == McuState.ERROR }
                    shutdown(mcu.stateReason)
                }
                logger.info { "Initializing MCU: ${mcu.config.name} done" }
            }
            state.value = State.STARTING
            partsList.forEach { it.onStart(this@MachineImpl) }
        } catch (e: Exception) {
            logger.error(e) { "Machine start failed, ${e.message}" }
            reactor.shutdown()
            throw e
        }
        if (state.value != State.STARTING) return
        state.value = State.RUNNING
    }

    private fun addCommonParts() {
        addPart(Stats())
    }

    override fun shutdown(reason: String, emergency: Boolean) {
        if (state.value != State.RUNNING) return
        state.value = State.STOPPING
        shutdownReason = reason
        logger.warn { "Shutting down the machine, reason: $reason" }
        reactor.launch {
            partsList.reversed().forEach { it.shutdown() }
            mcuList.reversed().forEach { it.shutdown(reason, emergency) }
            logger.warn { "Machine shutdown finished" }
            state.value = State.SHUTDOWN
            reactor.shutdown()
        }
    }

    override suspend fun gcode(command: String, responseHandler: ((String) -> Unit)) {
        gCode.runner(commandsQueue, this, responseHandler).gcode(command)
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
        val clearHistoryTime = flushTime - MOVE_HISTORY_TIME
        for (mcu in mcuList) {
            mcu.flushMoves(flushTime, clearHistoryTime)
        }
    }

    override fun newQueue() = queueManager.newQueue()
    override fun newBackdatingQueue() = queueManager.newBackdatingQueue()
    override fun defaultName(className: String): String {
        val num = nameGenerator.getOrElse(className) {0} + 1
        nameGenerator[className] = num
        return "${className}${num}"
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

    private fun acquireConnection(config: Connection): McuConnection {
        logger.debug { "Acquire connection $config" }
        val connection = when (config) {
            is SerialConnection -> McuConnection(connectSerial(config.file, config.baud), config.baud, reactor)
            else -> throw RuntimeException("Unsupported connection ${config}")
        }
        logger.info { "Identifying connection $config" }
        runBlocking { connection.identify() }
        return connection
    }
}