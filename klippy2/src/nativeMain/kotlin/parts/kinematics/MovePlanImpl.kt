package parts.kinematics

import MachineTime
import utils.distanceTo
import utils.magnitude
import utils.moveBy
import utils.sqrt
import utils.squared

data class MoveSpeeds(
    /** Max move speed per distance to travel. To easier apply the speeds among actuators.
     * speed = speedPerMm * distance
     * Also the duration of the move in seconds = 1/speedPerMm.
     */
    val speedPerMm: Double,
    /** Move acceleration per distance to travel. To easier apply the speeds among actuators.
     * accel = accelPerMm * distance
     */
    val accelPerMm: Double,
    val squareCornerVelocity: Double,
    val minCruiseRatio: Double)
{
    fun limitTo(speeds: MoveSpeeds) = MoveSpeeds(
        speedPerMm = speedPerMm.coerceAtMost(speeds.speedPerMm),
        accelPerMm = accelPerMm.coerceAtMost(speeds.accelPerMm),
        squareCornerVelocity = squareCornerVelocity.coerceAtMost(speeds.squareCornerVelocity),
        minCruiseRatio = minCruiseRatio.coerceAtMost(speeds.minCruiseRatio),
    )
    companion object {
        val UNLIMITED = MoveSpeeds(
            speedPerMm = Double.MAX_VALUE,
            accelPerMm = Double.MAX_VALUE,
            squareCornerVelocity = Double.MAX_VALUE,
            minCruiseRatio = 1.0,
        )

        fun from(speeds: LinearSpeeds, distance: Double) = MoveSpeeds(
            speedPerMm = if (distance > 0) speeds.speed / distance else Double.MAX_VALUE,
            accelPerMm = if (distance > 0) speeds.accel / distance else Double.MAX_VALUE,
            minCruiseRatio = speeds.minCruiseRatio,
            squareCornerVelocity = speeds.squareCornerVelocity,
        )
    }
}

data class MovePlanActuator(
    val move: MovePlan,
    val actuator: MotionActuator,
    val startPosition: List<Double>,
    val endPosition: List<Double>,
    val distance: Double,
    var speeds: MoveSpeeds,
) {
    var previous: MovePlanActuator? = null
    var next: MovePlanActuator? = null
}

/** Plan for a single move. */
data class MovePlan(
    val actuatorMoves: List<MovePlanActuator>,
    // Actuator moves, split by kinetic groups.
    val kinMoves: List<List<MovePlanActuator>>,
) {
    var minDuration: Double = 0.0

    /** Maximum junction speed between this and previous move. */
    var speeds = MoveSpeeds.UNLIMITED
    var maxJunctionSpeedPerMm: Double = 0.0

    /** Move start and end speeds. */
    var startSpeedPerMm: Double = 0.0
    var endSpeedPerMm: Double = 0.0
    var cruiseSpeedPerMm: Double = 0.0
    var accelDistPerMm: Double = 0.0
    var cruiseDistPerMm: Double = 0.0
    var decelDistPerMm: Double = 0.0
    var accelDuration: Double = 0.0
    var cruiseDuration: Double = 0.0
    var decelDuration: Double = 0.0
    var startTime: MachineTime = 0.0
    var endTime: MachineTime = 0.0

    fun calcJunctionSpeed() {
        // Find common minimum speed for all moves.
        val commonSpeeds = actuatorMoves.fold(MoveSpeeds.UNLIMITED) { acc, act -> acc.limitTo(act.speeds) }
        if (commonSpeeds.speedPerMm > 0) {
            minDuration = 1.0/commonSpeeds.speedPerMm
        }
        this.speeds = commonSpeeds
        for (aMove in actuatorMoves) {
            aMove.speeds = commonSpeeds
        }
        maxJunctionSpeedPerMm = kinMoves.minOfOrNull { kMove -> calcMaxJunctionSpeed(kMove) } ?: 0.0
    }

    /** Calculate maximum junction speed for a kin move. */
    private fun calcMaxJunctionSpeed(actuatorMoves: List<MovePlanActuator>): Double {
        val v1 = ArrayList<Double>(2)
        val v2 = ArrayList<Double>(2)
        var prevMoveSpeeds = MoveSpeeds.UNLIMITED
        var nextMoveSpeeds = MoveSpeeds.UNLIMITED
        for (aMove in actuatorMoves) {
            v2.addAll(aMove.startPosition.zip(aMove.endPosition) { s, e -> e - s })
            val prev = aMove.previous
            if (prev != null && prev.distance > 0.0) {
                v1.addAll(prev.startPosition.zip(aMove.startPosition) { s, e -> e - s })
                prevMoveSpeeds = prevMoveSpeeds.limitTo(prev.speeds)
            } else {
                v1.addAll(aMove.startPosition)
            }
            nextMoveSpeeds = nextMoveSpeeds.limitTo(aMove.speeds)
        }
        val distance = v2.magnitude()
        return if (distance > 0.0) {
            calculateJunctionSpeedSq(v1, v2, prevMoveSpeeds, nextMoveSpeeds).sqrt() / distance
        } else 0.0
    }

    /** Calculate max start and end speeds for the move, processing backwards from end to start. */
    fun calcSpeedsBackwards(): MoveSpeedStatus {
        endSpeedPerMm = actuatorMoves.minOfOrNull { aMove ->
            val next = aMove.next
            when {
            aMove.distance == 0.0 -> 0.0
            next == null -> 0.0
            else -> next.move.startSpeedPerMm * next.distance / aMove.distance
        }} ?: 0.0
        val maxJunctionSpToGetEndSpeedPerMm = (endSpeedPerMm.squared() + speeds.accelPerMm).sqrt()
        if (maxJunctionSpToGetEndSpeedPerMm < maxJunctionSpeedPerMm) {
            startSpeedPerMm = maxJunctionSpToGetEndSpeedPerMm
            return MoveSpeedStatus.END_SPEED_LIMITED
        }
        return MoveSpeedStatus.NOT_LIMITED
    }

    enum class MoveSpeedStatus { NOT_LIMITED, END_SPEED_LIMITED  }

    /** Forward pass, limit the start and end speeds to what is achievable with the given
     * initial speed and acceleration.
     **/
    fun calcSpeedsForwards() {
        startSpeedPerMm = actuatorMoves.minOfOrNull { aMove ->
            val prev = aMove.previous
            when {
                aMove.distance == 0.0 -> 0.0
                prev == null -> 0.0
                else -> prev.move.endSpeedPerMm * prev.distance / aMove.distance
            }} ?: 0.0
        endSpeedPerMm = endSpeedPerMm.coerceAtMost((startSpeedPerMm.squared() + speeds.accelPerMm).sqrt())
    }

    /** Calculate the move times using a accelerate, cruise and then decelerate profile. */
    fun planMove(startTime: MachineTime) {
        this.startTime = startTime
        val accel = speeds.accelPerMm
        cruiseSpeedPerMm = speeds.speedPerMm
            .coerceAtMost((startSpeedPerMm.squared() + accel).sqrt())
            .coerceAtMost((endSpeedPerMm.squared() + accel).sqrt())
        accelDistPerMm = (cruiseSpeedPerMm.squared() - startSpeedPerMm.squared()) * 0.5 / accel
        decelDistPerMm = (cruiseSpeedPerMm.squared() - endSpeedPerMm.squared()) * 0.5 / accel
        cruiseDistPerMm = 1.0 - accelDistPerMm - decelDistPerMm
        accelDuration = accelDistPerMm / ((startSpeedPerMm + cruiseSpeedPerMm) * 0.5)
        cruiseDuration = cruiseDistPerMm / cruiseSpeedPerMm
        decelDuration = decelDistPerMm / ((endSpeedPerMm + cruiseSpeedPerMm) * 0.5)
        this.endTime = startTime + accelDuration + cruiseDuration + decelDuration
    }

    fun writeMove() {
        for(aMove in actuatorMoves) {
            val distance = aMove.startPosition.distanceTo(aMove.endPosition)
            if (distance == 0.0) continue
            val invD = 1.0 / distance
            val direction = aMove.endPosition.zip(aMove.startPosition) { s, e -> (e - s) * invD }
            if (accelDuration > 0) {
                aMove.actuator.moveTo(
                    startTime,startTime + accelDuration,
                    startSpeedPerMm * distance,
                    cruiseSpeedPerMm * distance,
                    aMove.startPosition.moveBy(direction,accelDistPerMm))
            }
            if (cruiseDuration > 0) {
                aMove.actuator.moveTo(
                    startTime + accelDuration, endTime - decelDuration,
                    cruiseSpeedPerMm * distance,
                    cruiseSpeedPerMm * distance,
                    aMove.endPosition.moveBy(direction,-decelDistPerMm))
            }
            if (decelDuration > 0) {
                aMove.actuator.moveTo(
                    endTime - decelDuration, endTime,
                    cruiseSpeedPerMm * distance,
                    endSpeedPerMm * distance,
                    aMove.endPosition)
            }
        }
    }
}

