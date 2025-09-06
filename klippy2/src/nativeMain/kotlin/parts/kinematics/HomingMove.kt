package parts.kinematics

import MachineRuntime
import getBestTriggerPosition
import io.github.oshai.kotlinlogging.KotlinLogging
import machine.getNextMoveTime
import machine.getNow
import moveRailsTo
import parts.motionplanner.KinMove2
import parts.motionplanner.Position
import parts.motionplanner.SimpleMotionPlanner
import utils.direction
import utils.moveBy
import utils.setValue
import utils.vectorTo

class HomingMove(val session: ProbingSession, val actuator: MotionActuator, val runtime: MachineRuntime) {
    val logger = KotlinLogging.logger("Homing move for $actuator")

    suspend fun homeOneAxis(index: Int, homing: Homing, range: LinearRange): HomeResult {
        val initialPosition = actuator.commandedPosition.setValue(index, getInitialPosition(homing, range))
        val homedPosition = actuator.commandedPosition.setValue(index, homing.endstopPosition)
        actuator.initializePosition(getNow(), initialPosition, false)
        return home(homedPosition, homing)
    }

    suspend fun home(endstopPosition: Position, homing: Homing): HomeResult {
        logger.info { "Homing to $endstopPosition" }
        val initalPosition = actuator.commandedPosition
        val vector = initalPosition.vectorTo(endstopPosition)
        val direction = vector.direction()
        val initialEndPosition = initalPosition.moveBy(vector, 1.2)
        val homeResult = doHomingMove(initialEndPosition, homing.speed)
        actuator.initializePosition(getNow(), endstopPosition, true)
        if (homeResult !is EndstopSync.StateTriggered) {
            logger.info { "Homing fail. State = $homeResult" }
            return HomeResult.FAIL
        }
        // The first homing move result is used just to establish an initial position, it's not counted towards homing probes.

        val retractedPosition = endstopPosition.moveBy(direction, -homing.retractDist)
        val secondEndPosition = endstopPosition.moveBy(direction, homing.retractDist * 2)
        doRetract(retractedPosition, homing.speed)
        val homingSamples = buildList {
            repeat(homing.samples) {
                val homeResult = doHomingMove(secondEndPosition, homing.secondSpeed)
                if (homeResult !is EndstopSync.StateTriggered) {
                    logger.info { "Homing fail. State = $homeResult" }
                    return HomeResult.FAIL
                }
                add(actuator.commandedPosition)
                doRetract(retractedPosition, homing.speed)
            }
        }
        // Adjust position so that bestTriggerPos matches endstopPosition.
        val (bestTriggerPos, maxError, stdDev) = getBestTriggerPosition(homingSamples, direction)
        logger.info { "Homing success. Best trigger position: $bestTriggerPos, max error: $maxError, std dev: $stdDev" }
        val offset = bestTriggerPos.vectorTo(endstopPosition)
        actuator.initializePosition(getNow(), actuator.commandedPosition.moveBy(offset), true)
        return HomeResult.SUCCESS
    }

    suspend fun doRetract(targetPosition: Position, speed: Double) {
        val endTime = SimpleMotionPlanner(getNextMoveTime(), checkLimits = false).moveTo(
            KinMove2(
                actuator = actuator,
                position = targetPosition,
                speed = speed,
            )
        ) + 0.1
        actuator.generate(endTime)
        runtime.flushMoves(endTime)
        runtime.reactor.waitUntil(endTime)
    }

    /** Retracts each rail individually, bringing them all to the same position. */
    suspend fun doRetractRails(rails: List<LinearRail>, targetPosition: Double, speed: Double) {
        val endTime = moveRailsTo(rails, targetPosition, speed)
        actuator.generate(endTime)
        runtime.flushMoves(endTime)
        runtime.reactor.waitUntil(endTime)
    }

    suspend fun doHomingMove(targetPosition: Position, speed: Double): EndstopSync.State {
        val startTime = getNextMoveTime()
        return session.probingMove(startTime) {
            val endTime = SimpleMotionPlanner(startTime, checkLimits = false).moveTo(
                KinMove2(
                    actuator = actuator,
                    position = targetPosition,
                    speed = speed,
                )
            )
            val flushTime = endTime + 0.1
            actuator.generate(flushTime)
            runtime.flushMoves(flushTime)
            return@probingMove flushTime
        }
    }

    suspend fun homeGantry(rails: List<LinearRail>): HomeResult {
        val homing = rails.first().homing ?: throw RuntimeException("Homing not configured")
        val combinedRange = rails.fold(LinearRange.UNLIMITED) { cur, next -> cur.intersection(next.range) }
        val retractDist = rails.fold(homing.retractDist) { cur, next -> cur.coerceAtLeast(next.homing!!.retractDist) }
        val initialPosition = getInitialPosition(homing, combinedRange)
        val direction = if (homing.direction == HomingDirection.INCREASING) 1.0 else -1.0
        val homedNearPosition = if (homing.direction == HomingDirection.INCREASING)
            rails.fold(homing.endstopPosition) { c, new -> c.coerceAtMost(new.homing!!.endstopPosition) }
        else
            rails.fold(homing.endstopPosition) { c, new -> c.coerceAtLeast(new.homing!!.endstopPosition) }
        val homedFarPosition = if (homing.direction == HomingDirection.INCREASING)
            rails.fold(homing.endstopPosition) { c, new -> c.coerceAtLeast(new.homing!!.endstopPosition) }
        else
            rails.fold(homing.endstopPosition) { c, new -> c.coerceAtMost(new.homing!!.endstopPosition) }

        rails.forEach { it.initializePosition(getNow(), initialPosition, false) }

        logger.info { "Homing Gantry to $homedNearPosition" }
        val initialEndPosition = initialPosition + (homedFarPosition - initialPosition) * 1.2
        val homeResult = doHomingMove(listOf(initialEndPosition), homing.speed)

        // Initialize the same position for all rails initially.
        rails.forEach { it.initializePosition(getNow(), homedNearPosition, true) }
        if (homeResult !is EndstopSync.StateTriggered) {
            logger.info { "Homing fail. State = $homeResult" }
            return HomeResult.FAIL
        }
        // The first homing move result is used just to establish an initial position, it's not counted towards homing probes.

        val retractedPosition = homedNearPosition - retractDist * direction
        val secondEndPosition = homedFarPosition  + retractDist * direction
        val homingSamples = buildList {
            repeat(homing.samples) { index ->
                doRetractRails(rails, retractedPosition, homing.speed)
                val homeResult = doHomingMove(listOf(secondEndPosition), homing.secondSpeed)
                if (homeResult !is EndstopSync.StateTriggered) {
                    logger.info { "Homing fail. State = $homeResult" }
                    return HomeResult.FAIL
                }
                add(rails.map { it.commandedPosition })
            }
        }
        // Adjust position so that bestTriggerPos matches individual endstopPosition.
        rails.forEachIndexed { index, rail ->
            val sample = if (homingSamples.isNotEmpty()) homingSamples.map { it[index] } else listOf(homedNearPosition)
            val endstopPos = rail.homing!!.endstopPosition
            val triggerPos = getBestTriggerPosition(sample).position
            val actualPos = triggerPos - homedNearPosition + endstopPos
            rail.initializePosition(getNow(), actualPos, true)
        }
        doRetractRails(rails, retractedPosition, homing.speed)
        logger.info { "Homing success." }
        return HomeResult.SUCCESS
    }

    companion object {
        fun getInitialPosition(homing: Homing, range: LinearRange) = when (homing.direction) {
                HomingDirection.INCREASING -> range.positionMin - (homing.endstopPosition - range.positionMin) * 0.2
                HomingDirection.DECREASING -> range.positionMax + (range.positionMax - homing.endstopPosition) * 0.2
            }
        }
}