package parts.kinematics

import MachineTime
import chelper.cartesian_stepper_alloc
import chelper.trapq_alloc
import chelper.trapq_free
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import machine.MOVE_HISTORY_TIME
import machine.MoveOutsideRangeException
import machine.SCHEDULING_TIME
import machine.getNextMoveTime
import machine.getNow
import mcu.GcWrapper
import parts.motionplanner.Position
import platform.linux.free
import utils.distanceTo

@OptIn(ExperimentalForeignApi::class)
class CartesianKinematics(val x: LinearStepper, val y: LinearStepper, val z: LinearStepper): ItersolveMotion3(
    listOf(x, y, z),
    listOf({ GcWrapper(cartesian_stepper_alloc('x'.code.toByte())) { free(it) }},
        {GcWrapper(cartesian_stepper_alloc('y'.code.toByte())) { free(it) }},
            {GcWrapper(cartesian_stepper_alloc('z'.code.toByte())) { free(it) }}),
) {
    override fun positionToRails(position: Position) = position
    override fun railsToPosition() = motors.map { it.commandedPosition }

    override fun computeMaxSpeeds(start: Position, end: Position): LinearSpeeds {
        val distance = end.distanceTo(start)
        var speed = LinearSpeeds.UNLIMITED
        val xDelta = end[0] - start[0]
        if (xDelta != 0.0) speed = speed.intersection(x.speed * (xDelta / distance))
        val yDelta = end[1] - start[1]
        if (yDelta != 0.0) speed = speed.intersection(y.speed * (yDelta / distance))
        val zDelta = end[2] - start[2]
        if (zDelta != 0.0) speed = speed.intersection(z.speed * (zDelta / distance))
        return speed
    }

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

    override suspend fun home(axis: List<Int>): HomeResult {
        if (axis.isEmpty()) return HomeResult.SUCCESS
        val startTime = getNextMoveTime()
        x.setPowered(startTime, true)
        y.setPowered(startTime, true)
        z.setPowered(startTime, true)
        for (index in axis) {
            val rail = when (index) {
                0 -> x
                1 -> y
                2 -> z
                else -> throw IllegalArgumentException("Invalid axis index $index")
            }
            val homing = rail.homing ?: throw IllegalStateException("Homing not configured for axis index $index")
            makeProbingSession {
                addRail(x, this@CartesianKinematics)
                addRail(y, this@CartesianKinematics)
                addRail(z, this@CartesianKinematics)
                addTrigger(homing)
            }.use { session ->
                val homingMove = HomingMove(session, this, rail.runtime)
                val result = homingMove.homeOneAxis(index, homing, rail.range)
                if (result == HomeResult.SUCCESS) {
                    rail.setHomed(true)
                }
                return result
            }
        }
        return HomeResult.SUCCESS
    }
}

@OptIn(ExperimentalForeignApi::class)
abstract class ItersolveMotion3(
    val motors: List<LinearStepper>,
    kinProviders: List<() -> GcWrapper<chelper.stepper_kinematics>>,
): MotionActuator {
    val trapq = GcWrapper(trapq_alloc()) { trapq_free(it) }
    val kinematics: List<GcWrapper<chelper.stepper_kinematics>>
    override val size = 3
    override val positionTypes = listOf(MotionType.LINEAR, MotionType.LINEAR, MotionType.LINEAR)
    override var commandedPosition: List<Double> = listOf(0.0, 0.0, 0.0)
    override var commandedEndTime: MachineTime = 0.0
    override var axisStatus = listOf(RailStatus.INITIAL, RailStatus.INITIAL, RailStatus.INITIAL)

    init {
        require(motors.size == 3)
        require(kinProviders.size == 3)
        kinematics = buildList {
            for (i in kinProviders.indices) {
                val motor = motors[i]
                motor.assignToKinematics { stepDistance, stepQueue ->
                    val k = kinProviders[i].invoke()
                    add(k)
                    chelper.itersolve_set_trapq(k.ptr, trapq.ptr)
                    chelper.itersolve_set_position(k.ptr, 0.0,0.0,0.0)
                    chelper.itersolve_set_stepcompress(
                        k.ptr,
                        stepQueue.stepcompress.ptr,
                        stepDistance,
                    )
                }
            }
        }
        chelper.trapq_set_position(trapq.ptr, 0.0, 0.0,0.0,0.0)
    }

    override suspend fun initializePosition(time: MachineTime, position: List<Double>, homed: Boolean) {
        require(position.size == 3)
        generate(time)
        if (this.commandedEndTime > time) throw IllegalStateException("Time before last time")
        commandedPosition = position
        this.commandedEndTime = time
        for (kin in kinematics) {
            chelper.itersolve_generate_steps(kin.ptr, time) // Reset itersolve time
            chelper.itersolve_set_position(kin.ptr, position[0], position[1], position[2])
        }
        chelper.trapq_set_position(trapq.ptr, time, position[0], position[1], position[2])
        chelper.trapq_finalize_moves(trapq.ptr, time, time)
        val railsPosition = positionToRails(position)
        motors.forEachIndexed { index, motor ->
            motor.initializePosition(time, railsPosition[index], homed)
        }
        axisStatus = axisStatus.map { it.copy(homed = homed) }
    }

    abstract fun positionToRails(position: Position): Position
    abstract fun railsToPosition(): Position

    override suspend fun updatePositionAfterTrigger() {
        // Rails already updated by trigger.
        val pos = railsToPosition()
        val time = getNow()
        commandedPosition = pos
        commandedEndTime = time
        for (kin in kinematics) {
            chelper.itersolve_generate_steps(kin.ptr, time) // Reset itersolve time
            chelper.itersolve_set_position(kin.ptr, pos[0], pos[1], pos[2])
        }
        chelper.trapq_set_position(trapq.ptr, time, pos[0], pos[1], pos[2])
        chelper.trapq_finalize_moves(trapq.ptr, time,time)
    }

    override fun moveTo(
        startTime: MachineTime, endTime: MachineTime,
        startSpeed: Double, endSpeed: Double,
        endPosition: Position
    ) {
        require(endPosition.size == 3)
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
        chelper.trapq_finalize_moves(trapq.ptr, time - SCHEDULING_TIME, time - MOVE_HISTORY_TIME)
    }
}