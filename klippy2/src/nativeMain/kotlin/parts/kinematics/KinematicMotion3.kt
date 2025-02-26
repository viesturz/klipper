package parts.kinematics

import MachineTime
import chelper.cartesian_stepper_alloc
import chelper.trapq_alloc
import chelper.trapq_free
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import mcu.GcWrapper
import parts.LinearStepper
import platform.linux.free
import utils.distanceTo

@OptIn(ExperimentalForeignApi::class)
class CartesianKinematics(val x: LinearStepper, val y: LinearStepper, val z: LinearStepper): KinematicMotion3(
    listOf(x, y, z),
    listOf({ GcWrapper(cartesian_stepper_alloc('x'.code.toByte())) { free(it) }},
        {GcWrapper(cartesian_stepper_alloc('y'.code.toByte())) { free(it) }},
            {GcWrapper(cartesian_stepper_alloc('z'.code.toByte())) { free(it) }}),
) {
    override fun checkMove(start: List<Double>, end: List<Double>): LinearSpeeds {
        // TODO: consider the move
        return x.speeds.intersection(y.speeds).intersection(z.speeds)
    }
}

@OptIn(ExperimentalForeignApi::class)
abstract class KinematicMotion3(
    val motors: List<LinearStepper>,
    kinProviders: List<() -> GcWrapper<chelper.stepper_kinematics>>,
): MotionActuator {
    val trapq = GcWrapper(trapq_alloc()) { trapq_free(it) }
    var _position: List<Double> = listOf(0.0, 0.0, 0.0)
    var _time: Double = 0.0
    val kinematics: List<GcWrapper<chelper.stepper_kinematics>>
    override val size = 3
    override val positionTypes = listOf(MotionType.LINEAR, MotionType.LINEAR, MotionType.LINEAR)
    override var commandedPosition: List<Double>
        get() = _position
        set(value) {
            _position = value
        }

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
                    chelper.itersolve_set_position(k.ptr, _position[0], _position[1], _position[2])
                    return@assignToKinematics k
                }
            }
        }
        chelper.trapq_set_position(trapq.ptr, 0.0, _position[0], _position[1], _position[2])
    }

    override fun initializePosition(time: MachineTime, position: List<Double>) {
        require(position.size == 3)
        generate(time)
        if (_time > time) throw IllegalStateException("Time before last time")
        _position = position
        _time = time
        for (kin in kinematics) {
            chelper.itersolve_set_position(kin.ptr, _position[0], _position[1], _position[2])
        }
        chelper.trapq_set_position(trapq.ptr, time, _position[0], _position[1], _position[2])
    }

    override fun moveTo(
        startTime: MachineTime, endTime: MachineTime,
        startSpeed: Double, endSpeed: Double,
        endPosition: List<Double>
    ) {
        require(startTime >= _time, { "startTime < _time" })
        val distance = endPosition.distanceTo(_position)
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
            start_pos.x = _position[0]
            start_pos.y = _position[1]
            start_pos.z = _position[2]
            axes_r.x = (endPosition[0] - _position[0]) * invDist
            axes_r.y = (endPosition[1] - _position[1]) * invDist
            axes_r.z = (endPosition[2] - _position[2]) * invDist
        }
        chelper.trapq_add_move(trapq.ptr, move)
        _position = endPosition
        _time = endTime
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