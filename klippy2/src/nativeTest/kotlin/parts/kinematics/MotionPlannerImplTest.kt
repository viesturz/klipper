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
        config.axis('x', FakeActuator(speeds = LinearSpeeds(5.0, 1.0)))
        val planner = MotionPlannerImpl(config)

        planner.move(queue, KinMove(axis = "x", position = listOf(10.0), speed = null))

        assertEquals(1, queue.plannedCommands.size)
        val cmd = queue.plannedCommands[0] as MovePlan
        assertEquals( 10.0/5.0, cmd.minDuration , "Minium duration off")
    }

    @Test
    fun testMovePlan() {
        config.axis('x', FakeActuator(speeds = LinearSpeeds(5.0, 1.0)))
        val planner = MotionPlannerImpl(config)
        planner.move(queue, KinMove(axis = "x", position = listOf(10.0), speed = null))
        assertEquals(1, queue.plannedCommands.size)
        val cmd = queue.plannedCommands[0] as MovePlan

        val results = planner.tryPlan(0.0, listOf(cmd), true)

        assertEquals(1, results?.size)
    }

    @Test
    fun testNotAllAxis() {
        config.axis("xy", FakeActuator(size = 2, speeds = LinearSpeeds(5.0, 1.0)))
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

class FakeActuator(
    override val size: Int = 1,
    var speeds: LinearSpeeds = LinearSpeeds(100.0, 100.0),
    override val positionTypes: List<MotionType> = List(size) { MotionType.LINEAR},
    override var commandedPosition: List<Double> = List(size) { 0.0 }
) : MotionActuator {
    val moves = ArrayList<Move>()

    data class Move(val time: MachineTime, val position: List<Double>, val speed: Double)
    override fun checkMove(start: List<Double>, end: List<Double>) = speeds
    override fun initializePosition(time: MachineTime, position: List<Double>) {
        // Nothing here.
    }
    override fun moveTo(endTime: MachineTime, endPosition: List<Double>, endSpeed: Double) {
        moves.add(Move(endTime, endPosition, endSpeed))
    }
    override fun flush(time: MachineTime) {
        // Nothing here
    }
}
