package parts.kinematics

import EndstopSync
import MachineTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import mcu.components.McuEndstopSyncRuntimeBuilder
import parts.Trigger

fun makeProbingSession(block: ProbingSessionBuilder.() -> Unit) = ProbingSessionBuilder().also { it.block() }

class ProbingSessionBuilder {
    val builder = McuEndstopSyncRuntimeBuilder()
    val actuators = mutableListOf<MotionActuator>()

    fun addActuator(actuator: MotionActuator) {
        actuator.setupTriggerSync(builder)
        actuators.add(actuator)
    }

    fun addTrigger(trigger: Trigger) {
        trigger.setupTriggerSync(builder)
    }

    suspend inline fun <T> use(block: (session: ProbingSession) -> T): T {
        val session = ProbingSession(builder.build(), actuators)
        try {
            return block(session)
        } finally {
            session.release()
        }
    }
}

class ProbingSession(var sync: EndstopSync, val actuators: List<MotionActuator>) {
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

    suspend fun waitAndFinalize(): EndstopSync.State {
        val r = result ?: throw IllegalStateException("Not running")
        try {
            val x = r.await()
            result = null
            if (x is EndstopSync.StateTriggered) {
                actuators.forEach { it.updatePositionAfterTrigger(sync) }
            }
            sync.reset()
            return x
        }
        catch (e: CancellationException) {
            r.cancel()
            result = null
            sync.reset()
            throw e
        }
    }

    suspend fun release() {
        result?.cancel()
        result = null
        sync.release()
    }
}