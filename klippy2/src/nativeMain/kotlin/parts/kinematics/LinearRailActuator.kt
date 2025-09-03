package parts.kinematics

import MachineTime
import machine.MoveOutsideRangeException
import machine.getNextMoveTime

/** An actuator that has a single linear rail. */
class LinearRailActuator(val rail: LinearRail): MotionActuator {
    override val size = 1
    override val positionTypes = listOf(MotionType.LINEAR)
    override val commandedPosition: List<Double>
        get() = listOf(rail.commandedPosition)
    override val commandedEndTime: MachineTime
        get() = rail.commandedEndTime

    override fun computeMaxSpeeds(start: List<Double>, end: List<Double>): LinearSpeeds = rail.speeds
    override fun checkMoveInBounds(start: List<Double>,end: List<Double>): MoveOutsideRangeException? {
        if (rail.range.outsideRange(start[0])) return MoveOutsideRangeException("X=${start[0]} is outside the range ${rail.range}")
        if (rail.range.outsideRange(end[0])) return MoveOutsideRangeException("X=${end[0]} is outside the range ${rail.range}")
        return null
    }

    override suspend fun initializePosition(time: MachineTime, position: List<Double>, homed: Boolean) {
        rail.initializePosition(time, position[0], homed)
    }

    override val axisStatus: List<RailStatus>
        get() = listOf(rail.railStatus)

    override suspend fun home(axis: List<Int>): HomeResult {
        if (axis.isEmpty()) return HomeResult.SUCCESS
        require(axis.size == 1)
        require(axis[0] == 0)
        val homing = rail.homing ?: throw IllegalStateException("Homing not configured")

        val startTime = getNextMoveTime()
        if (!rail.railStatus.powered) {
            rail.setPowered(time = startTime, value = true)
        }
        makeProbingSession {
            addRail(rail, this@LinearRailActuator)
            addTrigger(homing.endstopTrigger)
        }.use { session ->
            val homingMove = HomingMove(session, this, rail.runtime)
            val result = homingMove.homeOneAxis(0, homing, rail.range)
            if (result == HomeResult.SUCCESS) {
                rail.setHomed(true)
            }
            return result
        }
    }

    override fun moveTo(
        startTime: MachineTime,
        endTime: MachineTime,
        startSpeed: Double,
        endSpeed: Double,
        endPosition: List<Double>
    ) {
        rail.moveTo(startTime, endTime, startSpeed, endSpeed, endPosition[0])
    }

    override fun generate(time: MachineTime) {
        rail.generate(time)
    }
}