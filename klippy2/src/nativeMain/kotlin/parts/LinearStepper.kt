package parts

import MachineTime
import config.StepperPins
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import MachineBuilder
import PartLifecycle
import StepperDriver
import chelper.cartesian_stepper_alloc
import chelper.stepper_kinematics
import chelper.trapq_alloc
import chelper.trapq_free
import machine.MoveOutsideRangeException
import mcu.connection.StepQueueImpl
import mcu.GcWrapper
import parts.kinematics.Homing
import parts.kinematics.LinearRail
import parts.kinematics.LinearRange
import parts.kinematics.LinearSpeeds
import parts.kinematics.RailStatus
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
    speed: LinearSpeeds = LinearSpeeds.UNLIMITED,
    range: LinearRange = LinearRange.UNLIMITED,
    homing: Homing? = null,
): LinearStepper = StepperImpl(
    name = name,
    pins = pins,
    stepsPerMm = stepsPerRotation * driver.microsteps / gearRatio / rotationDistance,
    speeds = speed,
    range = range,
    homing = homing,
    driver = driver,
    builder = this,
).also { addPart(it) }

interface LinearStepper: LinearRail {
    // To drive directly via stepQueue for external kinematics
    @OptIn(ExperimentalForeignApi::class)
    fun assignToKinematics(kinematicsProvider: () -> GcWrapper<chelper.stepper_kinematics>)
}

/** Implementation */
@OptIn(ExperimentalForeignApi::class)
private class StepperImpl(
    override val name: String,
    pins: StepperPins,
    val stepsPerMm: Double,
    override val speeds: LinearSpeeds,
    override val range: LinearRange,
    override val homing: Homing?,
    val driver: StepperDriver,
    builder: MachineBuilder,
) : PartLifecycle, LinearStepper {
    val motor = builder.setupMcu(pins.mcu).addStepperMotor(pins, driver)
    val stepQueue = motor.stepQueue as StepQueueImpl
    val kinematics = GcWrapper(cartesian_stepper_alloc('x'.code.toByte())) { free(it) }
    var externalKinematics: GcWrapper<chelper.stepper_kinematics>? = null
    val trapq = GcWrapper(trapq_alloc()) { trapq_free(it) }
    var _position: Double = 0.0
    var _time: Double = 0.0
    override var commandedPosition: Double
        get() = _position
        set(value) {
            _position = value
        }
    override var railStatus = RailStatus(false, false)

    override suspend fun setPowered(time: MachineTime, value: Boolean) {
        railStatus = railStatus.copy(powered = value)
        driver.enable( time, value)
    }

    init {
        driver.configureForStepper(stepsPerMm)
        chelper.itersolve_set_stepcompress(kinematics.ptr, stepQueue.stepcompress.ptr, stepsPerMm)
        chelper.itersolve_set_trapq(kinematics.ptr, trapq.ptr)
        chelper.itersolve_set_position(kinematics.ptr, _position, 0.0, 0.0)
    }

    override fun assignToKinematics(kinematicsProvider: () -> GcWrapper<stepper_kinematics>) {
        val kin = kinematicsProvider.invoke()
        externalKinematics = kin
        chelper.itersolve_set_stepcompress(
            kin.ptr,
            stepQueue.stepcompress.ptr,
            stepsPerMm,
        )
    }

    override fun checkMove(start: Double, end: Double): LinearSpeeds {
        if (range.outsideRange(end)) {
            throw MoveOutsideRangeException("move=${end} is outside the range $range")
        }
        return speeds
    }

    override fun initializePosition(time: MachineTime, position: Double, homed: Boolean) {
        if (externalKinematics != null) throw IllegalStateException("Stepper has external kinematics")
        generate(time)
        if (_time > time) throw IllegalStateException("Time before last time")
        _position = position
        _time = time
        chelper.itersolve_set_position(kinematics.ptr, _position, 0.0, 0.0)
        chelper.trapq_set_position(trapq.ptr, time, _position, 0.0, 0.0)
        railStatus = railStatus.copy(homed = homed)
    }

    override fun moveTo(
        startTime: MachineTime,
        endTime: MachineTime,
        startSpeed: Double,
        endSpeed: Double,
        endPosition: Double
    ) {
        if (externalKinematics != null) throw IllegalStateException("Stepper has external kinematics")
        val delta = endPosition - _position
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
            start_pos.x = _position
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

    override fun generate(time: MachineTime) {
        if (externalKinematics != null) throw IllegalStateException("Stepper has external kinematics")
        chelper.itersolve_generate_steps(kinematics.ptr, time).let { ret ->
            if (ret != 0) throw RuntimeException("Internal error in stepcompress $ret")
        }
        chelper.trapq_finalize_moves(trapq.ptr, time, time)
    }
}