package parts

import MachineTime
import config.StepperPins
import kotlinx.coroutines.delay
import machine.CommandQueue
import machine.MachineBuilder
import machine.MachineRuntime
import machine.Planner
import machine.impl.PartLifecycle
import machine.impl.Reactor
import parts.drivers.StepperDriver
import parts.kinematics.Homing
import parts.kinematics.LinearAxis
import parts.kinematics.LinearAxisConfiguration
import parts.kinematics.LinearRange
import parts.kinematics.LinearSpeed
import kotlin.time.Duration.Companion.seconds

fun MachineBuilder.LinearStepper(
    name: String,
    pins: StepperPins,
    driver: StepperDriver,
    stepsPerRotation: Int = 200,
    rotationDistance: Double,
    gearRatio: Double = 1.0,
    speed: LinearSpeed? = null,
    range: LinearRange? = null,
    homing: Homing? = null,
): LinearStepper = StepperImpl(
    name,
    pins,
    stepsPerMM = stepsPerRotation * driver.microsteps / gearRatio / rotationDistance,
    driver,
    this,
).also { addPart(it) }

interface LinearStepper: LinearAxis {
    /** Commanded position */
    val driver: StepperDriver
    fun initializePosition(pos: Double)
    fun move(queue: CommandQueue, pos: Double, speed: Double, accel: Double)
}

/** Implementation */

private class StepperImpl(
    override val name: String,
    pins: StepperPins,
    val stepsPerMM: Double,
    override val driver: StepperDriver,
    builder: MachineBuilder,
) : LinearStepper, PartLifecycle, Planner<StepperMove> {
    private var _position = 0.0
    private var steps = 0
    private var zeroPosition = 0.0
    val motor = builder.setupMcu(pins.mcu).addStepperMotor(pins, driver)
    override val position: Double
        get() = _position

    init {
        driver.configureStepsPerMM(stepsPerMM)
    }

    override suspend fun onStart(runtime: MachineRuntime) {
        runtime.reactor.launch {
            delay(1.seconds)
            // Plan some moves
            val time = Reactor.now + 0.5
            driver.enable(time, true)
            motor.setPosition(time, 0L)
            for (i in 0..5) {
                val time = Reactor.now + 0.3
                var stepTime = time
                for (s in 1..100) {
                    val speed = s*10 // step/sec
                    stepTime = stepTime + 1.0/speed
                    motor.step(stepTime, 0)
                }
                for (s in 1..100) {
                    val speed = (101-s)*10 // step/sec
                    stepTime = stepTime + 1.0/speed
                    motor.step(stepTime, 0)
                }

                runtime.flushMoves(stepTime)
                delay((stepTime - time + 0.3).seconds)
            }
        }
    }

    override fun initializePosition(pos: Double) {
        _position = pos
        zeroPosition = pos
        steps = 0
    }

    override fun move(queue: CommandQueue, pos: Double, speed: Double, accel: Double) {
        val absSteps = ((zeroPosition - _position) * stepsPerMM).toInt()
        val moveSteps = absSteps - steps
        steps = absSteps
        _position = pos
        queue.addPlanned(this, StepperMove(moveSteps, speed, accel))
    }

    override fun tryPlan(
        startTime: MachineTime,
        cmd: StepperMove,
        followupCommands: List<StepperMove>,
        force: Boolean
    ): MachineTime {
        // motor.move(startTime, cmd.steps, 100_000, 0)
        return startTime + cmd.steps / cmd.speed
    }

    override val configuration: LinearAxisConfiguration
        get() = TODO("Not yet implemented")
}

data class StepperMove(val steps: Int, val speed: Double, val accel: Double)
