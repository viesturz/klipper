package parts.kinematics

import MachineTime
import machine.MoveOutsideRangeException
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
    override val size = 2
    override val positionTypes = listOf(MotionType.LINEAR, MotionType.LINEAR)
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

    // x = 0.5 * (a+b)
    // y = 0.5 * (a-b)
    // a = x + y
    // b = x - y

    override fun initializePosition(time: MachineTime, position: List<Double>) {
        require(position.size == 2)
        val a = position[0] + position[1]
        val b = position[0] - position[1]
        railA.initializePosition(time, a)
        railA.initializePosition(time, b)
    }

    override fun checkMove(start: List<Double>, end: List<Double>): LinearSpeeds {
        // Check XY range
        if (xRange.outsideRange(end[0])) throw MoveOutsideRangeException("X=${end[0]} is outside the range $xRange")
        if (yRange.outsideRange(end[1])) throw MoveOutsideRangeException("Y=${end[1]} is outside the range $yRange")

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
        // Check AB range
        val aRange = railA.range
        val bRange = railB.range
        if (aRange.outsideRange(a2)) throw MoveOutsideRangeException("A=${end[0]} is outside the range $aRange")
        if (bRange.outsideRange(b2)) throw MoveOutsideRangeException("B=${end[1]} is outside the range $bRange")

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
        val a = endPosition[0] + endPosition[1]
        val b = endPosition[0] - endPosition[1]
        railA.moveTo(startTime, endTime, startSpeed, endSpeed, a)
        railB.moveTo(startTime, endTime, startSpeed, endSpeed, b)
    }

    override fun generate(time: MachineTime) {
        railA.generate(time)
        railB.generate(time)
    }
}