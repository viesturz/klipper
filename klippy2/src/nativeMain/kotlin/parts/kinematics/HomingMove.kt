package parts.kinematics

import MachineRuntime
import machine.getNextMoveTime
import machine.getNow
import parts.motionplanner.KinMove2
import parts.motionplanner.Position
import parts.motionplanner.SimpleMotionPlanner
import utils.direction
import utils.moveBy
import utils.vectorTo

class HomingMove(val session: ProbingSession, val actuator: MotionActuator, val runtime: MachineRuntime) {

    suspend fun home(endstopPosition: Position, homing: Homing): HomeResult {
        val initalPosition = actuator.commandedPosition
        val vector = initalPosition.vectorTo(endstopPosition)
        val direction = vector.direction()
        val initialEndPosition = initalPosition.moveBy(vector, 1.2)
        val homeResult = doHomingMove(initialEndPosition, homing.speed)
        if (homeResult !is EndstopSync.StateTriggered) {
            return HomeResult.FAIL
        }
        actuator.initializePosition(getNow(), endstopPosition)
        val retractedPosition = endstopPosition.moveBy(direction, -homing.retractDist)
        doRetract(retractedPosition, homing.speed)

        val secondEndPosition = endstopPosition.moveBy(direction, homing.retractDist * 2)
        val homingSamples = buildList {
            repeat(homing.attempts - 1) {
                val homeResult = doHomingMove(secondEndPosition, homing.secondSpeed)
                if (homeResult !is EndstopSync.StateTriggered) {
                    return HomeResult.FAIL
                }
                // TODO: query the actual motor position
                add(actuator.commandedPosition)
                actuator.initializePosition(getNow(), endstopPosition)
                doRetract(retractedPosition, homing.speed)
            }
        }
        // TODO check homing sample accuracy.
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
        val result = session.wait()
        session.allowMoves()
        return result
    }
}