package parts.kinematics

import EndstopSync
import MachineTime
import kotlinx.coroutines.Deferred
import mcu.components.McuEndstopSyncRuntimeBuilder
import parts.Trigger

fun makeProbingSession(block: ProbingSessionBuilder.() -> Unit): ProbingSession {
    val b = ProbingSessionBuilder()
    b.block()
    return ProbingSession(b.builder.build())
}

class ProbingSessionBuilder {
    val builder = McuEndstopSyncRuntimeBuilder()

    fun addActuator(actuator: MotionActuator) {
        actuator.setupTriggerSync(builder)
    }

    fun addTrigger(trigger: Trigger) {
        trigger.setupTriggerSync(builder)
    }
}

class ProbingSession(var sync: EndstopSync) {
    var result: Deferred<EndstopSync.State>? = null

    suspend fun start(startTime: MachineTime, timeoutTime: MachineTime): EndstopSync.State? {
        if (result != null) throw IllegalStateException("Starting a homing move when one is already running")
        val r = sync.start(startTime, timeoutTime)
        if (r.isCompleted) {
            return r.await()
        } else {
            result = r
            return null
        }
    }

    suspend fun wait(): EndstopSync.State {
        val r = result ?: throw IllegalStateException("Not running")
        return r.await().also { result = null }
    }

    suspend fun allowMoves() {
        result?.cancel()
        result = null
        sync.reset()
    }

    suspend fun release() {
        result?.cancel()
        result = null
        sync.release()
    }

    suspend inline fun <T> use(block: () -> T): T = try {
        block()
    } finally {
        this.release()
    }
}