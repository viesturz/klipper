package mcu.impl

import config.McuConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import machine.impl.MachineTime
import machine.impl.Reactor
import mcu.Mcu
import mcu.McuState
import mcu.NeedsRestartException
import mcu.connection.CommandQueue
import mcu.connection.McuConnection
import kotlin.time.Duration.Companion.milliseconds

class McuConfigureImpl(var cmd: Commands): McuConfigure {
    internal var numIds:ObjectId = 0u
    internal var numMoves: Int = 0
    internal val configCommands = ArrayList<UByteArray>()
    internal val initCommands = ArrayList<UByteArray>()
    internal val queryCommands = ArrayList<QueryCommand>()
    internal val restartCommands = ArrayList<UByteArray>()
    internal val responseHandlers = HashMap<Pair<ResponseParser<McuResponse>, ObjectId>, (message: McuResponse) -> Unit>()
    internal val commandQueues = ArrayList<CommandQueue>()
    val components = ArrayList<McuComponent>()
    override val identify: FirmwareConfig
        get() = cmd.identify

    override fun makeOid(): ObjectId {
        val id = numIds
        numIds++
        return id
    }

    override fun makeCommandQueue(name: String): CommandQueue {
        val result = CommandQueue(null, name)
        commandQueues.add(result)
        return result
    }

    override fun configCommand(signature: String, block: CommandBuilder.()->Unit) { configCommands.add(cmd.build(signature, block)) }
    override fun initCommand(signature: String, block: CommandBuilder.()->Unit) { initCommands.add(cmd.build(signature, block)) }
    override fun queryCommand(signature: String, block: CommandBuilder.(clock: McuClock32)->Unit) { queryCommands.add(QueryCommand(signature, block)) }
    override fun restartCommand(signature: String, block: CommandBuilder.()->Unit) { restartCommands.add(cmd.build(signature, block))}

    @Suppress("UNCHECKED_CAST")
    override fun <ResponseType: McuResponse> responseHandler(parser: ResponseParser<ResponseType>, id: ObjectId, handler: ((message: ResponseType) -> Unit)) {
        val key = Pair(parser, id) as Pair<ResponseParser<McuResponse>, ObjectId>
        responseHandlers[key] = handler as ((message: McuResponse) -> Unit)
    }
}

data class QueryCommand(val signature: String, val block: CommandBuilder.(clock: McuClock32)->Unit)

class McuImpl(override val config: McuConfig, val connection: McuConnection, val configuration: McuConfigureImpl): Mcu {
    private val defaultQueue = CommandQueue(connection, "MCU ${config.name} DefaultQueue")
    override val components: List<McuComponent> = configuration.components
    private val clocksync = ClockSync(connection)
    private val _state = MutableStateFlow(McuState.STARTING)
    private var _stateReason = ""
    private val logger = KotlinLogging.logger("McuImpl ${config.name}")
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
        logger.info { "Starting components" }
        val runtime = makeRuntime(reactor)
        components.forEach { it.start(runtime) }
        logger.info { "Startup done" }
        _state.value = McuState.RUNNING
    }

    private fun makeRuntime(reactor: Reactor) = object :McuRuntime {
        override val reactor: Reactor
            get() = reactor
        override val defaultQueue: CommandQueue
            get() = this@McuImpl.defaultQueue
        override fun durationToClock(durationSeconds: Float) =
            clocksync.estimate.durationToClock(durationSeconds)
        override fun timeToClock(time: MachineTime) = clocksync.estimate.timeToClock(time)
        override fun clockToTime(clock: McuClock32) = clocksync.estimate.clockToTime(clock)
    }

    override fun shutdown(reason: String) {
        components.forEach { it.shutdown() }
        connection.disconnect()
        _stateReason = reason
        _state.value = McuState.ERROR
    }

    private suspend fun configure(reactor: Reactor) {
        logger.info { "Configuring MCU, oids=${configuration.numIds}, queues=${configuration.commandQueues.size}" }
        val config = defaultQueue.sendWithResponse("get_config", responseConfigParser)
        if (config.isShutdown)
            throw NeedsRestartException("Mcu ${this.config.name} is shutdown")
        if (configuration.numMoves > config.moveCount.toInt())
            throw RuntimeException("Mcu ${this.config.name}, moves buffer not enough, need ${config.moveCount}, has ${configuration.numMoves}")

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
    }

    private suspend fun restartFirmware() {
        if (connection.commands.hasCommand("reset")) {
            defaultQueue.send("reset")
            delay(15.milliseconds)
        }
    }

}

data class ResponseConfig(val isConfig: Boolean, val crc: UInt, val isShutdown: Boolean, val moveCount: MoveQueueId): McuResponse
val responseConfigParser = ResponseParser("config is_config=%c crc=%u is_shutdown=%c move_count=%hu")
            { ResponseConfig(parseB(), parseU(), parseB(), parseHU()) }
