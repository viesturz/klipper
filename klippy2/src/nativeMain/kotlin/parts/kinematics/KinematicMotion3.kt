package parts.kinematics

import EndstopSync
import EndstopSyncBuilder
import MachineTime
import chelper.cartesian_stepper_alloc
import chelper.trapq_alloc
import chelper.trapq_free
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import machine.MoveOutsideRangeException
import mcu.GcWrapper
import platform.linux.free
import utils.distanceTo

@OptIn(ExperimentalForeignApi::class)
class CartesianKinematics(val x: LinearStepper, val y: LinearStepper, val z: LinearStepper): KinematicMotion3(
    listOf(x, y, z),
    listOf({ GcWrapper(cartesian_stepper_alloc('x'.code.toByte())) { free(it) }},
        {GcWrapper(cartesian_stepper_alloc('y'.code.toByte())) { free(it) }},
            {GcWrapper(cartesian_stepper_alloc('z'.code.toByte())) { free(it) }}),
) {
    override fun computeMaxSpeeds(start: List<Double>, end: List<Double>): LinearSpeeds =
        x.speeds.intersection(y.speeds).intersection(z.speeds)

    override fun checkMoveInBounds(
        start: List<Double>,
        end: List<Double>
    ): MoveOutsideRangeException? {
        val xRange = x.range
        val yRange = y.range
        val zRange = z.range
        val xOutside = xRange.outsideRange(end[0])
        val yOutside = yRange.outsideRange(end[1])
        val zOutside = zRange.outsideRange(end[2])
        if (xOutside || yOutside || zOutside) {
            return MoveOutsideRangeException(
                "Move outside of range: X=${if (xOutside) "true" else "false"}, Y=${if (yOutside) "true" else "false"}, Z=${if (zOutside) "true" else "false"}"
            )
        }
        return null
    }

    override fun setupTriggerSync(sync: EndstopSyncBuilder) {
        x.setupTriggerSync(sync)
        y.setupTriggerSync(sync)
        z.setupTriggerSync(sync)
    }

    override suspend fun home(axis: List<Int>): HomeResult {
        TODO("Not yet implemented")
    }
}

@OptIn(ExperimentalForeignApi::class)
abstract class KinematicMotion3(
    val motors: List<LinearStepper>,
    kinProviders: List<() -> GcWrapper<chelper.stepper_kinematics>>,
): MotionActuator {
    val trapq = GcWrapper(trapq_alloc()) { trapq_free(it) }
    val kinematics: List<GcWrapper<chelper.stepper_kinematics>>
    override val size = 3
    override val positionTypes = listOf(MotionType.LINEAR, MotionType.LINEAR, MotionType.LINEAR)
    override var commandedPosition: List<Double> = listOf(0.0, 0.0, 0.0)
    override var commandedEndTime: MachineTime = 0.0
    override var axisStatus = mutableListOf(RailStatus.INITIAL, RailStatus.INITIAL, RailStatus.INITIAL)

    init {
        require(motors.size == 3)
        require(kinProviders.size == 3)
        kinematics = buildList {
            for (i in kinProviders.indices) {
                val motor = motors[i]
                motor.assignToKinematics {
                    val k = kinProviders[i].invoke()
                    add(k)
                    chelper.itersolve_set_trapq(k.ptr, trapq.ptr)
                    chelper.itersolve_set_position(k.ptr, 0.0,0.0,0.0)
                    return@assignToKinematics k
                }
            }
        }
        chelper.trapq_set_position(trapq.ptr, 0.0, 0.0,0.0,0.0)
    }

    override suspend fun initializePosition(time: MachineTime, position: List<Double>) {
        require(position.size == 3)
        generate(time)
        if (this.commandedEndTime > time) throw IllegalStateException("Time before last time")
        commandedPosition = position
        this.commandedEndTime = time
        for (kin in kinematics) {
            chelper.itersolve_set_position(kin.ptr, position[0], position[1], position[2])
        }
        chelper.trapq_set_position(trapq.ptr, time, position[0], position[1], position[2])
    }

    override suspend fun updatePositionAfterTrigger(sync: EndstopSync) {
        motors.forEach { it.updatePositionAfterTrigger(sync) }
        // TODO - update commanded position
        // TODO - update trapq and itersolve
    }

    override fun moveTo(
        startTime: MachineTime, endTime: MachineTime,
        startSpeed: Double, endSpeed: Double,
        endPosition: List<Double>
    ) {
        require(startTime >= this.commandedEndTime, { "startTime < _time" })
        val distance = endPosition.distanceTo(commandedPosition)
        val duration = endTime - startTime
        if (duration <= 0.0) {
            if (distance == 0.0) return
            throw IllegalStateException("Nonzero move with zero duration")
        }
        val invDist = 1.0 / distance
        val move = chelper.move_alloc() ?: throw OutOfMemoryError()
        move.pointed.apply {
            print_time = startTime
            move_t = duration
            start_v = startSpeed
            half_accel = (endSpeed - startSpeed) / duration * 0.5
            start_pos.x = commandedPosition[0]
            start_pos.y = commandedPosition[1]
            start_pos.z = commandedPosition[2]
            axes_r.x = (endPosition[0] - commandedPosition[0]) * invDist
            axes_r.y = (endPosition[1] - commandedPosition[1]) * invDist
            axes_r.z = (endPosition[2] - commandedPosition[2]) * invDist
        }
        chelper.trapq_add_move(trapq.ptr, move)
        commandedPosition = endPosition
        this.commandedEndTime = endTime
    }

    override fun generate(time: MachineTime) {
        for (kin in kinematics) {
            chelper.itersolve_generate_steps(kin.ptr, time).let { ret ->
                if (ret != 0) throw RuntimeException("Internal error in stepcompress $ret")
            }
        }
        chelper.trapq_finalize_moves(trapq.ptr, time, time)
    }
}