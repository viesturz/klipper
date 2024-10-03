package parts.kinematics

/** Helper classes to configure the motion kinematics.
*  No logic here, just configuration parameters. */

interface LinearAxis: MotionActuator {
    val configuration: LinearAxisConfiguration
}

data class LinearAxisConfiguration(
    var range: LinearRange? = null,
    var speed: LinearSpeeds? = null,
    var homing: Homing? = null,
)

data class LinearRange(
    val positionMin: Double,
    val positionMax: Double,
)

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

data class LinearSpeeds(
    var speed: Double = Double.MAX_VALUE,
    var accel: Double = Double.MAX_VALUE,
    var minCruiseRatio: Double = 0.5,
    var squareCornerVelocity: Double = 5.0,
)

/** Add additional constraints to the axis */
fun ContstrainAxis(axis: LinearAxis, range: LinearRange? = null, speed: LinearSpeeds? = null): LinearAxis {
    return axis
}

/** Multiple motors driving the same axis */
fun CombineAxis(vararg axis: LinearAxis): LinearAxis {
    return axis[0]
}

enum class KinematicsType {
    NONE,
    COREXY,
    DELTA,
    POLAR,
}

data class Kinematics(val axis: List<LinearAxis>, val type: KinematicsType)

fun CoreXYKinematics(
    a: LinearAxis,
    b: LinearAxis,
    xRange: LinearRange,
    yRange: LinearRange,
    xHoming: Homing,
    yHoming: Homing,
    xSpeed: LinearSpeeds? = null,
    ySpeed: LinearSpeeds? = null,
): Kinematics {
//    return Kinematics(
//        val xAxis =
//
//        axis = mapOf('x' to xAxis, 'y' to yAxis)
    return Kinematics(axis = listOf(a, b), type = KinematicsType.COREXY)
}
