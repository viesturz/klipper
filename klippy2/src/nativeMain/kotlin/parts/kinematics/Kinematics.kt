package parts.kinematics

import MachineTime
import machine.MoveOutsideRangeException
import utils.distanceTo
import utils.sqrt
import utils.squared
import kotlin.math.absoluteValue

/** Helper classes to configure the motion kinematics.
*  No logic here, just configuration parameters. */

interface LinearAxis: MotionActuator {
    val range: LinearRange?
    val speeds: LinearSpeeds?
    val homing: Homing?
}

data class LinearRange(
    val positionMin: Double,
    val positionMax: Double,
) {
    fun intersection(other: LinearRange) = LinearRange(this.positionMin.coerceAtLeast(other.positionMin), this.positionMax.coerceAtMost(other.positionMax))
    fun outsideRange(d: Double) = positionMin > d || positionMax < d
    override fun toString() = "[$positionMin .. $positionMax]"
}

data class LinearSpeeds(
    var speed: Double = Double.MAX_VALUE,
    var accel: Double = Double.MAX_VALUE,
    var squareCornerVelocity: Double = 5.0,
    var minCruiseRatio: Double = 0.5,
){
    fun intersection(other: LinearSpeeds) = LinearSpeeds(
        this.speed.coerceAtMost(other.speed),
        this.accel.coerceAtMost(other.accel),
        this.squareCornerVelocity.coerceAtMost(other.squareCornerVelocity),
        this.minCruiseRatio.coerceAtLeast(other.minCruiseRatio))

    operator fun div(fraction: Double) = this * (1/fraction)
    operator fun times(fraction: Double) = LinearSpeeds(
        this.speed * fraction,
        this.accel * fraction,
        this.squareCornerVelocity * fraction,
        this.minCruiseRatio,
    )

    companion object {
        val UNLIMITED = LinearSpeeds(squareCornerVelocity = Double.MAX_VALUE, minCruiseRatio = 0.0)
    }
}

data class Homing(
    val endstopPosition: Double,
    val endstopTrigger: Trigger,
    val direction: HomingDirection,
    val speed: Double,
    val secondSpeed: Double? = null,
    val retractDist: Double,
)

enum class HomingDirection {
    MIN,
    MAX,
}

/** Add additional constraints to the axis */
fun ConstrainAxis(axis: LinearAxis, range: LinearRange? = null, speed: LinearSpeeds? = null): LinearAxis {
    return axis
}

/** Multiple motors driving the same axis */
class CombineAxis(vararg axis: LinearAxis) : LinearAxis {
    val axis: List<LinearAxis> = axis.toList()
    override val speeds: LinearSpeeds?
    override val range: LinearRange?
    override val homing: Homing? = null
    override val size: Int
    override val positionTypes: List<MotionType>
    init {
        require(axis.isNotEmpty())
        var sp = axis[0].speeds
        var ra = axis[0].range
        size = axis[0].size
        positionTypes = axis[0].positionTypes
        for (a in axis) {
            val s = a.speeds
            val r = a.range
            if (sp != null && s != null) sp = sp.intersection(s)
            if (ra!= null && r != null) ra = ra.intersection(r)
            require(size == axis.size) { "Axis size mismatch"}
            require(positionTypes == a.positionTypes) {"Axis position types mismatch"}
        }
        speeds = sp
        range = ra
    }

    override var commandedPosition: List<Double>
        get() = axis[0].commandedPosition
        set(value) {
            for (a in axis) {
                a.commandedPosition = value
            }
        }

    override fun checkMove(start: List<Double>, end: List<Double>): LinearSpeeds {
        var sp = axis[0].checkMove(start, end)
        for (a in axis) {
            sp = sp.intersection(a.checkMove(start, end))
        }
        return sp
    }
    override fun initializePosition(time: MachineTime, position: List<Double>) {
        for (a in axis) {
            a.initializePosition(time, position)
        }
    }

    override fun moveTo(
        startTime: MachineTime,
        endTime: MachineTime,
        startSpeed: Double,
        endSpeed: Double,
        endPosition: List<Double>
    ) {
        for (a in axis) {
            a.moveTo(startTime, endTime, startSpeed, endSpeed, endPosition)
        }
    }

    override fun flush(time: MachineTime) {
        for (a in axis) {
            a.flush(time)
        }
    }
}

class CoreXYKinematics(
    val axisA: LinearAxis,
    val axisB: LinearAxis,
    val xRange: LinearRange?,
    val yRange: LinearRange?,
    val xHoming: Homing,
    val yHoming: Homing,
    val xSpeed: LinearSpeeds? = null,
    val ySpeed: LinearSpeeds? = null,
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
            axisA.commandedPosition = listOf(a)
            axisB.commandedPosition = listOf(b)
        }

    // x = 0.5 * (a+b)
    // y = 0.5 * (a-b)
    // a = x + y
    // b = x - y

    init {
        require(axisA.size == 1)
        require(axisB.size == 1)
        require(axisA.positionTypes[0] == MotionType.LINEAR)
        require(axisB.positionTypes[0] == MotionType.LINEAR)
    }

    override fun initializePosition(time: MachineTime, position: List<Double>) {
        require(position.size == 2)
        val a = position[0] + position[1]
        val b = position[0] - position[1]
        axisA.initializePosition(time, listOf(a))
        axisA.initializePosition(time, listOf(b))
    }

    override fun checkMove(start: List<Double>, end: List<Double>): LinearSpeeds {
        // Check XY range
        if (xRange != null && xRange.outsideRange(end[0])) throw MoveOutsideRangeException("X=${end[0]} is outside the range $xRange")
        if (yRange != null && yRange.outsideRange(end[1])) throw MoveOutsideRangeException("Y=${end[1]} is outside the range $yRange")

        var speeds = LinearSpeeds.UNLIMITED
        // Check XY speeds
        val xDistance = (end[0] - start[0]).absoluteValue
        val yDistance = (end[1] - start[1]).absoluteValue
        if (xDistance + yDistance == 0.0) return speeds

        val xyDist = start.distanceTo(end)
        if (xSpeed != null && xDistance > 0) {
            speeds = speeds.intersection(xSpeed * (xyDist / xDistance))
        }
        if (ySpeed != null && yDistance > 0) {
            speeds = speeds.intersection(ySpeed * (xyDist / yDistance))
        }
        val a1 = start[0] + start[1]
        val a2 = end[0] + end[1]
        val b1 = start[0] - start[1]
        val b2 = end[0] - end[1]
        // Check AB range
        val aRange = axisA.range
        val bRange = axisB.range
        if (aRange != null && aRange.outsideRange(a2)) throw MoveOutsideRangeException("A=${end[0]} is outside the range $aRange")
        if (bRange != null && bRange.outsideRange(b2)) throw MoveOutsideRangeException("B=${end[1]} is outside the range $bRange")

        // Check AB speeds
        val aDistance = (a2 - a1).absoluteValue
        val bDistance = (b2 - b1).absoluteValue
        val aSpeeds = axisA.speeds
        val bSpeeds = axisB.speeds
        if (aSpeeds != null && aDistance > 0) {
            speeds = speeds.intersection(aSpeeds * (xyDist / aDistance))
        }
        if (bSpeeds != null && bDistance > 0) {
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
        axisA.moveTo(startTime, endTime, startSpeed, endSpeed, listOf(a))
        axisB.moveTo(startTime, endTime, startSpeed, endSpeed, listOf(b))
    }

    override fun flush(time: MachineTime) {
        axisA.flush(time)
        axisB.flush(time)
    }
}
