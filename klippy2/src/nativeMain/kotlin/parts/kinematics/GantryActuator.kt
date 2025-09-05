package parts.kinematics

import MachineTime
import machine.MoveOutsideRangeException
import machine.getNextMoveTime

data class GantryRail(val rail: LinearRail, val x: Double = 0.0, val y: Double = 0.0)

/** A single axis actuator via a 1d or 2d gantry supported by multiple linear rails.
 * Supports manual or automatic tramming. */
class GantryActuator(val rails: List<GantryRail>, val homing: Homing? = null): MotionActuator {
    val mainRail = rails[0].rail
    override val size = 1
    override val positionTypes = listOf(MotionType.LINEAR)
    override val commandedPosition: List<Double>
        get() = listOf(mainRail.commandedPosition)
    override val commandedEndTime: MachineTime
        get() = mainRail.commandedEndTime

    val range = rails.fold(LinearRange.UNLIMITED) { cur, next -> cur.intersection(next.rail.range) }

    override fun computeMaxSpeeds(start: List<Double>, end: List<Double>): LinearSpeeds = mainRail.speed
    override fun checkMoveInBounds(start: List<Double>,end: List<Double>): MoveOutsideRangeException? {
        if (mainRail.range.outsideRange(start[0])) return MoveOutsideRangeException("X=${start[0]} is outside the range ${mainRail.range}")
        if (mainRail.range.outsideRange(end[0])) return MoveOutsideRangeException("X=${end[0]} is outside the range ${mainRail.range}")
        return null
    }

    override suspend fun initializePosition(time: MachineTime, position: List<Double>, homed: Boolean) {
        require(position.size == 1)
        rails.forEach { it.rail.initializePosition(time, position[0], homed) }
    }

    override val axisStatus: List<RailStatus>
        get() = listOf(mainRail.railStatus)

    override suspend fun home(axis: List<Int>): HomeResult {
        if (rails.isEmpty()) return HomeResult.SUCCESS
        if (axis.isEmpty()) return HomeResult.SUCCESS
        require(axis.size == 1)
        require(axis[0] == 0)

        val startTime = getNextMoveTime()
        rails.forEach { it.rail.setPowered(time = startTime, value = true) }

        return when {
            homing != null -> homeWithSingleEndstop(homing)
            rails.all { it.rail.homing != null } -> return homeWithRailEndstops()
            else -> throw IllegalStateException("Homing not configured")
        }
    }

    private suspend fun homeWithSingleEndstop(homing: Homing): HomeResult {
        makeProbingSession {
            rails.forEach { addRail(it.rail, this@GantryActuator) }
            addTrigger(homing.endstopTrigger)
        }.use { session ->
            val homingMove = HomingMove(session, this, mainRail.runtime)
            val result = homingMove.homeOneAxis(0, homing, range)
            if (result == HomeResult.SUCCESS) {
                rails.forEach { it.rail.setHomed(true) }
            }
            return result
        }
    }

    private suspend fun homeWithRailEndstops(): HomeResult {
        val homingToUse = mainRail.homing ?: throw IllegalStateException("Homing not configured")
        makeCompoundProbingSession {
            for (rail in rails) {
                val h = rail.rail.homing ?: continue
                addGroup {
                    addTrigger(h.endstopTrigger)
                    addRail(rail.rail, this@GantryActuator)
                }
            }
        }.use { session ->
            val homingMove = HomingMove(session, this, mainRail.runtime)
            val result = homingMove.homeOneAxis(0, homingToUse, range)
            if (result == HomeResult.SUCCESS) {
                rails.forEach { it.rail.setHomed(true) }
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
        require(endPosition.size == 1)
        rails.forEach { it.rail.moveTo(startTime, endTime, startSpeed, endSpeed, endPosition[0]) }
    }

    override fun generate(time: MachineTime) {
        rails.forEach { it.rail.generate(time) }
    }
}
