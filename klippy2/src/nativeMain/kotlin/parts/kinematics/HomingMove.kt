package parts.kinematics

import MachineRuntime
import getBestTriggerPosition
import io.github.oshai.kotlinlogging.KotlinLogging
import machine.getNextMoveTime
import machine.getNow
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
        actuator.initializePosition(getNow(), initialPosition)
        return home(homedPosition, homing)
    }

    suspend fun home(endstopPosition: Position, homing: Homing): HomeResult {
        logger.info { "Homing to $endstopPosition" }
        val initalPosition = actuator.commandedPosition
        val vector = initalPosition.vectorTo(endstopPosition)
        val direction = vector.direction()
        val initialEndPosition = initalPosition.moveBy(vector, 1.2)
        val homeResult = doHomingMove(initialEndPosition, homing.speed)
        actuator.initializePosition(getNow(), endstopPosition)
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
        actuator.initializePosition(getNow(), actuator.commandedPosition.moveBy(offset))
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

    suspend fun doHomingMove(targetPosition: Position, speed: Double): EndstopSync.State {
        val startTime = getNextMoveTime()
        val endTime = SimpleMotionPlanner(startTime, checkLimits = false).moveTo(
            KinMove2(
                actuator = actuator,
                position = targetPosition,
                speed = speed,
            )
        )
        val maybeAlreadyTriggered = session.start(startTime, timeoutTime = endTime + 1.0)
        if (maybeAlreadyTriggered != null) { return maybeAlreadyTriggered }
        val flushTime = endTime + 0.1
        actuator.generate(flushTime)
        runtime.flushMoves(flushTime)
        val result = session.waitAndFinalize()
        return result
    }

    companion object {
        fun getInitialPosition(homing: Homing, range: LinearRange) = when (homing.direction) {
                HomingDirection.INCREASING -> range.positionMin - (homing.endstopPosition - range.positionMin) * 0.2
                HomingDirection.DECREASING -> range.positionMax + (range.positionMax - homing.endstopPosition) * 0.2
            }
        }
}