package mcu.components

import Endstop
import EndstopSync
import EndstopSyncBuilder
import MachineDuration
import MachineTime
import Mcu
import StepperMotor
import chelper.trdispatch_mcu
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import mcu.McuClock32
import mcu.McuComponent
import mcu.McuConfigure
import mcu.McuImpl
import mcu.McuObjectCommand
import mcu.McuObjectResponse
import mcu.McuRuntime
import mcu.ObjectId
import utils.RegisterMcuMessage
import kotlin.math.min

class McuEndstopSyncStaticBuilder(): EndstopSyncBuilder {
    private val endstops = ArrayList<McuEndstop>()
    private val motors = ArrayList<McuStepperMotor>()
    private val mcus = ArrayList<McuImpl>()

    override fun addEndstop(endstop: Endstop) {
        require(endstop is McuEndstop)
        endstops.add(endstop)
        if (!mcus.contains(endstop.mcu)) {
            mcus.add(endstop.mcu as McuImpl)
        }
    }
    override fun addStepperMotor(motor: StepperMotor) {
        require(motor is McuStepperMotor)
        motors.add(motor)
        if (!mcus.contains(motor.mcu)) {
            mcus.add(motor.mcu)
        }
    }
    fun build(): EndstopSync {
        require(mcus.isNotEmpty())
        require(endstops.isNotEmpty())
        require(motors.isNotEmpty())
        val mcuToTrsync = buildMap { mcus.forEach { mcu -> put(mcu, McuTrsync(mcu.configuration)) }}
        return MultiMcuEndstopSync(mcuToTrsync, endstops, motors, releaseFunc = null)
    }
}

class McuEndstopSyncRuntimeBuilder: EndstopSyncBuilder {
    private val endstops = ArrayList<McuEndstop>()
    private val motors = ArrayList<McuStepperMotor>()
    private val mcus = ArrayList<McuImpl>()

    override fun addEndstop(endstop: Endstop) {
        require(endstop is McuEndstop)
        endstops.add(endstop)
        if (!mcus.contains(endstop.mcu)) {
            mcus.add(endstop.mcu as McuImpl)
        }
    }
    override fun addStepperMotor(motor: StepperMotor) {
        require(motor is McuStepperMotor)
        motors.add(motor)
        if (!mcus.contains(motor.mcu)) {
            mcus.add(motor.mcu)
        }
    }
    fun build(): EndstopSync {
        require(mcus.isNotEmpty())
        require(endstops.isNotEmpty())
        require(motors.isNotEmpty())
        val owner = this@McuEndstopSyncRuntimeBuilder
        val mcuToTrsync = buildMap { mcus.forEach { mcu ->
            put(mcu, mcu.trsyncPool.acquire(owner))
        }}
        val releaseFunc = {
            mcuToTrsync.forEach { (mcu, trsync) ->
                mcu.trsyncPool.release(trsync, owner)
            }
        }
        return MultiMcuEndstopSync(mcuToTrsync, endstops, motors, releaseFunc)
    }
}

/** Multi MCU capable endstop sync */
@OptIn(ExperimentalForeignApi::class)
class MultiMcuEndstopSync(
    val mcuToTrsync: Map<McuImpl, McuTrsync>,
    val endstops: List<McuEndstop>,
    val motors: List<McuStepperMotor>,
    val releaseFunc: (() -> Unit)?) : EndstopSync {
    val reactor = mcuToTrsync.values.first().runtime.reactor
    val trdispatch =  chelper.trdispatch_alloc() ?: throw RuntimeException("Failed to alloc trdispatch")
    override var state = MutableStateFlow<EndstopSync.State>(EndstopSync.StateIdle)
    val logger = KotlinLogging.logger("MultiMcuEndstopSync")
    init {
        mcuToTrsync.forEach { (mcu, trsync) -> trsync.acquire(this, mcu) }
    }

    override suspend fun reset() {
        if (state.value == EndstopSync.StateIdle) return
        if (state.value is EndstopSync.StateReleased) throw IllegalStateException("Sync released")
        logger.debug { "reset" }
        for (trsync in mcuToTrsync.values) {
            trsync.reset()
        }
        for (endstop in endstops) {
            endstop.reset()
        }
        for (stepper in motors) {
            stepper.resetTrigger()
        }
        state.value = EndstopSync.StateIdle
    }

    override suspend fun release() {
        logger.debug { "release" }
        val releaseF = releaseFunc ?: throw RuntimeException("Not a releasable instance")
        reset()
        state.value = EndstopSync.StateReleased
        chelper.trdispatch_free(trdispatch)
        mcuToTrsync.forEach { (_, trsync) -> trsync.release() }
        releaseF()
    }

    override suspend fun start(startTime: MachineTime, timeoutTime: MachineTime): Deferred<EndstopSync.State> {
        logger.debug { "start startTime=$startTime timeoutTime=$timeoutTime" }
        reset()

        // Check that we are not triggered already
        for (endstop in endstops) {
            if (endstop.queryState()) {
                return CompletableDeferred(EndstopSync.StateAlreadyTriggered)
            }
        }
        state.value = EndstopSync.StateRunning
        for (endstop in endstops) {
            val trsync = mcuToTrsync[endstop.mcu] ?: throw RuntimeException("Not supposed to happen")
            val runtime = trsync.runtime
            trsync.queue.send(CommandEndstopHome(
                id = endstop.id,
                clock = runtime.timeToClock32(startTime),
                sampleTicks = runtime.durationToClock(endstop.config.sampleInterval),
                sampleCount = endstop.config.sampleCount,
                restTicks = runtime.durationToClock(endstop.config.restTime),
                pinValue = !endstop.config.invert,
                trssyncId = trsync.id,
                triggerReason = TriggerResult.ENDSTOP_HIT.id))
        }
        val commsTimeout = 0.25
        val reportInterval = commsTimeout * 0.3
        val reactor = mcuToTrsync.values.first().runtime.reactor
        val results = mcuToTrsync.entries.map { e ->
            val trsync = e.value
            val runtime = trsync.runtime
            chelper.trdispatch_mcu_setup(trsync.trdispatchMcu,
                runtime.timeToClock(startTime),
                runtime.timeToClock(startTime + commsTimeout),
                runtime.durationToClock(commsTimeout).toULong(),
                runtime.durationToClock(commsTimeout * 0.24).toULong() + 1u)
            val result = trsync.start(startTime, timeoutTime, reportInterval, reportInterval)
            return@map McuTrsyncEntry(trsync, result)
        }
        for (stepper in motors) {
            val trsync = mcuToTrsync[stepper.mcu] ?: throw RuntimeException("Not supposed to happen")
            trsync.queue.send(CommandStepperStopOnTrigger(stepper.id, trsync.id))
        }
        chelper.trdispatch_start(trdispatch, TriggerResult.HOST_REQUEST.id.toUInt())
        return reactor.async {
            return@async try {
                logger.debug { "wait start" }
                results.map { it.result.await() }.reduce(::combineStates).also {
                    logger.debug { "wait done, result $it" }
                }
            } finally {
                chelper.trdispatch_stop(trdispatch)
            }
        }
    }

    data class McuTrsyncEntry(val trsync: McuTrsync, val result: Deferred<EndstopSync.State> )
}

/** Trigger sync inside a single MCU. */
@OptIn(ExperimentalForeignApi::class)
class McuTrsync(initialize: McuConfigure): McuComponent {
    var id = initialize.makeOid()
    val queue = initialize.makeCommandQueue("McuTrsync", 5)
    val logger = KotlinLogging.logger("McuTrsync $id")
    var timeoutTime: MachineTime? = null
    var trdispatchMcu: CPointer<trdispatch_mcu>? = null
    var activeTrigger: CompletableDeferred<EndstopSync.State>? = null
    lateinit var runtime: McuRuntime

    init {
        initialize.configCommand(CommandConfigTrsync(id))
        initialize.restartCommand(CommandTrsyncStart(id, 0U,0U,0U))
        initialize.responseHandler(ResponseTrsyncState::class, id, ::handleTrsyncState)
    }

    override fun start(runtime: McuRuntime) {
        this.runtime = runtime
    }

    fun handleTrsyncState(state: ResponseTrsyncState) {
        val timeout = timeoutTime
        val trigger = activeTrigger
        logger.debug { "state $state" }
        if (trigger != null && state.canTrigger == false) {
            // Triggered
            val result = parseTriggerResult(state.reason, runtime.clockToTime(state.clock))
            trigger.complete(result)
            activeTrigger = null
        } else if (timeout != null && timeout < runtime.clockToTime(state.clock)) {
            // Send timeout trigger; will be re-invoked with a timeout state.
            sendTrigger(TriggerResult.PAST_END_TIME)
            timeoutTime = null
        }
    }

    fun acquire(mm: MultiMcuEndstopSync, mcu: McuImpl) {
        logger.debug { "acquire" }
        if (trdispatchMcu != null) throw RuntimeException("Double acquire")
        trdispatchMcu = chelper.trdispatch_mcu_alloc(
            mm.trdispatch, mcu.connection.serial.ptr, queue.queue.ptr, id.toUInt(),
            mcu.connection.commands.tagFor(CommandTrsyncSetTimeout::class).toUInt(),
            mcu.connection.commands.tagFor(CommandTrsyncTrigger::class).toUInt(),
            mcu.connection.commands.tagFor(ResponseTrsyncState::class).toUInt(),
        ) ?: throw RuntimeException("Failed to alloc trdispatch mcu")
    }

    fun release() {
        logger.debug { "release" }
        require(activeTrigger == null)
        val tm = trdispatchMcu ?: throw RuntimeException("Relasing without acquire")
        chelper.trdispatch_mcu_free(tm)
        trdispatchMcu = null
    }

    fun sendTrigger(reason: TriggerResult) {
        queue.send(CommandTrsyncTrigger(id, reason.id))
    }

    fun reset() {
        queue.send(CommandTrsyncStart(id, 0U,0U,0U))
    }

    fun start(
        startTime: MachineTime,
        timeoutTime: MachineTime,
        firstReportOffset: MachineDuration,
        reportInterval: MachineDuration): Deferred<EndstopSync.State> {
        this.timeoutTime = timeoutTime
        if (activeTrigger != null) throw RuntimeException("Already running")
        logger.debug { "start" }
        val result = CompletableDeferred<EndstopSync.State>()
        activeTrigger = result
        val firstReportTime = startTime + firstReportOffset
        queue.send(
            CommandTrsyncStart(
                id, runtime.timeToClock32(firstReportTime),
                runtime.durationToClock(reportInterval),
                TriggerResult.COMMS_TIMEOUT.id
            )
        )
        queue.send(CommandTrsyncSetTimeout(id, runtime.timeToClock32(startTime + reportInterval * 3)))
        return result
    }
}

/** Pool of reusable trsync IDs */
class McuTrsyncPool(val mcu: Mcu, initialize: McuConfigure): McuComponent {
    val entries: List<TrsyncEntry> = buildList { repeat(5) {
        val trsync = McuTrsync(initialize).also { initialize.addComponent(it) }
        add(TrsyncEntry(trsync, null))
    } }

    fun acquire(owner: Any): McuTrsync {
        val entry = entries.firstOrNull { it.owner == null } ?: throw RuntimeException("Acquire: Not enough Trsyncs for MCU ${mcu.config.name}")
        entry.owner = owner
        return entry.trsync
    }

    fun release(trsync: McuTrsync, owner: Any) {
        val entry = entries.firstOrNull{ it.trsync === trsync } ?:  throw RuntimeException("Release: Trsync not found")
        if (entry.owner !== owner) {
            throw RuntimeException("Release: Trsync owner does not match, current owner ${entry.owner}, releasing owner $owner")
        }
        entry.owner = null
    }

    data class TrsyncEntry(val trsync: McuTrsync, var owner: Any?)
}

@RegisterMcuMessage(signature = "config_trsync oid=%c")
data class CommandConfigTrsync(override val id: ObjectId): McuObjectCommand
@RegisterMcuMessage(signature = "trsync_start oid=%c report_clock=%u report_ticks=%u expire_reason=%c")
data class CommandTrsyncStart(override val id: ObjectId, val reportClock: McuClock32, val reportTicks: McuClock32, val expireReason: UByte): McuObjectCommand
// Send a manual trigger to trsync.
@RegisterMcuMessage(signature = "trsync_trigger oid=%c reason=%c")
data class CommandTrsyncTrigger(override val id: ObjectId, val reason: UByte): McuObjectCommand
// Sets or extends the time when triggering is considred timed out.
@RegisterMcuMessage(signature = "trsync_set_timeout oid=%c clock=%u")
data class CommandTrsyncSetTimeout(override val id: ObjectId, val clock: McuClock32): McuObjectCommand
@RegisterMcuMessage(signature = "trsync_state oid=%c can_trigger=%c trigger_reason=%c clock=%u")
data class ResponseTrsyncState(override val id: ObjectId, val canTrigger: Boolean, val reason: UByte, val clock: McuClock32) : McuObjectResponse

fun parseTriggerResult(num: UByte, time: MachineTime) = when(num.toUInt()) {
    0U -> EndstopSync.StateRunning
    1u -> EndstopSync.StateTriggered(time)
    2u -> EndstopSync.StateCommsTimeout
    3u -> EndstopSync.StateReset
    4u -> EndstopSync.StatePastEndTime
    else -> throw RuntimeException("Invalid trigger result $num")
}

enum class TriggerResult(val id: UByte) {
    ENDSTOP_HIT(id = 1u),
    HOST_REQUEST(id = 2u),
    PAST_END_TIME(id = 3u),
    COMMS_TIMEOUT(id = 4u),
}

fun combineStates(a: EndstopSync.State, b: EndstopSync.State): EndstopSync.State {
    if (a is EndstopSync.StateTriggered && b is EndstopSync.StateTriggered) {
        return EndstopSync.StateTriggered(min(a.triggerTime, b.triggerTime))
    }
    return a
}
