package parts.kinematics

import MachineTime
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import machine.ConfigurationException
import mcu.connection.StepQueueImpl
import mcu.impl.GcWrapper
import parts.LinearStepper
import platform.linux.free
import utils.distanceTo

@OptIn(ExperimentalForeignApi::class)
private class KinematicMotion3(
    val motors: List<LinearStepper>,
    override val configuration: LinearAxisConfiguration,
) : LinearAxis {
    val kinematics = listOf(
        GcWrapper(chelper.cartesian_stepper_alloc('x'.code.toByte())) { free(it) },
        GcWrapper(chelper.cartesian_stepper_alloc('y'.code.toByte())) { free(it) },
        GcWrapper(chelper.cartesian_stepper_alloc('z'.code.toByte())) { free(it) })
    val trapq = GcWrapper(chelper.trapq_alloc()) { chelper.trapq_free(it) }
    var _position: List<Double> = listOf(0.0, 0.0, 0.0)
    var _time: Double = 0.0
    override val size = 3
    override val positionTypes = listOf(MotionType.LINEAR, MotionType.LINEAR, MotionType.LINEAR)
    override var commandedPosition: List<Double>
        get() = _position
        set(value) {
            _position = value
        }

    init {
        for (i in kinematics.indices) {
            val kin = kinematics[i]
            val motor = motors[i]
            chelper.itersolve_set_stepcompress(
                kin.ptr,
                (motor.stepQueue as StepQueueImpl).stepcompress.ptr,
                motor.stepsPerMm,
            )
            chelper.itersolve_set_trapq(kin.ptr, trapq.ptr)
            chelper.itersolve_set_position(kin.ptr, _position[0], _position[1], _position[2])
        }
    }

    override fun initializePosition(time: MachineTime, position: List<Double>) {
        require(position.size == 3)
        flush(time)
        if (_time > time) throw IllegalStateException("Time before last time")
        _position = position
        _time = time
        for (kin in kinematics) {
            chelper.itersolve_set_position(kin.ptr, _position[0], _position[1], _position[2])
        }
        chelper.trapq_set_position(trapq.ptr, time, _position[0], _position[1], _position[2])
    }

    override fun checkMove(start: List<Double>, end: List<Double>): LinearSpeeds {
        //TODO: Check bounds.
        return configuration.speed ?: throw ConfigurationException("No speed configuration")
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

    override fun flush(time: MachineTime) {
        for (kin in kinematics) {
            chelper.itersolve_generate_steps(kin.ptr, time).let { ret ->
                if (ret != 0) throw RuntimeException("Internal error in stepcompress $ret")
            }
        }
        chelper.trapq_finalize_moves(trapq.ptr, time, time)
    }
}