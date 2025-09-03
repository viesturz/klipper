package mcu.components

import Endstop
import EndstopSync
import EndstopSyncBuilder
import MachineDuration
import MachineTime
import Mcu
import McuClock
import StepperMotor
import chelper.trdispatch_mcu
import combineStates
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
            stepper.reset()
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

    override suspend fun start(startTime: MachineTime): Deferred<EndstopSync.State> {
        logger.debug { "start startTime=$startTime" }
        reset()

        // Check that we are not triggered already
        for (endstop in endstops) {
            if (endstop.queryState()) {
                return CompletableDeferred(EndstopSync.StateAlreadyTriggered(endstop))
            }
        }
        state.value = EndstopSync.StateRunning
        for (endstop in endstops) {
            val trsync = mcuToTrsync[endstop.mcu] ?: throw RuntimeException("Not supposed to happen")
            val runtime = trsync.runtime
            val triggerReason = (TriggerReason.ENDSTOP_HIT_WITH_ID.id + endstop.id).toUByte()
            trsync.addTrigger(triggerReason, endstop)
            trsync.queue.send(CommandEndstopHome(
                id = endstop.id,
                clock = runtime.timeToClock32(startTime),
                sampleTicks = runtime.durationToClock(endstop.config.sampleInterval),
                sampleCount = endstop.config.sampleCount,
                restTicks = runtime.durationToClock(endstop.config.restTime),
                pinValue = !endstop.config.invert,
                trssyncId = trsync.id,
                triggerReason = triggerReason))
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
            val result = trsync.start(startTime, reportInterval, reportInterval)
            return@map McuTrsyncEntry(trsync, result)
        }
        for (stepper in motors) {
            val trsync = mcuToTrsync[stepper.mcu] ?: throw RuntimeException("Not supposed to happen")
            trsync.queue.send(CommandStepperStopOnTrigger(stepper.id, trsync.id))
        }
        chelper.trdispatch_start(trdispatch, TriggerReason.HOST_REQUEST.id.toUInt())
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

    override fun setTimeoutTime(timeoutTime: MachineTime) {
        mcuToTrsync.values.forEach { it.setTimeoutTime(timeoutTime) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getTriggerClock(mcu: Mcu): McuClock {
        val trsync = mcuToTrsync.getValue(mcu as McuImpl)
        val triggerReason = trsync.finishedTrigger ?: throw RuntimeException("No active trigger or not completed")
        if (triggerReason !is EndstopSync.StateTriggered) throw RuntimeException("Trigger reason is not triggered: $triggerReason")
        return triggerReason.triggerClock
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
    var finishedTrigger: EndstopSync.State? = null
    val triggers = mutableMapOf<UByte, Endstop>()
    lateinit var runtime: McuRuntime

    init {
        initialize.configCommand(CommandConfigTrsync(id))
        initialize.restartCommand(CommandTrsyncStart(id, 0U,0U,0U))
        initialize.responseHandler(ResponseTrsyncState::class, id, ::handleTrsyncState)
    }

    override suspend fun start(runtime: McuRuntime) {
        this.runtime = runtime
    }

    fun addTrigger(reason: UByte, endstop: Endstop) {
        triggers[reason] = endstop
    }

    fun handleTrsyncState(state: ResponseTrsyncState) {
        val timeout = timeoutTime
        val trigger = activeTrigger
        logger.debug { "state $state" }
        if (trigger != null && state.canTrigger == false) {
            // Triggered
            val result = parseTriggerReason(state.reason, runtime.clock32ToClock(state.clock), runtime.clockToTime(state.clock))
            trigger.complete(result)
            finishedTrigger = result
            activeTrigger = null
        } else if (timeout != null && timeout < runtime.clockToTime(state.clock)) {
            // Send timeout trigger; will be re-invoked with a timeout state.
            sendTrigger(TriggerReason.PAST_END_TIME)
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

    fun sendTrigger(reason: TriggerReason) {
        queue.send(CommandTrsyncTrigger(id, reason.id))
    }

    fun reset() {
        sendTrigger(TriggerReason.HOST_REQUEST)
        queue.send(CommandTrsyncStart(id, 0U,0U,0U))
    }

    fun start(
        startTime: MachineTime,
        firstReportOffset: MachineDuration,
        reportInterval: MachineDuration): Deferred<EndstopSync.State> {
        this.timeoutTime = null
        if (activeTrigger != null) throw RuntimeException("Already running")
        logger.debug { "start" }
        val result = CompletableDeferred<EndstopSync.State>()
        activeTrigger = result
        finishedTrigger = null
        val firstReportTime = startTime + firstReportOffset
        queue.send(
            CommandTrsyncStart(
                id, runtime.timeToClock32(firstReportTime),
                runtime.durationToClock(reportInterval),
                TriggerReason.COMMS_TIMEOUT.id
            )
        )
        queue.send(CommandTrsyncSetTimeout(id, runtime.timeToClock32(startTime + reportInterval * 3)))
        return result
    }

    fun setTimeoutTime(timeoutTime: MachineTime) {
        this.timeoutTime = timeoutTime
    }

    fun parseTriggerReason(num: UByte, clock: McuClock, time: MachineTime) = when(num.toUInt()) {
        0U -> EndstopSync.StateRunning
        1u -> throw RuntimeException("Invalid trigger reason $num")
        2u -> EndstopSync.StateCommsTimeout
        3u -> EndstopSync.StateReset
        4u -> EndstopSync.StatePastEndTime
        else -> EndstopSync.StateTriggered(triggers.getValue(num), clock, time)
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

enum class TriggerReason(val id: UByte) {
    HOST_REQUEST(id = 1u),
    PAST_END_TIME(id = 2u),
    COMMS_TIMEOUT(id = 3u),
    ENDSTOP_HIT_WITH_ID(id = 4u), // + endstop object ID to identify exact endstop that was triggered.
}
