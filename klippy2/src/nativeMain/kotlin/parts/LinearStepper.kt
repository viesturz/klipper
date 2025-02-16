package parts

import MachineTime
import config.StepperPins
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import machine.ConfigurationException
import MachineBuilder
import PartLifecycle
import StepperDriver
import chelper.cartesian_stepper_alloc
import chelper.trapq_alloc
import chelper.trapq_free
import mcu.connection.StepQueueImpl
import mcu.GcWrapper
import parts.kinematics.Homing
import parts.kinematics.LinearAxis
import parts.kinematics.LinearAxisConfiguration
import parts.kinematics.LinearRange
import parts.kinematics.LinearSpeeds
import parts.kinematics.MotionType
import platform.linux.free
import kotlin.math.absoluteValue
import kotlin.math.sign

fun MachineBuilder.LinearStepper(
    name: String = defaultName("LinearStepper"),
    pins: StepperPins,
    driver: StepperDriver,
    stepsPerRotation: Int = 200,
    rotationDistance: Double,
    gearRatio: Double = 1.0,
    speed: LinearSpeeds? = null,
    range: LinearRange? = null,
    homing: Homing? = null,
): LinearStepper = StepperImpl(
    name,
    pins,
    stepsPerMm = stepsPerRotation * driver.microsteps / gearRatio / rotationDistance,
    configuration = LinearAxisConfiguration(
        speed = speed,
        range = range,
        homing = homing,
    ),
    driver,
    this,
).also { addPart(it) }

interface LinearStepper: LinearAxis {
    val driver: StepperDriver
    val stepsPerMm: Double
    val stepQueue: StepQueueImpl
}

/** Implementation */
@OptIn(ExperimentalForeignApi::class)
private class StepperImpl(
    override val name: String,
    pins: StepperPins,
    override val stepsPerMm: Double,
    override val configuration: LinearAxisConfiguration,
    override val driver: StepperDriver,
    builder: MachineBuilder,
) : PartLifecycle, LinearStepper {
    val motor = builder.setupMcu(pins.mcu).addStepperMotor(pins, driver)
    override val stepQueue = motor.stepQueue as StepQueueImpl
    val kinematics = GcWrapper(cartesian_stepper_alloc('x'.code.toByte())) { free(it) }
    val trapq = GcWrapper(trapq_alloc()) { trapq_free(it) }
    var _position: List<Double> = listOf(0.0)
    var _time: Double = 0.0
    override val size = 1
    override val positionTypes = listOf(MotionType.LINEAR)
    override var commandedPosition: List<Double>
        get() = _position
        set(value) {
            _position = value
        }

    init {
        driver.configureForStepper(stepsPerMm)
        chelper.itersolve_set_stepcompress(kinematics.ptr, stepQueue.stepcompress.ptr, stepsPerMm)
        chelper.itersolve_set_trapq(kinematics.ptr, trapq.ptr)
        chelper.itersolve_set_position(kinematics.ptr, _position[0], 0.0, 0.0)
    }

    override fun initializePosition(time: MachineTime, position: List<Double>) {
        require(position.size == 1)
        flush(time)
        if (_time > time) throw IllegalStateException("Time before last time")
        _position = position
        _time = time
        chelper.itersolve_set_position(kinematics.ptr, _position[0], 0.0, 0.0)
        chelper.trapq_set_position(trapq.ptr, time, _position[0], 0.0, 0.0)
    }

    override fun checkMove(start: List<Double>, end: List<Double>): LinearSpeeds {
        //TODO: Check bounds.
        return configuration.speed
            ?: throw ConfigurationException("No speed configuration for steper $name")
    }

    override fun moveTo(
        startTime: MachineTime,
        endTime: MachineTime,
        startSpeed: Double,
        endSpeed: Double,
        endPosition: List<Double>
    ) {
        val delta = endPosition[0] - _position[0]
        val direction = delta.sign
        val distance = delta.absoluteValue
        val duration = endTime - startTime
        if (duration <= 0.0) {
            if (distance == 0.0) return
            throw IllegalStateException("Nonzero move with zero duration")
        }
        val move = chelper.move_alloc() ?: throw OutOfMemoryError()
        move.pointed.apply {
            print_time = startTime
            move_t = duration
            start_v = startSpeed
            half_accel = (endSpeed - startSpeed) / duration * 0.5
            start_pos.x = _position[0]
            start_pos.y = 0.0
            start_pos.z = 0.0
            axes_r.x = direction
            axes_r.y = 0.0
            axes_r.z = 0.0
        }
        chelper.trapq_add_move(trapq.ptr, move)
        _position = endPosition
        _time = endTime
    }

    override fun flush(time: MachineTime) {
        chelper.itersolve_generate_steps(kinematics.ptr, time).let { ret ->
            if (ret != 0) throw RuntimeException("Internal error in stepcompress $ret")
        }
        chelper.trapq_finalize_moves(trapq.ptr, time, time)
    }
}