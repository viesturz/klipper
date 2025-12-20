package parts.kinematics

import EndstopSync
import EndstopSyncBuilder
import MachineTime
import config.StepperPins
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import MachineBuilder
import MachineRuntime
import PartLifecycle
import StepperDriver
import StepsToPosition
import io.github.oshai.kotlinlogging.KotlinLogging
import machine.MOVE_HISTORY_TIME
import machine.SCHEDULING_TIME
import machine.getNow
import mcu.connection.StepQueueImpl
import mcu.GcWrapper
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
    speed = speed,
    range = range,
    homing = homing,
    driver = driver,
    builder = this,
).also { addPart(it) }

interface LinearStepper: LinearRail {
    // To drive directly via stepQueue for external kinematics
    fun assignToKinematics(register: (stepDistance: Double, s: StepQueueImpl) -> Unit )
}

/** Implementation */
@OptIn(ExperimentalForeignApi::class)
private class StepperImpl(
    override val name: String,
    pins: StepperPins,
    val stepDistance: Double,
    override val speed: LinearSpeeds,
    override val range: LinearRange,
    override val homing: Homing?,
    val driver: StepperDriver,
    builder: MachineBuilder,
) : PartLifecycle, LinearStepper {
    override var commandedPosition: Double = 0.0
    override var commandedEndTime: MachineTime = 0.0
    override var railStatus = RailStatus(false, false)
    override lateinit var runtime: MachineRuntime

    val logger = KotlinLogging.logger(name)
    val motor = builder.setupMcu(pins.mcu).addStepperMotor(pins, driver)
    val stepQueue = motor.stepQueue as StepQueueImpl
    val kinematics = GcWrapper(chelper.cartesian_stepper_alloc('x'.code.toByte())) { free(it) }
    var internalKinematics = true
    val trapq = GcWrapper(chelper.trapq_alloc()) { chelper.trapq_free(it) }
    var stepsToPostion: StepsToPosition? = null

    override suspend fun onStart(runtime: MachineRuntime) {
        this.runtime = runtime
    }

    override suspend fun setPowered(time: MachineTime, value: Boolean) {
        if (value == railStatus.powered) return
        railStatus = railStatus.copy(powered = value, homed = if (!value) false else railStatus.homed)
        driver.setEnabled( time, value)
    }
    override fun setHomed(value: Boolean) {
        railStatus = railStatus.copy(homed = value)
    }

    init {
        chelper.itersolve_set_stepcompress(kinematics.ptr, stepQueue.stepcompress.ptr, stepDistance)
        chelper.itersolve_set_trapq(kinematics.ptr, trapq.ptr)
        chelper.itersolve_set_position(kinematics.ptr, 0.0, 0.0, 0.0)
    }

    override fun assignToKinematics(register: (stepDistance: Double, stepQueue: StepQueueImpl) -> Unit ) {
        internalKinematics = false
        register.invoke(stepDistance,stepQueue)
    }

    override fun setupTriggerSync(sync: EndstopSyncBuilder) {
        sync.addStepperMotor(motor)
    }

    override suspend fun initializePosition(time: MachineTime, position: Double, homed: Boolean) {
        logger.debug { "Initializing position $position at time $time" }
        resetPosition(position, time)
        stepsToPostion = StepsToPosition(
            referenceSteps = motor.queryPosition(),
            referencePosition = position,
            stepDistance = stepDistance,
        )
        railStatus = railStatus.copy(homed = homed)
    }

    override suspend fun updatePositionAfterTrigger(sync: EndstopSync) {
        val (triggerSteps, stopSteps) = motor.getTriggerPosition(sync)
        val stepsToPos = this.stepsToPostion
        require(stepsToPos != null)
        val triggerPosition = stepsToPos.toPosition(triggerSteps)
        val stopPosition = stepsToPos.toPosition(stopSteps)
        logger.debug { "updatePositionAfterTrigger, triggerSteps = $triggerSteps, stopSteps = $stopSteps" }
        logger.debug { "updatePositionAfterTrigger, triggerPosition=$triggerPosition, stopPosition=$stopPosition" }
        resetPosition(stopPosition, getNow())
        motor.clearQueuedSteps()
    }

    private fun resetPosition(position: Double, time: MachineTime) {
        commandedPosition = position
        commandedEndTime = getNow()
        if (internalKinematics) {
            chelper.trapq_set_position(trapq.ptr, commandedEndTime, commandedPosition, 0.0, 0.0)
            chelper.trapq_finalize_moves(trapq.ptr, commandedEndTime, commandedEndTime)
            chelper.itersolve_set_position(kinematics.ptr, commandedPosition, 0.0, 0.0)
            chelper.itersolve_generate_steps(kinematics.ptr, commandedEndTime) // Reset itersolve time
        }
    }

    override fun moveTo(
        startTime: MachineTime,
        endTime: MachineTime,
        startSpeed: Double,
        endSpeed: Double,
        endPosition: Double
    ) {
        logger.debug  { "moveTo t=$startTime -> $endTime, p=$commandedPosition -> $endPosition" }
        if (internalKinematics) throw IllegalStateException("moveTo: Stepper has external kinematics")
        val delta = endPosition - commandedPosition
        val direction = delta.sign
        val distance = delta.absoluteValue
        val duration = endTime - startTime
        if (duration <= 0.0) {
            if (distance < 1e-9) return
            throw IllegalStateException("Nonzero move with zero duration")
        }
        val move = chelper.move_alloc() ?: throw OutOfMemoryError()
        val accel = (endSpeed - startSpeed) / duration
        // Sanity check
        logger.debug { "move $startTime+$duration, Accel:$accel, Pos:$commandedPosition->$endPosition, speed:$startSpeed->$endSpeed" }
        val distanceFromSpeed = startSpeed * duration + accel * duration * duration * 0.5
        require((distanceFromSpeed - distance).absoluteValue < 0.001 ) { "Speeds and durations do not match: Position distance: $distance, speedDistance: $distanceFromSpeed." }
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
        logger.debug  { "generate at time $time" }
        if (internalKinematics) throw IllegalStateException("generate: Stepper has external kinematics")
        chelper.itersolve_generate_steps(kinematics.ptr, time).let { ret ->
            if (ret != 0) throw RuntimeException("Internal error in stepcompress $ret")
        }
        chelper.trapq_finalize_moves(trapq.ptr, time - SCHEDULING_TIME, time - MOVE_HISTORY_TIME)
    }
}