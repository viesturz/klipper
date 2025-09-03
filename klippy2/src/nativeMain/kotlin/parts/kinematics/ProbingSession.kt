package parts.kinematics

import EndstopSync
import MachineTime
import combineStates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import mcu.components.McuEndstopSyncRuntimeBuilder
import parts.Trigger

/**
 *  A probing session detects trigger events and stops actuators first trigger.
 * */
fun makeProbingSession(block: ProbingSessionBuilder.() -> Unit) = ProbingSessionBuilder().also { it.block() }

/**
 *  A compound probing session consists of multiple independent trigger groups.
 *  And finishes when all of them are triggered.
 * */
fun makeCompoundProbingSession(block: CompoundProbingSessionBuilder.() -> Unit) = CompoundProbingSessionBuilder().also { it.block() }

class ProbingSessionBuilder() {
    val _currentSync = McuEndstopSyncRuntimeBuilder()
    val _rails = mutableListOf<LinearRail>()
    val _actuators = mutableSetOf<MotionActuator>()

    fun addTrigger(trigger: Trigger) {
        trigger.setupTriggerSync(_currentSync)
    }

    fun addRail(rail: LinearRail, actuator: MotionActuator) {
        _actuators.add(actuator)
        rail.setupTriggerSync(_currentSync)
        _rails.add(rail)
    }

    suspend inline fun <T> use(block: (session: ProbingSession) -> T): T {
        val syncs = listOf(SyncData(_currentSync.build(), _rails.toList()))
        val session = ProbingSession(syncs, _actuators.toList())
        try {
            return block(session)
        } finally {
            syncs.forEach { it.sync.release() }
        }
    }
}

class CompoundProbingSessionBuilder() {
    val _syncs = mutableListOf<SyncBuilderData>()
    val _actuators = mutableSetOf<MotionActuator>()

    /** Adds an independent trigger group. */
    fun addGroup(block: ProbingSessionBuilder.() -> Unit) {
        val builder = ProbingSessionBuilder().also { it.block() }
        _syncs.add(SyncBuilderData(builder._currentSync, builder._rails))
        _actuators.addAll(builder._actuators)
    }

    suspend inline fun <T> use(block: (session: ProbingSession) -> T): T {
        val syncs = _syncs.map { SyncData(it.sync.build(), it.rails) }
        val session = ProbingSession(syncs, _actuators.toList())
        try {
            return block(session)
        } finally {
            syncs.forEach { it.sync.release() }
        }
    }
}

data class SyncBuilderData(val sync: McuEndstopSyncRuntimeBuilder, val rails: List<LinearRail>)
data class SyncData(val sync: EndstopSync, val rails: List<LinearRail>)

class ProbingSession(var syncs: List<SyncData>, val actuators: List<MotionActuator>) {
    /** Run a probing move. Multiple probing moves can be run within the same session.
     *  @param startTime The time at which the session should start.
     *  @param block A block of code that runs the session. The block should perform the moves towards a probe trigger. And return a timeout time.
     *  @return The state of the endstops at the end of the session.
     *  */
    suspend fun probingMove(startTime: MachineTime, block: suspend () -> MachineTime): EndstopSync.State {
        val rlist = syncs.map { it.sync.start(startTime) }
        val alreadyTriggered = rlist.find { it.isCompleted }
        if (alreadyTriggered != null) {
            val result = alreadyTriggered.await()
            rlist.forEach { it.cancel() }
            return result
        }
        try {
            val timeoutTime = block()
            syncs.forEach { it.sync.setTimeoutTime(timeoutTime) }
            val x = rlist.awaitAll().reduce { a, b -> combineStates(a,b)}
            if (x is EndstopSync.StateTriggered) {
                syncs.forEach { sync -> sync.rails.forEach { it.updatePositionAfterTrigger(sync.sync) } }
                actuators.forEach { it.updatePositionAfterTrigger() }
            }
            return x
        }
        catch (e: CancellationException) {
            rlist.forEach { it.cancel() }
            throw e
        }
        finally {
            syncs.forEach { it.sync.reset() }
        }
    }
}