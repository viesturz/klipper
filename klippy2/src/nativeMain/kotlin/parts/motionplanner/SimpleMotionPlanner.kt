package parts.motionplanner

import MachineTime
import utils.distanceTo
import utils.magnitude
import utils.moveBy
import utils.squared
import utils.vectorTo
import kotlin.math.sqrt

/** A simple motion planner with all moves sequential and full stop after every move. */
class SimpleMotionPlanner(var startTime: Double, val checkLimits: Boolean = true) {
    fun moveTo(vararg moves: KinMove2): MachineTime  {
        require(moves.isNotEmpty())

        var totalDistanceSq = 0.0
        var cruiseSpeedPerMm: Double = Double.MAX_VALUE
        var accelPerMm: Double = Double.MAX_VALUE

        // Compute speed and accel
        for (m in moves) {
            val currentPosition = m.actuator.commandedPosition
            val targetPosition = m.position
            val distance = targetPosition.distanceTo(currentPosition)
            if (distance == 0.0) continue
            totalDistanceSq += distance.squared()

            val speeds = m.actuator.computeMaxSpeeds(currentPosition, targetPosition)
            if (checkLimits) m.actuator.checkMoveInBounds(currentPosition, targetPosition)?.let { throw it }
            val speed = speeds.speed.coerceAtMost(m.speed ?: Double.MAX_VALUE)
            cruiseSpeedPerMm = cruiseSpeedPerMm.coerceAtMost(speed / distance)
            accelPerMm = accelPerMm.coerceAtMost(speeds.accel / distance)
        }
        if (totalDistanceSq == 0.0) return startTime
        cruiseSpeedPerMm = cruiseSpeedPerMm.coerceAtMost(sqrt(2.0*0.5*accelPerMm))
        val accelDuration = cruiseSpeedPerMm/accelPerMm
        val accelDistancePerMm = accelDuration * accelDuration * accelPerMm * 0.5
        val cruiseDistancePerMm = 1-accelDistancePerMm*2
        val cruiseDuration = cruiseDistancePerMm / cruiseSpeedPerMm
        val cruiseStartTime = startTime + accelDuration
        val deccelStartTime = cruiseStartTime + cruiseDuration
        val endTime = deccelStartTime + accelDuration

        // Schedule the moves
        for (m in moves) {
            val startPosition = m.actuator.commandedPosition
            val endPosition = m.position
            val direction = startPosition.vectorTo(endPosition)
            val distance = direction.magnitude()
            if (distance == 0.0) {
                // Schedule noop move to update commanded time.
                m.actuator.moveTo(startTime, endTime, startSpeed = 0.0, endSpeed = 0.0, endPosition = endPosition)
                continue
            }

            val cruiseStartPosition = startPosition.moveBy(direction, accelDistancePerMm)
            val cruiseEndPosition = endPosition.moveBy(direction, -accelDistancePerMm)
            val cruiseSpeed = cruiseSpeedPerMm * distance
            // Accel
            m.actuator.moveTo(
                startTime,
                cruiseStartTime,
                startSpeed = 0.0,
                endSpeed = cruiseSpeed,
                endPosition = cruiseStartPosition,
            )
            // Cruise
            m.actuator.moveTo(
                cruiseStartTime,
                deccelStartTime,
                startSpeed = cruiseSpeed,
                endSpeed = cruiseSpeed,
                endPosition = cruiseEndPosition,
            )
            // Deccel
            m.actuator.moveTo(
                deccelStartTime,
                endTime,
                startSpeed = cruiseSpeed,
                endSpeed = 0.0,
                endPosition = endPosition)
        }
        startTime = endTime
        return endTime
    }
}
