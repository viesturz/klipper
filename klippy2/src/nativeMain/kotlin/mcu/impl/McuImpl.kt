package mcu.impl

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
import kotlinx.cinterop.ExperimentalForeignApi
import machine.impl.Reactor
import mcu.Mcu
import mcu.McuClock
import mcu.McuSetup
import mcu.McuState
import mcu.NeedsRestartException
import mcu.PwmPin
import mcu.StepperMotor
import mcu.components.McuAnalogPin
import mcu.components.McuButton
import mcu.components.McuDigitalPin
import mcu.components.McuHwPwmPin
import mcu.components.McuPwmPin
import mcu.components.McuStepperMotor
import mcu.components.TmcUartBus
import mcu.connection.CommandQueue
import mcu.connection.McuConnection
import mcu.connection.StepQueue
import mcu.connection.StepperSync
import parts.drivers.StepperDriver
import kotlin.time.Duration.Companion.milliseconds

/** Step 0 - API for adding all the parts to MCU. */
class McuSetupImpl(override val config: McuConfig, val connection: McuConnection): McuSetup {
    private val configuration = McuConfigureImpl(connection.commands)
    private val mcu = McuImpl(config, connection, configuration)

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
    override fun addTmcUart(config: UartPins) = TmcUartBus(mcu, config, configuration).also { add(it) }
}

/** Step 1 - API to configure each part. */
class McuConfigureImpl(var cmd: Commands): McuConfigure {
    internal var numIds:ObjectId = 0u
    internal var reservedMoves: Int = 0
    internal val configCommands = ArrayList<UByteArray>()
    internal val initCommands = ArrayList<UByteArray>()
    internal val queryCommands = ArrayList<QueryCommand>()
    internal val restartCommands = ArrayList<UByteArray>()
    internal val responseHandlers = HashMap<Pair<ResponseParser<McuResponse>, ObjectId>, suspend (message: McuResponse) -> Unit>()
    internal val commandQueues = ArrayList<CommandQueue>()
    internal val stepQueues = ArrayList<StepQueue>()
    val components = ArrayList<McuComponent>()
    override val firmware: FirmwareConfig
        get() = cmd.identify

    override fun makeOid(): ObjectId {
        val id = numIds
        numIds++
        return id
    }

    override fun makeCommandQueue(name: String, numCommands: Int): CommandQueue {
        val result = CommandQueue(null, name)
        commandQueues.add(result)
        reservedMoves += numCommands
        return result
    }

    override fun makeStepQueue(id: ObjectId): StepQueue {
        val result = StepQueue(firmware, null, id)
        stepQueues.add(result)
        return result
    }

    override fun configCommand(signature: String, block: CommandBuilder.()->Unit) { configCommands.add(cmd.build(signature, block)) }
    override fun initCommand(signature: String, block: CommandBuilder.()->Unit) { initCommands.add(cmd.build(signature, block)) }
    override fun queryCommand(signature: String, block: CommandBuilder.(clock: McuClock32)->Unit) { queryCommands.add(QueryCommand(signature, block)) }
    override fun restartCommand(signature: String, block: CommandBuilder.()->Unit) { restartCommands.add(cmd.build(signature, block))}

    @Suppress("UNCHECKED_CAST")
    override fun <ResponseType: McuResponse> responseHandler(parser: ResponseParser<ResponseType>, id: ObjectId, handler: suspend (message: ResponseType) -> Unit) {
        val key = Pair(parser, id) as Pair<ResponseParser<McuResponse>, ObjectId>
        responseHandlers[key] = handler as (suspend (message: McuResponse) -> Unit)
    }
}

data class QueryCommand(val signature: String, val block: CommandBuilder.(clock: McuClock32)->Unit)

/** Step 2 - the MCU runtime. */
class McuImpl(override val config: McuConfig, val connection: McuConnection, val configuration: McuConfigureImpl): Mcu {
    private val defaultQueue = CommandQueue(connection, "MCU ${config.name} DefaultQueue")
    override val components: List<McuComponent> = configuration.components
    private val clocksync = ClockSync(this, connection)
    private val _state = MutableStateFlow(McuState.STARTING)
    private var _stateReason = ""
    private val logger = KotlinLogging.logger("McuImpl ${config.name}")
    private var stepperSync: StepperSync? = null
    override val state: StateFlow<McuState>
        get() = _state
    override val stateReason: String
        get() = _stateReason

    suspend fun start(reactor: Reactor) {
        logger.info { "Starting" }
        logger.info { "Syncing clock" }
        clocksync.start(reactor)
        logger.info { "Configuring ${components.size} components" }
        components.forEach { it.configure(configuration) }
        configure(reactor)
        // Unlock the command queues.
        configuration.commandQueues.forEach { it.connection = this.connection }
        configuration.stepQueues.forEach { it.connection = this.connection }
        logger.info { "Starting components" }
        val runtime = makeRuntime(reactor)
        components.forEach { it.start(runtime) }
        logger.info { "Startup done" }
        advanceStateOrAbort(McuState.RUNNING)
    }

    private fun makeRuntime(reactor: Reactor) = object :McuRuntime {
        override val firmware: FirmwareConfig
            get() = connection.commands.identify
        override val reactor: Reactor
            get() = reactor
        override val defaultQueue: CommandQueue
            get() = this@McuImpl.defaultQueue
        override fun durationToClock(duration: MachineDuration) =
            clocksync.estimate.durationToClock(duration)
        override fun timeToClock(time: MachineTime) = clocksync.estimate.timeToClock(time)
        override fun clockToTime(clock: McuClock32) = clocksync.estimate.clockToTime(clock)
        override fun <ResponseType : McuResponse> responseHandler(
            parser: ResponseParser<ResponseType>,
            id: ObjectId,
            handler: suspend (message: ResponseType) -> Unit) = connection.setResponseHandler(parser, id, handler)
    }

    override fun flushMoves(time: MachineTime,  clearHistoryTime: MachineTime) {
        require(isRunning)
        stepperSync?.flushMoves(clocksync.estimate.timeToClock(time), clocksync.estimate.timeToClock(clearHistoryTime))
    }

    override fun shutdown(reason: String) {
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
        var config = defaultQueue.sendWithResponse("get_config", responseConfigParser)
        if (config.isShutdown)
            throw NeedsRestartException("Mcu ${this.config.name} is shutdown")
        configuration.responseHandlers.entries.forEach { e -> connection.setResponseHandler(e.key.first, e.key.second, e.value) }
        val configCommands = buildList {
            add(defaultQueue.build("allocate_oids count=%c") {addC(configuration.numIds)})
            addAll(configuration.configCommands)
        }
        val checksum = configCommands.map { it.contentHashCode() }.reduce{ a,b -> a xor b}.toUInt()

        if (config.isConfig) {
            if (checksum != config.crc) {
                logger.info { "Configure - config changed, restart needed" }
                throw NeedsRestartException("Configuration changed")
            }
            logger.info { "Configure - reusing existing config" }
            configuration.restartCommands.forEach { defaultQueue.send(it) }
        } else {
            configCommands.forEach { defaultQueue.send(it) }
            defaultQueue.send("finalize_config crc=%u"){addU(checksum)}
        }
        // Run initial commands
        configuration.initCommands.forEach { defaultQueue.send(it) }
        val configureStartOffset = 0.5
        configuration.queryCommands.forEach { builder ->
            val clock = clocksync.estimate.timeToClock(reactor.now + configureStartOffset).toUInt()
            val cmd = defaultQueue.build(builder.signature) {
                builder.block(this, clock)
            }
            // Wait for acks to throttle the query commands.
            defaultQueue.sendWaitAck(cmd)
        }
        // Configure the stepper queues.
        config = defaultQueue.sendWithResponse("get_config", responseConfigParser)
        if (configuration.reservedMoves > config.moveCount.toInt())
            throw RuntimeException("Mcu ${this.config.name}, moves buffer not enough, has ${config.moveCount}, need ${configuration.reservedMoves}")
        stepperSync = StepperSync(connection, configuration.stepQueues, config.moveCount.toInt() - configuration.reservedMoves)
    }

    private suspend fun restartFirmware() {
        if (connection.commands.hasCommand("reset")) {
            defaultQueue.send("reset")
            delay(15.milliseconds)
        }
    }

    fun updateClocks(frequency: Double, convTime: Double, convClock: ULong, lastClock: McuClock) {
        connection.setClockEstimate(frequency, convTime, convClock, lastClock)
        stepperSync?.setTime(convTime, frequency)
    }

    private val isRunning: Boolean
        get() = _state.value == McuState.RUNNING
}

data class ResponseConfig(val isConfig: Boolean, val crc: UInt, val isShutdown: Boolean, val moveCount: MoveQueueId): McuResponse
val responseConfigParser = ResponseParser("config is_config=%c crc=%u is_shutdown=%c move_count=%hu")
            { ResponseConfig(isConfig = parseB(), crc = parseU(), isShutdown = parseB(), moveCount = parseHU()) }
