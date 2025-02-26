package parts.kinematics

import MachineTime

class MotionActuatorFake(
    override val size: Int,
    var speeds: LinearSpeeds = LinearSpeeds(),
): MotionActuator {
    val moves = ArrayList<MoveHistory>()

    override val positionTypes = MutableList(size) { MotionType.LINEAR }
    override var commandedPosition = List(size) { 0.0 }
    override fun checkMove(start: List<Double>, end: List<Double>) = speeds
    override fun initializePosition(time: MachineTime, position: List<Double>) {
        commandedPosition = position
    }

    override fun moveTo(
        startTime: MachineTime,
        endTime: MachineTime,
        startSpeed: Double,
        endSpeed: Double,
        endPosition: List<Double>
    ) {
        moves.add(MoveHistory(endTime, endPosition, endSpeed))
    }
    override fun generate(time: MachineTime) {}

    data class MoveHistory(val endTime: MachineTime, val endPosition: List<Double>, val endSpeed: Double)
}