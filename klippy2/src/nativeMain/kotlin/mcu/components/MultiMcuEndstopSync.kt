package mcu.components

import Endstop
import EndstopSync
import EndstopSyncBuilder
import MachineDuration
import MachineTime
import Mcu
import StepperMotor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.awaitAll
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
        val mcuToTrsync = buildMap { mcus.forEach { mcu -> put(mcu, mcu.trsyncPool.acquire(this)) }}
        val releaseFunc = { ->
            mcuToTrsync.forEach { (mcu, trsync) ->
                mcu.trsyncPool.release(trsync, this)
            }
        }
        return MultiMcuEndstopSync(mcuToTrsync, endstops, motors, releaseFunc)
    }
}

/** Multi MCU capable endstop sync */
class MultiMcuEndstopSync(
    val mcuToTrsync: Map<McuImpl, McuTrsync>,
    val endstops: List<McuEndstop>,
    val motors: List<McuStepperMotor>,
    val releaseFunc: (() -> Unit)?) : EndstopSync, McuComponent {
    override var state = MutableStateFlow<EndstopSync.State>(EndstopSync.StateIdle)
    lateinit var runtime: McuRuntime

    override fun start(runtime: McuRuntime) {
        this.runtime = runtime
    }

    override fun reset() {
        if (state.value == EndstopSync.StateIdle) return
        if (state.value is EndstopSync.StateReleased) throw IllegalStateException("Sync released")
        for (trsync in mcuToTrsync.values) {
            trsync.reset()
        }
        for (endstop in endstops) {
            endstop.reset()
        }
        for (stepper in motors) {
            val trsync = mcuToTrsync[stepper.mcu] ?: throw RuntimeException("Not supposed to happen")
            trsync.queue.send(CommandResetStepClock(stepper.id, 0U))
        }
        state.value = EndstopSync.StateIdle
    }

    override fun release() {
        val rf = releaseFunc ?: throw RuntimeException("Not a releasable instance")
        reset()
        rf()
        state.value = EndstopSync.StateReleased
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun run(
        startTime: MachineTime,
        timeoutTime: MachineTime,
        pollInterval: MachineDuration,
        samplesToCheck: UByte,
        checkInterval: MachineDuration): EndstopSync.State {
        reset()
        state.value = EndstopSync.StateRunning
        for (stepper in motors) {
            val trsync = mcuToTrsync[stepper.mcu] ?: throw RuntimeException("Not supposed to happen")
            trsync.queue.send(CommandStepperStopOnTrigger(stepper.id, trsync.id))
        }
        for (endstop in endstops) {
            val trsync = mcuToTrsync[endstop.mcu] ?: throw RuntimeException("Not supposed to happen")
            val runtime = trsync.runtime
            trsync.queue.send(CommandEndstopHome(
                id =endstop.id,
                clock = runtime.timeToClock32(startTime),
                sampleTicks = runtime.durationToClock(pollInterval),
                sampleCount = samplesToCheck,
                restTicks = runtime.durationToClock(checkInterval),
                pinValue = !endstop.config.invert,
                trssyncId = trsync.id,
                triggerReason = EndstopSync.TriggerResult.ENDSTOP_HIT.id))
        }
        if (mcuToTrsync.size == 1) {
            // Single MCU, wait for the trigger result.
            return mcuToTrsync.values.first().run(startTime, timeoutTime, pollInterval)
        } else {
            // Multiple MCUs - setup trigger dispatch
            val trdispatch =  chelper.trdispatch_alloc()
            for (e in mcuToTrsync.entries) {
                val mcu = e.key
                val trsync = e.value
//                val trdispatchMcy = chelper.trdispatch_mcu_alloc(trdispatch, )

            }
            TODO("Setup trigger dispatch")
            val result = mcuToTrsync.values.map { runtime.reactor.async { it.run(startTime, timeoutTime, pollInterval) } }.awaitAll().reduce(::combineStates)
            TODO("Cleanup trigger dispatch")
            return result
        }
    }
}

/** Trigger sync inside a single MCU. */
class McuTrsync(initialize: McuConfigure): McuComponent {
    var id = initialize.makeOid()
    val queue = initialize.makeCommandQueue("McuTrsync", 5)
    lateinit var runtime: McuRuntime

    init {
        initialize.configCommand(CommandConfigTrsync(id))
        initialize.restartCommand(CommandTrsyncStart(id, 0U,0U,0U))
    }

    override fun start(runtime: McuRuntime) {
        this.runtime = runtime
    }

    fun sendTrigger(reason: EndstopSync.TriggerResult) {
        queue.send(CommandTrsyncTrigger(id, reason.id))
    }

    fun reset() {
        queue.send(CommandTrsyncStart(id, 0U,0U,0U))
    }

    suspend fun run(
        startTime: MachineTime,
        timeoutTime: MachineTime,
        pollInterval: MachineDuration): EndstopSync.State {
        queue.send(CommandTrsyncStart(id, runtime.timeToClock32(startTime),
            runtime.durationToClock(pollInterval),
            EndstopSync.TriggerResult.COMMS_TIMEOUT.id))
        queue.send(CommandTrsyncSetTimeout(id, runtime.timeToClock32(timeoutTime)))
        val response = queue.connection!!.setResponseHandlerOnce(ResponseTrsyncState::class, id).await()
        return when (parseTriggerResult(response.reason)) {
            EndstopSync.TriggerResult.ENDSTOP_HIT -> EndstopSync.StateTriggered(runtime.clockToTime(response.clock))
            else -> EndstopSync.StateAborted(parseTriggerResult(response.reason))
        }
    }
}

/** Pool of reusable trsync IDs */
class McuTrsyncPool(val mcu: Mcu, initialize: McuConfigure): McuComponent {
    val entries: List<TrsyncEntry> = buildList { repeat(5) {
        add(TrsyncEntry(McuTrsync(initialize), null))
    } }

    fun acquire(owner: Any): McuTrsync {
        val entry = entries.firstOrNull { it.owner == null } ?: throw RuntimeException("Acquire: Not enough Trsyncs for MCU ${mcu.config.name}")
        entry.owner = owner
        return entry.trsync
    }

    fun release(trsync: McuTrsync, owner: Any) {
        val entry = entries.firstOrNull{ it.trsync === trsync } ?:  throw RuntimeException("Release: Trsync not found")
        if (entry.owner !== owner) {
            throw RuntimeException("Release: Trsync owner does not match")
        }
        entry.owner = null
    }

    data class TrsyncEntry(val trsync: McuTrsync, var owner: Any?)
}

@RegisterMcuMessage(signature = "config_trsync oid=%c")
data class CommandConfigTrsync(override val id: ObjectId): McuObjectCommand
@RegisterMcuMessage(signature = "trsync_start oid=%c report_clock=%u report_ticks=%u expire_reason=%c")
data class CommandTrsyncStart(override val id: ObjectId, val reportClock: McuClock32, val reportTicks: McuClock32, val expireReason: UByte): McuObjectCommand
@RegisterMcuMessage(signature = "trsync_trigger oid=%c reason=%c")
data class CommandTrsyncTrigger(override val id: ObjectId, val reason: UByte): McuObjectCommand
@RegisterMcuMessage(signature = "trsync_set_timeout oid=%c clock=%u")
data class CommandTrsyncSetTimeout(override val id: ObjectId, val clock: McuClock32): McuObjectCommand
@RegisterMcuMessage(signature = "trsync_state oid=%c can_trigger=%c trigger_reason=%c clock=%u")
data class ResponseTrsyncState(override val id: ObjectId, val canTrigger: Boolean, val reason: UInt, val clock: McuClock32) : McuObjectResponse

fun parseTriggerResult(num: UInt) = when(num) {
    0U -> EndstopSync.TriggerResult.NONE
    1u -> EndstopSync.TriggerResult.ENDSTOP_HIT
    2u -> EndstopSync.TriggerResult.COMMS_TIMEOUT
    3u -> EndstopSync.TriggerResult.RESET
    4u -> EndstopSync.TriggerResult.PAST_END_TIME
    else -> throw RuntimeException("Invalid trigger result $num")
}

fun combineStates(a: EndstopSync.State, b: EndstopSync.State): EndstopSync.State {
    if (a is EndstopSync.StateTriggered && b is EndstopSync.StateTriggered) {
        return EndstopSync.StateTriggered(min(a.triggerTime, b.triggerTime))
    }
    if (a is EndstopSync.StateAborted) {
        return a
    }
    if (b is EndstopSync.StateAborted) {
        return b
    }
    if (b is EndstopSync.StateRunning) {
        return b
    }
    return a
}
