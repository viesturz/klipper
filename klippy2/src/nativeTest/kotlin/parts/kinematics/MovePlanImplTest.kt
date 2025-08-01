package parts.kinematics

import parts.motionplanner.MovePlan
import parts.motionplanner.MovePlanActuator
import parts.motionplanner.MoveSpeeds
import kotlin.test.Test

class MovePlanImplTest {
    val speeds1 = LinearSpeeds(accel = 1.0, speed = 1.0, squareCornerVelocity = 5.0)
    val actuator = MotionActuatorFake(size = 1)

    @Test
    fun testSingle() {
        val actuators = ArrayList<MovePlanActuator>()
        val kinMoves = ArrayList<List<MovePlanActuator>>()
        val plan = MovePlan(actuators, kinMoves)
        val planActuator = MovePlanActuator(
            move = plan,
            actuator = actuator,
            startPosition = listOf(0.0),
            endPosition = listOf(2.0),
            distance = 2.0,
            speeds = MoveSpeeds.from(speeds1, distance = 2.0),
        )
        actuators.add(planActuator)
        kinMoves.add(actuators)

        plan.calcJunctionSpeed()
        plan.calcSpeedsBackwards()

        assertEquals(2.0, plan.minDuration, precision = 0.0, "maxJunctionSpeedPerMm")
        assertEquals(0.0, plan.maxJunctionSpeedPerMm, precision = 0.0, "maxJunctionSpeedPerMm")
        assertEquals(0.0, plan.endSpeedPerMm, precision = 0.0, "endSpeedPerMm")
        assertEquals(0.0, plan.startSpeedPerMm, precision = 0.0, "startSpeedPerMm")

        plan.calcSpeedsForwards()
        plan.planMove(0.0)

        assertEquals(0.5, plan.cruiseSpeedPerMm, precision = 0.0, "cruiseSpeedPerMm")
        assertEquals(1.0, plan.accelDuration, precision = 0.0, "accelDuration")
        assertEquals(1.0, plan.decelDuration, precision = 0.0, "decelDuration")
        assertEquals(1.0, plan.cruiseDuration, precision = 0.0, "cruiseDuration")

        assertEquals(0.25, plan.accelDistPerMm, precision = 0.0, "accelDist")
        assertEquals(0.25, plan.decelDistPerMm, precision = 0.0, "decelDist")
        assertEquals(0.5, plan.cruiseDistPerMm, precision = 0.0, "cruiseDist")
        assertEquals(0.0, plan.startTime, precision = 0.0, "startTime")
        assertEquals(3.0, plan.endTime, precision = 0.0, "endTime")
    }

    @Test
    fun testLinked() {
        val actuators = ArrayList<MovePlanActuator>()
        val kinMoves = ArrayList<List<MovePlanActuator>>()
        val plan = MovePlan(actuators, kinMoves)
        val planActuator = MovePlanActuator(
            move = plan,
            actuator = actuator,
            startPosition = listOf(0.0),
            endPosition = listOf(2.0),
            distance = 2.0,
            speeds = MoveSpeeds.from(speeds1, distance = 2.0),
        )
        actuators.add(planActuator)
        kinMoves.add(actuators)

        val actuatorsPrev = ArrayList<MovePlanActuator>()
        val kinMovesPrev = ArrayList<List<MovePlanActuator>>()
        val planPrev = MovePlan(actuators, kinMoves)
        actuatorsPrev.add(
            MovePlanActuator(
                move = planPrev,
                actuator = actuator,
                startPosition = listOf(-2.0),
                endPosition = listOf(0.0),
                distance = 2.0,
                speeds = MoveSpeeds.from(speeds1, distance = 2.0),
            )
        )
        kinMovesPrev.add(actuatorsPrev)
        planActuator.previous = actuatorsPrev[0]

        plan.calcJunctionSpeed()
        plan.calcSpeedsBackwards()

        assertEquals(0.5, plan.maxJunctionSpeedPerMm, precision = 0.0, "maxJunctionSpeedPerMm")
        assertEquals(0.0, plan.endSpeedPerMm, precision = 0.0, "endSpeedPerMm")
        assertEquals(0.5, plan.startSpeedPerMm, precision = 0.0, "startSpeedPerMm")

        plan.calcSpeedsForwards()
        assertEquals(0.0, plan.endSpeedPerMm, precision = 0.0, "endSpeedPerMm")
        assertEquals(0.0, plan.startSpeedPerMm, precision = 0.0, "startSpeedPerMm")
    }
}