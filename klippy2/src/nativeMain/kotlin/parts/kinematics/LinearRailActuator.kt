package parts.kinematics

import EndstopSyncBuilder
import MachineTime
import io.github.oshai.kotlinlogging.KotlinLogging
import machine.MoveOutsideRangeException
import machine.getNextMoveTime

/** An actuator that has a single linear rail. */
class LinearRailActuator(val rail: LinearRail): MotionActuator {
    override val size = 1
    override val positionTypes = listOf(MotionType.LINEAR)
    override var commandedPosition: List<Double>
        get() = listOf(rail.commandedPosition)
        set(value) { rail.commandedPosition = value[0]}
    override val commandedEndTime: MachineTime
        get() = rail.commandedEndTime
    val logger = KotlinLogging.logger("Linear rail actuator")

    override fun computeMaxSpeeds(start: List<Double>, end: List<Double>): LinearSpeeds = rail.speeds
    override fun checkMoveInBounds(start: List<Double>,end: List<Double>): MoveOutsideRangeException? {
        if (rail.range.outsideRange(start[0])) return MoveOutsideRangeException("X=${start[0]} is outside the range ${rail.range}")
        if (rail.range.outsideRange(end[0])) return MoveOutsideRangeException("X=${end[0]} is outside the range ${rail.range}")
        return null
    }

    override fun initializePosition(time: MachineTime, position: List<Double>) {
        rail.initializePosition(time, position[0], false)
    }

    override fun setupTriggerSync(sync: EndstopSyncBuilder) {
        rail.setupTriggerSync(sync)
    }

    override val axisStatus: List<RailStatus>
        get() = listOf(rail.railStatus)

    override suspend fun home(axis: List<Int>): HomeResult {
        if (axis.isEmpty()) return HomeResult.SUCCESS
        require(axis.size == 1)
        require(axis[0] == 0)
        val homing = rail.homing ?: throw IllegalStateException("Homing not configured")
        logger.info { "Homing start" }

        val startTime = getNextMoveTime()
        if (!rail.railStatus.powered) {
            rail.setPowered(time = startTime, value = true)
        }
        makeProbingSession {
            addActuator(this@LinearRailActuator)
            addTrigger(homing.endstopTrigger)
        }.use { session ->
            val homingMove = HomingMove(session, this, rail.runtime)
            val result = homingMove.homeOneAxis(0, homing, rail.range)
            logger.info { "Homing result $result" }
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