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
import io.github.oshai.kotlinlogging.KotlinLogging
import machine.MoveOutsideRangeException
import mcu.connection.StepQueueImpl
import mcu.GcWrapper
import parts.kinematics.Homing
import parts.kinematics.HomingMove
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
    stepDistance = rotationDistance * gearRatio / stepsPerRotation / driver.microsteps,
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
    val stepDistance: Double,
    override val speeds: LinearSpeeds,
    override val range: LinearRange,
    override val homing: Homing?,
    val driver: StepperDriver,
    builder: MachineBuilder,
) : PartLifecycle, LinearStepper {
    val logger = KotlinLogging.logger(name)
    val motor = builder.setupMcu(pins.mcu).addStepperMotor(pins, driver)
    val stepQueue = motor.stepQueue as StepQueueImpl
    val kinematics = GcWrapper(cartesian_stepper_alloc('x'.code.toByte())) { free(it) }
    var externalKinematics: GcWrapper<chelper.stepper_kinematics>? = null
    val trapq = GcWrapper(trapq_alloc()) { trapq_free(it) }
    override var commandedPosition: Double = 0.0
    override var commandedEndTime: MachineTime = 0.0
    override var railStatus = RailStatus(false, false)

    override suspend fun setPowered(time: MachineTime, value: Boolean) {
        railStatus = railStatus.copy(powered = value, homed = if (!value) false else railStatus.homed)
        driver.setEnabled( time, value)
    }
    override fun setHomed(value: Boolean) {
        railStatus = railStatus.copy(homed = value)
    }

    init {
        chelper.itersolve_set_stepcompress(kinematics.ptr, stepQueue.stepcompress.ptr, stepDistance)
        chelper.itersolve_set_trapq(kinematics.ptr, trapq.ptr)
        chelper.itersolve_set_position(kinematics.ptr, commandedPosition, 0.0, 0.0)
    }

    override fun assignToKinematics(kinematicsProvider: () -> GcWrapper<stepper_kinematics>) {
        val kin = kinematicsProvider.invoke()
        externalKinematics = kin
        chelper.itersolve_set_stepcompress(
            kin.ptr,
            stepQueue.stepcompress.ptr,
            stepDistance,
        )
    }

    override fun setupHomingMove(homingMove: HomingMove) {
        homingMove.addStepper(motor)
    }

    override fun initializePosition(time: MachineTime, position: Double, homed: Boolean) {
        logger.info { "Initializing position $position at time $time" }
        if (externalKinematics != null) throw IllegalStateException("Stepper has external kinematics")
        generate(time)
        if (commandedEndTime > time) throw IllegalStateException("Time before last time")
        commandedPosition = position
        commandedEndTime = time
        chelper.itersolve_set_position(kinematics.ptr, commandedPosition, 0.0, 0.0)
        chelper.trapq_set_position(trapq.ptr, time, commandedPosition, 0.0, 0.0)
        railStatus = railStatus.copy(homed = homed)
    }

    override fun moveTo(
        startTime: MachineTime,
        endTime: MachineTime,
        startSpeed: Double,
        endSpeed: Double,
        endPosition: Double
    ) {
        logger.info  { "moveTo $endPosition at time $endTime" }
        if (externalKinematics != null) throw IllegalStateException("Stepper has external kinematics")
        val delta = endPosition - commandedPosition
        val direction = delta.sign
        val distance = delta.absoluteValue
        val duration = endTime - startTime
        if (duration <= 0.0) {
            if (distance == 0.0) return
            throw IllegalStateException("Nonzero move with zero duration")
        }
        val move = chelper.move_alloc() ?: throw OutOfMemoryError()
        val accel = (endSpeed - startSpeed) / duration
        // Sanity check
        val distanceFromSpeed = startSpeed * duration + accel * duration * duration * 0.5
        require((distanceFromSpeed - distance).absoluteValue < 0.001 ) { "Speeds and durations do not match: Position distanece: $distance, speedDistance: $distanceFromSpeed." }
        move.pointed.apply {
            print_time = startTime
            move_t = duration
            start_v = startSpeed
            half_accel = accel * 0.5
            start_pos.x = commandedPosition
            start_pos.y = 0.0
            start_pos.z = 0.0
            axes_r.x = direction
            axes_r.y = 0.0
            axes_r.z = 0.0
        }
        chelper.trapq_add_move(trapq.ptr, move)
        commandedPosition = endPosition
        commandedEndTime = endTime
    }

    override fun generate(time: MachineTime) {
        logger.info  { "generate at time $time" }
        if (externalKinematics != null) throw IllegalStateException("Stepper has external kinematics")
        chelper.itersolve_generate_steps(kinematics.ptr, time).let { ret ->
            if (ret != 0) throw RuntimeException("Internal error in stepcompress $ret")
        }
        chelper.trapq_finalize_moves(trapq.ptr, time, time)
    }
}