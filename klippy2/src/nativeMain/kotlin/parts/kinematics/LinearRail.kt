package parts.kinematics

import MachineTime

interface LinearRail {
    val railStatus: RailStatus
    var commandedPosition: Double
    val range: LinearRange
    val speeds: LinearSpeeds
    val homing: Homing?

    suspend fun setPowered(time: MachineTime, value: Boolean)
    fun checkMove(start: Double, end: Double): LinearSpeeds

    // To drive the rail directly without kinematics
    fun initializePosition(time: MachineTime, position: Double, homed: Boolean)
    fun moveTo(
        startTime: MachineTime,
        endTime: MachineTime,
        startSpeed: Double,
        endSpeed: Double,
        endPosition: Double
    )
    /** Generates move commands up to the given time. */
    fun generate(time: MachineTime)
}

data class RailStatus(
    val powered: Boolean,
    // When true, the rail position is accurate to the endstops.
    // When false the position is arbitrary.
    val homed: Boolean) {

    fun combine(other: RailStatus) = RailStatus(
        powered = this.powered && other.powered,
        homed = this.homed && other.homed)

    companion object {
        val INITIAL = RailStatus(powered = false, homed = false)
    }
}

data class LinearRange(
    val positionMin: Double,
    val positionMax: Double,
) {
    fun intersection(other: LinearRange) = LinearRange(this.positionMin.coerceAtLeast(other.positionMin), this.positionMax.coerceAtMost(other.positionMax))
    fun outsideRange(d: Double) = positionMin > d || positionMax < d
    override fun toString() = "[$positionMin .. $positionMax]"

    companion object {
        val UNLIMITED = LinearRange(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)

        fun intersection(first: List<LinearRange>?, second: List<LinearRange>?): List<LinearRange>? =
            if (first == null || second == null) null else
                first.mapIndexed { i, r -> r.intersection(second[i]) }
    }
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

