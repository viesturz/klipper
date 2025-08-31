package mcu

import Endstop
import EndstopSync
import EndstopSyncBuilder
import MachineDuration
import config.McuConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import MachineTime
import config.AnalogInPin
import config.DigitalInPin
import config.DigitalOutPin
import config.StepperPins
import config.UartPins
import machine.NeedsRestartException
import machine.Reactor
import Mcu
import McuClock
import McuSetup
import McuState
import PwmPin
import StepperDriver
import StepperMotor
import config.RestartMethod
import mcu.components.McuAnalogPin
import mcu.components.McuButton
import mcu.components.McuDigitalPin
import mcu.components.McuEndstop
import mcu.components.McuEndstopSyncRuntimeBuilder
import mcu.components.McuEndstopSyncStaticBuilder
import mcu.components.McuHwPwmPin
import mcu.components.McuPwmPin
import mcu.components.McuStepperMotor
import mcu.components.McuTrsyncPool
import mcu.components.TmcUartBus
import mcu.connection.CommandQueue
import mcu.connection.McuConnection
import mcu.connection.StepQueueImpl
import mcu.connection.StepperSync
import utils.RegisterMcuMessage
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds

/** Step 0 - API for adding all the parts to MCU. */
class McuSetupImpl(override val config: McuConfig, val connection: McuConnection): McuSetup {
    private val configuration = McuConfigureImpl(connection.commands)
    private val mcu = McuImpl(config, connection, configuration,)

    init {
        add(McuBasics(mcu, configuration))
    }

    private fun add(component: McuComponent) = configuration.components.add(component)

    override suspend fun start(reactor: Reactor): Mcu {
        mcu.start(reactor)
        return mcu
    }

    override fun addButton(pin: DigitalInPin) = McuButton(mcu, pin, configuration).also { add(it) }
    override fun addDigitalPin(config: DigitalOutPin) = McuDigitalPin(mcu, config, configuration).also { add(it) }
    override fun addPwmPin(config: DigitalOutPin): PwmPin =
        (if (config.hardwarePwm) McuHwPwmPin(mcu, config, configuration)
        else McuPwmPin(mcu, config, configuration)).also { add(it) }
    override fun addAnalogPin(pin: AnalogInPin) = McuAnalogPin(mcu, pin, configuration).also { add(it) }
    override fun addStepperMotor(config: StepperPins, driver: StepperDriver): StepperMotor = McuStepperMotor(mcu, config, driver, configuration).also { add(it) }
    override fun addEndstop(pin: DigitalInPin): Endstop = McuEndstop(mcu, pin, configuration).also { add(it) }
    override fun addTmcUart(config: UartPins) = TmcUartBus(mcu, config, configuration).also { add(it) }
    override fun addEndstopSync(block: (EndstopSyncBuilder) -> Unit): EndstopSync = McuEndstopSyncStaticBuilder().also { block(it) }.build()
}

/** Step 1 - API to configure each part. */
class McuConfigureImpl(var cmd: Commands): McuConfigure {
    internal var numIds: ObjectId = 0u
    internal var reservedMoves: Int = 0
    internal val configCommands = ArrayList<UByteArray>()
    internal val initCommands = ArrayList<UByteArray>()
    internal val queryCommands = ArrayList<(clock: McuClock32) -> McuCommand>()
    internal val restartCommands = ArrayList<UByteArray>()
    internal val responseHandlers = HashMap<Pair<KClass<McuResponse>, ObjectId>, suspend (message: McuResponse) -> Unit>()
    internal val commandQueues = ArrayList<CommandQueue>()
    internal val stepQueues = ArrayList<StepQueueImpl>()
    val components = ArrayList<McuComponent>()
    override val firmware: FirmwareConfig
        get() = cmd.identify

    override fun makeOid(): ObjectId {
        val id = numIds
        numIds++
        return id
    }

    override fun addComponent(component: McuComponent) {
        components.add(component)
    }

    override fun makeCommandQueue(name: String, numCommands: Int): CommandQueue {
        val result = CommandQueue(null, name)
        commandQueues.add(result)
        reservedMoves += numCommands
        return result
    }

    override fun makeStepQueue(id: ObjectId): StepQueueImpl {
        val result = StepQueueImpl(firmware, null, id)
        stepQueues.add(result)
        return result
    }

    override fun configCommand(command: McuCommand) { configCommands.add(cmd.build(command)) }
    override fun initCommand(command: McuCommand) { initCommands.add(cmd.build(command)) }
    override fun queryCommand(block: (clock: McuClock32)-> McuCommand) { queryCommands.add(block) }
    override fun restartCommand(command: McuCommand) { restartCommands.add(cmd.build(command))}

    @Suppress("UNCHECKED_CAST")
    override fun <ResponseType: McuResponse> responseHandler(response: KClass<ResponseType>, id: ObjectId, handler: suspend (message: ResponseType) -> Unit) {
        val key = Pair(response as KClass<McuResponse>, id)
        responseHandlers[key] = handler as (suspend (message: McuResponse) -> Unit)
    }
}
/** Step 2 - the MCU runtime. */
class McuImpl(
    override val config: McuConfig,
    val connection: McuConnection,
    val configuration: McuConfigureImpl): Mcu {
    val defaultQueue = CommandQueue(connection, "MCU ${config.name} DefaultQueue")
    val trsyncPool =  McuTrsyncPool(this, configuration)
    private val components: List<McuComponent> = configuration.components
    private val clocksync = ClockSync(this, connection)
    private val _state = MutableStateFlow(McuState.STARTING)
    private var _stateReason = ""
    private val logger = KotlinLogging.logger("McuImpl ${config.name}")
    private val stepperSyncs = mutableListOf<StepperSync>()
    override val state: StateFlow<McuState>
        get() = _state
    override val stateReason: String
        get() = _stateReason
    private val isRunning: Boolean
        get() = _state.value == McuState.RUNNING

    suspend fun start(reactor: Reactor) {
        logger.info { "Starting" }
        logger.info { "Syncing clock" }
        clocksync.start(reactor)
        logger.info { "Configuring ${components.size} components" }
        components.forEach { it.configure(configuration) }
        try {
            configure(reactor)
        } catch (e: NeedsRestartException) {
            logger.info { "Restarting firmware for: ${e.message}" }
            restartFirmware()
            connection.disconnect()
            logger.info { "Restarting complete" }
            throw e
        }
        // Unlock the command queues.
        configuration.commandQueues.forEach { it.connection = this.connection }
        configuration.stepQueues.forEach { it.connection = this.connection }
        logger.info { "Starting components" }
        val runtime = makeRuntime(reactor)
        components.forEach { it.start(runtime) }
        logger.info { "Startup done" }
        advanceStateOrAbort(McuState.RUNNING)
    }

    private fun makeRuntime(reactor: Reactor) = object : McuRuntime {
        override val firmware: FirmwareConfig
            get() = connection.commands.identify
        override val reactor: Reactor
            get() = reactor
        override val defaultQueue: CommandQueue
            get() = this@McuImpl.defaultQueue
        override fun durationToClock(duration: MachineDuration) =
            clocksync.estimate.durationToClock(duration)
        override fun timeToClock(time: MachineTime) = clocksync.estimate.timeToClock(time)
        override fun timeToClock32(time: MachineTime) = timeToClock(time).toUInt()
        override fun clockToTime(clock: McuClock32) = clocksync.estimate.clockToTime(clock)
        override fun clock32ToClock(clock: McuClock32) = clocksync.estimate.clock32ToClock64(clock)

        override fun <ResponseType : McuResponse> responseHandler(
            response: KClass<ResponseType>,
            id: ObjectId,
            handler: suspend (message: ResponseType) -> Unit) = connection.setResponseHandler(response, id, handler)
    }

    override fun addEndstopSync(block: (EndstopSyncBuilder) -> Unit): EndstopSync = McuEndstopSyncRuntimeBuilder().also { block(it) }.build()

    override fun flushMoves(time: MachineTime,  clearHistoryTime: MachineTime) {
        require(isRunning)
        stepperSyncs.forEach {
            it.flushMoves(clocksync.estimate.timeToClock(time), clocksync.estimate.timeToClock(clearHistoryTime))
        }
    }

    override fun shutdown(reason: String, emergency: Boolean) {
        if (emergency) {
            defaultQueue.send(CommandEmergencyStop())
        }
        if (_state.value == McuState.ERROR || _state.value == McuState.SHUTDOWN) return
        logger.error { "MCU shutdown, $reason" }
        connection.dumpCmds()
        components.forEach { it.shutdown() }
        connection.disconnect()
        _stateReason = reason
        _state.value = McuState.ERROR
    }

    private fun advanceStateOrAbort(state: McuState) {
        if (_state.value == McuState.ERROR) {
            throw RuntimeException("Mcu error, aborting, $stateReason")
        }
        _state.value = state
    }

    private suspend fun configure(reactor: Reactor) {
        logger.info { "Configuring MCU, oids=${configuration.numIds}, queues=${configuration.commandQueues.size}" }
        var config = defaultQueue.sendWithResponse<ResponseConfig>(CommandGetConfig())
        if (config.isShutdown)
            throw NeedsRestartException("Mcu ${this.config.name} is shutdown")
        configuration.responseHandlers.entries.forEach { e -> connection.setResponseHandler(e.key.first, e.key.second, e.value) }
        val configCommands = buildList {
            add(connection.commands.build(CommandAllocateOids(configuration.numIds)))
            addAll(configuration.configCommands)
        }
        val checksum = configCommands.map { it.contentHashCode() }.reduce{ a,b -> a xor b}.toUInt()

        if (config.isConfig) {
            if (checksum != config.crc) {
                logger.info { "Configure - config changed, restart needed, existing CRC = ${config.crc}, new = ${checksum}" }
                throw NeedsRestartException("Configuration changed")
            }
            logger.info { "Configure - reusing existing config" }
            configuration.restartCommands.forEach { defaultQueue.send(it) }
        } else {
            configCommands.forEach { defaultQueue.send(it) }
            defaultQueue.send(CommandFinalizeConfig(checksum))
        }
        // Run initial commands
        configuration.initCommands.forEach { defaultQueue.send(it) }
        // Flush the queue and get the new config.
        config = defaultQueue.sendWithResponse<ResponseConfig>(CommandGetConfig())
        val configureStartOffset = 0.5
        configuration.queryCommands.forEach { builder ->
            val time = reactor.now + configureStartOffset
            val clock = clocksync.estimate.timeToClock(time).toUInt()
            // Wait for acks to throttle the query commands.
            defaultQueue.sendWaitAck(builder(clock))
        }
        // Configure the stepper queues.
        if (configuration.reservedMoves > config.moveCount.toInt())
            throw RuntimeException("Mcu ${this.config.name}, moves buffer not enough, has ${config.moveCount}, need ${configuration.reservedMoves}")
        if (configuration.stepQueues.isNotEmpty()) {
            val stepQueueMoveCount = (config.moveCount.toInt() - configuration.reservedMoves) / configuration.stepQueues.size
            configuration.stepQueues.forEach { stepperSyncs.add(StepperSync(connection, it, stepQueueMoveCount)) }
        }
    }

    private suspend fun restartFirmware() {
        when (config.restartMethod) {
            RestartMethod.COMMAND -> {
                if (connection.commands.hasCommand(CommandConfigReset::class)) {
                    defaultQueue.send(CommandConfigReset())
                    delay(15.milliseconds)
                } else if (connection.commands.hasCommand(CommandReset::class)) {
                    defaultQueue.send(CommandReset())
                    delay(15.milliseconds)
                } else {
                    logger.error { "Cannot reset MCU ${config.name} via command - no supported command found." }
                }
            }
            else -> throw NotImplementedError("Restart method ${config.restartMethod} not implemented")
        }
    }

    fun updateClocks(frequency: Double, convTime: Double, convClock: ULong, lastClock: McuClock) {
        connection.setClockEstimate(frequency, convTime, convClock, lastClock)
        stepperSyncs.forEach { it.setTime(convTime, frequency) }
    }
}

@RegisterMcuMessage(signature = "emergency_stop")
class CommandEmergencyStop(): McuCommand
@RegisterMcuMessage(signature = "allocate_oids count=%c")
class CommandAllocateOids(val count: UByte): McuCommand
@RegisterMcuMessage(signature = "finalize_config crc=%u")
class CommandFinalizeConfig(val crc: UInt): McuCommand
@RegisterMcuMessage(signature = "config_reset")
class CommandConfigReset(): McuCommand
@RegisterMcuMessage(signature = "reset")
class CommandReset(): McuCommand
@RegisterMcuMessage(signature = "get_config")
class CommandGetConfig(): McuCommand
@RegisterMcuMessage(signature = "config is_config=%c crc=%u is_shutdown=%c move_count=%hu")
data class ResponseConfig(val isConfig: Boolean, val crc: UInt, val isShutdown: Boolean, val moveCount: UShort): McuResponse
