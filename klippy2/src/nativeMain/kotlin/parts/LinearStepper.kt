package parts

import MachineTime
import config.StepperPins
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import machine.ConfigurationException
import machine.MachineBuilder
import machine.PartLifecycle
import mcu.StepperDriver
import mcu.connection.StepQueueImpl
import mcu.impl.GcWrapper
import parts.kinematics.Homing
import parts.kinematics.LinearAxis
import parts.kinematics.LinearAxisConfiguration
import parts.kinematics.LinearRange
import parts.kinematics.LinearSpeeds
import parts.kinematics.PositionType
import platform.linux.free

fun MachineBuilder.LinearStepper(
    name: String,
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
    stepsPerMM = stepsPerRotation * driver.microsteps / gearRatio / rotationDistance,
    configuration = LinearAxisConfiguration(
        speed = speed,
        range = range,
        homing = homing,
    ),
    driver,
    this,
).also { addPart(it) }

interface LinearStepper: LinearAxis {
    /** Commanded position */
    val driver: StepperDriver
}

/** Implementation */
@OptIn(ExperimentalForeignApi::class)
private class StepperImpl(
    override val name: String,
    pins: StepperPins,
    val stepsPerMM: Double,
    override val configuration: LinearAxisConfiguration,
    override val driver: StepperDriver,
    builder: MachineBuilder,
) : PartLifecycle, LinearStepper {
    val motor = builder.setupMcu(pins.mcu).addStepperMotor(pins, driver)
    val stepQueue = motor.stepQueue as StepQueueImpl
    val kinematics = GcWrapper(chelper.cartesian_stepper_alloc('x'.code.toByte())) {free(it)}
    val trapq = GcWrapper(chelper.trapq_alloc()) {chelper.trapq_free(it)}
    var _position: List<Double> = listOf(0.0)
    var _speed: Double = 0.0
    var _time: Double = 0.0
    override val size = 1
    override val positionTypes = listOf(PositionType.LINEAR)
    override var commandedPosition: List<Double>
        get() = _position
        set(value) { _position = value }

    init {
        driver.configureForStepper(stepsPerMM)
        chelper.itersolve_set_stepcompress(kinematics.ptr, stepQueue.stepcompress.ptr, stepsPerMM)
        chelper.itersolve_set_trapq(kinematics.ptr, trapq.ptr)
        chelper.itersolve_set_position(kinematics.ptr, _position[0], 0.0, 0.0)
    }

    override fun initializePosition(time: MachineTime, position: List<Double>) {
        require(position.size == 1)
        flush(time)
        if (_time > time) throw IllegalStateException("Time before last time")
        _position = position
        _speed = 0.0
        _time = time
        chelper.itersolve_set_position(kinematics.ptr, _position[0], 0.0, 0.0)
    }

    override fun checkMove(start: List<Double>, end: List<Double>): LinearSpeeds {
        //TODO: Check bounds.
        return configuration.speed ?: throw ConfigurationException("No speed configuration for steper $name")
    }

    override fun moveTo(endTime: MachineTime, endPosition: List<Double>, endSpeed: Double) {
        val move = chelper.move_alloc() ?: throw OutOfMemoryError()
        val distance = endPosition[0] - _position[0]
        val duration = endTime - _time
        if (duration <= 0.0) {
            if (distance == 0.0) return
            throw IllegalStateException("Nonzero move with zero duration")
        }
        move.pointed.print_time = _time
        move.pointed.move_t = duration
        move.pointed.start_v = _speed
        move.pointed.half_accel = (endSpeed - _speed) / duration * 0.5
        move.pointed.start_pos.x = _position[0]
        move.pointed.start_pos.y = 0.0
        move.pointed.start_pos.z = 0.0
        move.pointed.axes_r.x = 1.0
        move.pointed.axes_r.y = 0.0
        move.pointed.axes_r.z = 0.0
        chelper.trapq_add_move(trapq.ptr, move)
        _position = endPosition
        _speed = endSpeed
        _time = endTime
    }

    override fun flush(time: MachineTime) {
        chelper.itersolve_generate_steps(kinematics.ptr, time).let { ret ->
            if (ret != 0) throw RuntimeException("Internal error in stepcompress $ret")
        }
        chelper.trapq_finalize_moves(trapq.ptr, time, time)
    }
}