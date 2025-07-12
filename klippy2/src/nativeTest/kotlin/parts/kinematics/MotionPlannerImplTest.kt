package parts.kinematics

import MachineTime
import kotlinx.coroutines.Deferred
import machine.CommandQueue
import machine.Planner
import machine.QueueManager
import machine.Reactor
import kotlin.test.Test
import kotlin.test.assertEquals

class MotionPlannerImplTest {
    val queue = FakeQueue()
    val config = MotionPlannerConfig()

    @Test
    fun testMinDuration() {
        config.axis('x', MotionActuatorFake(1, speeds = LinearSpeeds(5.0, 1.0)))
        val planner = MotionPlannerImpl(config)

        planner.move(queue, KinMove(axis = "x", position = listOf(10.0), speed = null))

        assertEquals(1, queue.plannedCommands.size)
        val cmd = queue.plannedCommands[0] as MovePlan
        assertEquals( 10.0/5.0, cmd.minDuration , "Minium duration off")
    }

    @Test
    fun testMovePlan() {
        config.axis('x', MotionActuatorFake(1, speeds = LinearSpeeds(5.0, 1.0)))
        val planner = MotionPlannerImpl(config)
        planner.move(queue, KinMove(axis = "x", position = listOf(10.0), speed = null))
        assertEquals(1, queue.plannedCommands.size)
        val cmd = queue.plannedCommands[0] as MovePlan

        val results = planner.tryPlan(0.0, listOf(cmd), true)

        assertEquals(1, results?.size)
    }

    @Test
    fun testNotAllAxis() {
        config.axis("xy", MotionActuatorFake(size = 2, speeds = LinearSpeeds(5.0, 1.0)))
        val planner = MotionPlannerImpl(config)

        planner.move(queue, KinMove(axis = "x", position = listOf(10.0), speed = null))

        assertEquals(queue.plannedCommands.size, 1)
        val cmd = queue.plannedCommands[0] as MovePlan
        assertEquals(1, cmd.actuatorMoves.size)
        val move = cmd.actuatorMoves[0]
        assertEquals(listOf(0.0,0.0), move.startPosition)
        assertEquals(listOf(10.0,0.0), move.endPosition)
    }
}

class FakeQueue: CommandQueue {
    val plannedCommands = ArrayList<Any>()

    override val manager: QueueManager
        get() = TODO("Not yet implemented")
    override val reactor: Reactor
        get() = TODO("Not yet implemented")

    override fun add(block: (time: MachineTime) -> Unit) {
    }

    override fun <T: Any> addPlanned(planner: Planner<T>, data: T) {
        plannedCommands.add(data)
    }

    override fun addLongRunning(block: suspend () -> Unit) {
    }

    override fun wait(deferred: Deferred<MachineTime>) {
    }

    override fun fork(): CommandQueue {
        return this
    }

    override fun join(other: CommandQueue) {
    }

    override fun close() {
    }

    override fun isClosed() = false

    override fun abort() {
    }

    override fun tryGenerate() {
    }

    override suspend fun flush() = 0.0
}
