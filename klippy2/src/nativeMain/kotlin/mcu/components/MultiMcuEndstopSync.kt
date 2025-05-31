package mcu.components

import Endstop
import EndstopSync
import EndstopSyncBuilder
import MachineDuration
import MachineTime
import StepperMotor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import mcu.McuClock32
import mcu.McuComponent
import mcu.McuConfigure
import mcu.McuImpl
import mcu.McuObjectResponse
import mcu.McuRuntime
import mcu.ObjectId
import mcu.ResponseParser
import kotlin.math.min

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

    fun build(): MultiMcuEndstopSync {
        require(mcus.size > 0)
        require(endstops.size > 0)
        require(motors.size > 0)
        val mcuToTrsync = buildMap { mcus.forEach { mcu -> put(mcu, mcu.acquireTrsync()) }}
        return MultiMcuEndstopSync(mcuToTrsync, endstops, motors)
    }
}

/** Multi MCU capable endstop sync */
class MultiMcuEndstopSync(
    val mcuToTrsync: Map<McuImpl, McuTrsync>,
    val endstops: List<McuEndstop>,
    val motors: List<McuStepperMotor>) : EndstopSync, McuComponent {
    override var state = MutableStateFlow<EndstopSync.State>(EndstopSync.StateIdle)
    lateinit var runtime: McuRuntime

    override fun start(runtime: McuRuntime) {
        this.runtime = runtime
    }

    override fun reset() {
        if (state.value == EndstopSync.StateIdle) return
        for (trsync in mcuToTrsync.values) {
            trsync.reset()
        }
        for (endstop in endstops) {
            endstop.reset()
        }
        for (stepper in motors) {
            val trsync = mcuToTrsync[stepper.mcu] ?: throw RuntimeException("Not supposed to happen")
            trsync.queue.send("reset_step_clock oid=%c clock=%u") {
                addId(stepper.id); addU(0U)
            }
        }
        state.value = EndstopSync.StateIdle
    }

    override fun release() {
        reset()
        TODO("Not yet implemented")
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun run(
        startTime: MachineTime,
        timeoutTime: MachineTime,
        pollInterval: MachineDuration,
        samplesToCheck: UByte,
        checkInterval: MachineDuration,
        stopOnValue: Boolean
    ): EndstopSync.State {
        reset()
        state.value = EndstopSync.StateRunning
        for (stepper in motors) {
            val trsync = mcuToTrsync[stepper.mcu] ?: throw RuntimeException("Not supposed to happen")
            trsync.queue.send("stepper_stop_on_trigger oid=%c trsync_oid=%c") {
                addId(stepper.id);addId(trsync.id)
            }
        }
        for (endstop in endstops) {
            val trsync = mcuToTrsync[endstop.mcu] ?: throw RuntimeException("Not supposed to happen")
            val runtime = trsync.runtime
            trsync.queue.send("endstop_home oid=%c clock=%u sample_ticks=%u sample_count=%c rest_ticks=%u pin_value=%c trsync_oid=%c trigger_reason=%c") {
                addId(endstop.id)
                addU(runtime.timeToClock32(startTime))
                addU(runtime.durationToClock(pollInterval))
                addC(samplesToCheck)
                addU(runtime.durationToClock(checkInterval))
                addBoolean(stopOnValue != endstop.config.invert)
                addId(trsync.id)
                addC(EndstopSync.TriggerResult.ENDSTOP_HIT.id)
            }
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
        initialize.configCommand("config_trsync oid=%c") { addId(id) }
        initialize.restartCommand("trsync_start oid=%c report_clock=%u report_ticks=%u expire_reason=%c") {
            addId(id);addU(0U);addU(0U);addC(0U)
        }
    }

    override fun start(runtime: McuRuntime) {
        this.runtime = runtime
    }

    fun sendTrigger(reason: EndstopSync.TriggerResult) {
        queue.send("trsync_trigger oid=%c reason=%c") {
            addId(id);addC(reason.id)
        }
    }

    fun reset() {
        queue.send("trsync_start oid=%c report_clock=%u report_ticks=%u expire_reason=%c") {
            addId(id);addU(0U);addU(0U);addC(0U)
        }
    }

    suspend fun run(
        startTime: MachineTime,
        timeoutTime: MachineTime,
        pollInterval: MachineDuration): EndstopSync.State {
        queue.send("trsync_start oid=%c report_clock=%u report_ticks=%u expire_reason=%c") {
            addId(id)
            addU(runtime.timeToClock32(startTime))
            addU(runtime.durationToClock(pollInterval))
            addC(EndstopSync.TriggerResult.COMMS_TIMEOUT.id)
        }
        queue.send("trsync_set_timeout oid=%c clock=%u") {
            addId(id)
            addU(runtime.timeToClock32(timeoutTime))
        }

        val response = queue.connection!!.setResponseHandlerOnce(responesTrsyncStateParser, id).await()
        return when (response.reason) {
            EndstopSync.TriggerResult.ENDSTOP_HIT -> EndstopSync.StateTriggered(runtime.clockToTime(response.clock))
            else -> EndstopSync.StateAborted(response.reason)
        }
    }
}

data class ResponseTrsyncState(override val id: ObjectId, val canTrigger: Boolean, val reason: EndstopSync.TriggerResult, val clock: McuClock32) :
    McuObjectResponse
val responesTrsyncStateParser = ResponseParser("trsync_state oid=%c can_trigger=%c trigger_reason=%c clock=%u") {
    ResponseTrsyncState(parseId(),  parseBoolean(), parseTriggerResult(parseI()), parseClock())
}

fun parseTriggerResult(num: Int) = when(num) {
    0 -> EndstopSync.TriggerResult.NONE
    1 -> EndstopSync.TriggerResult.ENDSTOP_HIT
    2 -> EndstopSync.TriggerResult.COMMS_TIMEOUT
    3 -> EndstopSync.TriggerResult.RESET
    4 -> EndstopSync.TriggerResult.PAST_END_TIME
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
