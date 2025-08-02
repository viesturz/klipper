package parts.kinematics

import EndstopSyncBuilder
import MachineTime

class MotionActuatorFake(
    override val size: Int,
    var speeds: LinearSpeeds = LinearSpeeds()
): MotionActuator {
    val moves = ArrayList<MoveHistory>()
    override var axisStatus: List<RailStatus> = buildList { repeat(size) { add(RailStatus.INITIAL)} }
    override val positionTypes = MutableList(size) { MotionType.LINEAR }
    override var commandedPosition = List(size) { 0.0 }
    override var commandedEndTime: MachineTime = 0.0
    override fun computeMaxSpeeds(start: List<Double>, end: List<Double>) = speeds
    override fun checkMoveInBounds(start: List<Double>, end: List<Double>) = null
    override suspend fun home(axis: List<Int>): HomeResult = HomeResult.SUCCESS

    override fun setupTriggerSync(sync: EndstopSyncBuilder) {}

    override fun initializePosition(time: MachineTime, position: List<Double>) {
        commandedPosition = position
        commandedEndTime = time
    }

    override fun moveTo(
        startTime: MachineTime,
        endTime: MachineTime,
        startSpeed: Double,
        endSpeed: Double,
        endPosition: List<Double>
    ) {
        moves.add(MoveHistory(endTime, endPosition, endSpeed))
        commandedPosition = endPosition
        commandedEndTime = endTime
    }
    override fun generate(time: MachineTime) {}

    data class MoveHistory(val endTime: MachineTime, val endPosition: List<Double>, val endSpeed: Double)
}