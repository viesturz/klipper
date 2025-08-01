package parts.kinematics

import Endstop
import EndstopSync
import MachineTime
import StepperMotor
import kotlinx.coroutines.Deferred
import mcu.components.McuEndstopSyncRuntimeBuilder

class HomingMove() {
    var sync: EndstopSync? = null
    val builder = McuEndstopSyncRuntimeBuilder()
    var result: Deferred<EndstopSync.State>? = null

    fun addStepper(stepper: StepperMotor) {
        builder.addStepperMotor(stepper)
    }

    fun addEndstop(endstop: Endstop) {
        builder.addEndstop(endstop)
    }

    suspend fun start(startTime: MachineTime, timeoutTime: MachineTime) {
        if (result != null) throw IllegalStateException("Starting a homing move when one is already running")
        var s = sync
        if (s == null) {s = builder.build(); sync = s}
        result = s.start(startTime, timeoutTime)
    }

    suspend fun wait(): EndstopSync.State {
        val r = result ?: throw IllegalStateException("Not running")
        return r.await().also { result = null }
    }

    suspend fun allowMoves() {
        result?.cancel()
        result = null
        sync?.reset()
    }

    suspend fun release() {
        result?.cancel()
        result = null
        sync?.release()
        sync = null
    }

    suspend inline fun <T> use(block: () -> T): T = try {
        block()
    } finally {
        this.release()
    }
}