package parts.kinematics

import EndstopSyncBuilder
import MachineTime
import io.github.oshai.kotlinlogging.KotlinLogging
import machine.MoveOutsideRangeException
import machine.getNextMoveTime
import utils.distanceTo
import kotlin.math.absoluteValue

/** Creates CoreXY kinematics from two linear rails.
 * Uses a simple forward calculation instead of the solver, so can be used on any rail. */
class CoreXYKinematics(
    val railA: LinearRail,
    val railB: LinearRail,
    val xRange: LinearRange = LinearRange.UNLIMITED,
    val yRange: LinearRange = LinearRange.UNLIMITED,
    val xSpeed: LinearSpeeds = LinearSpeeds.UNLIMITED,
    val ySpeed: LinearSpeeds = LinearSpeeds.UNLIMITED,
    val xHoming: Homing? = null,
    val yHoming: Homing? = null,
): MotionActuator {
    val logger = KotlinLogging.logger("CoreXYKinematics")
    override val size = 2
    override val positionTypes = listOf(MotionType.LINEAR, MotionType.LINEAR)
    override var commandedEndTime: MachineTime = 0.0

    var _commandedPosition: List<Double> = listOf(0.0,0.0)
    override var commandedPosition: List<Double>
        get() = _commandedPosition
        set(value) {
            _commandedPosition = value
            val a = value[0] + value[1]
            val b = value[0] - value[1]
            railA.commandedPosition = a
            railB.commandedPosition = b
        }

    override var axisStatus: MutableList<RailStatus> = mutableListOf(RailStatus.INITIAL, RailStatus.INITIAL)

    // x = 0.5 * (a+b)
    // y = 0.5 * (a-b)
    // a = x + y
    // b = x - y

    override fun initializePosition(time: MachineTime, position: List<Double>) {
        require(position.size == 2)
        val a = position[0] + position[1]
        val b = position[0] - position[1]
        // The motors are never homed, they have no meaningful positions.
        railA.initializePosition(time, a, false)
        railB.initializePosition(time, b, false)
    }

    override fun setupTriggerSync(sync: EndstopSyncBuilder) {
        railA.setupTriggerSync(sync)
        railB.setupTriggerSync(sync)
    }

    override suspend fun home(axis: List<Int>): HomeResult {
        val startTime = getNextMoveTime()
        if (!railA.railStatus.powered) {
            railA.setPowered(time = startTime, value = true)
            axisStatus[0] = axisStatus[0].copy(powered = true)
        }
        if (!railB.railStatus.powered) {
            railB.setPowered(time = startTime, value = true)
            axisStatus[0] = axisStatus[0].copy(powered = true)
        }

        for (i in axis) {
            val result = when (i) {
                0 -> homeX()
                1 -> homeY()
                else -> throw IllegalArgumentException("Invalid axis index $i")
            }
            if (result != HomeResult.SUCCESS) {
                return result
            }
        }
        return HomeResult.SUCCESS
    }

    suspend fun homeX(): HomeResult {
        val homing = xHoming ?: throw RuntimeException("X Homing not configured")
        val range = xRange
        logger.info { "X Homing start" }
        makeProbingSession {
            addActuator(this@CoreXYKinematics)
            addTrigger(homing.endstopTrigger)
        }.use { session ->
            val homingMove = HomingMove(session, this, railA.runtime)
            val result = homingMove.homeOneAxis(0, homing, range)
            logger.info { "X Homing result $result" }
            if (result == HomeResult.SUCCESS) {
                axisStatus[0] = axisStatus[0].copy(homed = true)
            }
            return result
        }
    }

    suspend fun homeY(): HomeResult {
        val homing = yHoming ?: throw RuntimeException("Y Homing not configured")
        val range = yRange
        logger.info { "Y Homing start" }
        makeProbingSession {
            addActuator(this@CoreXYKinematics)
            addTrigger(homing.endstopTrigger)
        }.use { session ->
            val homingMove = HomingMove(session, this, railA.runtime)
            val result = homingMove.homeOneAxis(1, homing, range)
            logger.info { "Y Homing result $result" }
            if (result == HomeResult.SUCCESS) {
                axisStatus[1] = axisStatus[1].copy(homed = true)
            }
            return result
        }
    }

    override fun checkMoveInBounds(
        start: List<Double>,
        end: List<Double>
    ): MoveOutsideRangeException? {
        // Check XY range
        if (xRange.outsideRange(end[0])) return MoveOutsideRangeException("X=${end[0]} is outside the range $xRange")
        if (yRange.outsideRange(end[1])) return MoveOutsideRangeException("Y=${end[1]} is outside the range $yRange")
        val a2 = end[0] + end[1]
        val b2 = end[0] - end[1]
        val aRange = railA.range
        val bRange = railB.range
        if (aRange.outsideRange(a2)) return MoveOutsideRangeException("A=${end[0]} is outside the range $aRange")
        if (bRange.outsideRange(b2)) return MoveOutsideRangeException("B=${end[1]} is outside the range $bRange")
        return null
    }

    override fun computeMaxSpeeds(start: List<Double>, end: List<Double>): LinearSpeeds {

        var speeds = LinearSpeeds.UNLIMITED
        // Check XY speeds
        val xDistance = (end[0] - start[0]).absoluteValue
        val yDistance = (end[1] - start[1]).absoluteValue
        if (xDistance + yDistance == 0.0) return speeds

        val xyDist = start.distanceTo(end)
        if (xDistance > 0) {
            speeds = speeds.intersection(xSpeed * (xyDist / xDistance))
        }
        if (yDistance > 0) {
            speeds = speeds.intersection(ySpeed * (xyDist / yDistance))
        }
        val a1 = start[0] + start[1]
        val a2 = end[0] + end[1]
        val b1 = start[0] - start[1]
        val b2 = end[0] - end[1]
        // Check AB speeds
        val aDistance = (a2 - a1).absoluteValue
        val bDistance = (b2 - b1).absoluteValue
        val aSpeeds = railA.speeds
        val bSpeeds = railB.speeds
        if (aDistance > 0) {
            speeds = speeds.intersection(aSpeeds * (xyDist / aDistance))
        }
        if (bDistance > 0) {
            speeds = speeds.intersection(bSpeeds * (xyDist / bDistance))
        }
        return speeds
    }

    override fun moveTo(
        startTime: MachineTime,
        endTime: MachineTime,
        startSpeed: Double,
        endSpeed: Double,
        endPosition: List<Double>
    ) {
        require(startTime >= commandedEndTime)
        val a = endPosition[0] + endPosition[1]
        val b = endPosition[0] - endPosition[1]
        railA.moveTo(startTime, endTime, startSpeed, endSpeed, a)
        railB.moveTo(startTime, endTime, startSpeed, endSpeed, b)
        commandedEndTime = endTime
    }

    override fun generate(time: MachineTime) {
        railA.generate(time)
        railB.generate(time)
    }
}