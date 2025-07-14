package parts.kinematics

import MachineTime
import machine.CommandQueue
import MachineBuilder
import MachineDuration

typealias Position = List<Double>

fun MachineBuilder.MotionPlanner(block: MotionPlannerConfig.()-> Unit): MotionPlanner {
    val config = MotionPlannerConfig()
    block(config)
    return MotionPlannerImpl(config).also{addPart(it)}
}

class MotionPlannerConfig {
    val mapping = HashMap<String, MotionActuator>()
    fun axis(name: Char, motion: MotionActuator) {
        mapping[name.toString()] = motion
    }
    fun axis(name: String, value: MotionActuator) {
        mapping[name] = value
    }
    fun axis(name: Char, value: LinearRail) {
//        mapping[name.toString()] = LinearRailActuator(value)
    }
}

enum class MotionType {
    OTHER,
    LINEAR,
    ROTATION,
}

interface MotionPlanner {
    /** Currently configured axis, uppercase letters. */
    val axis: String
    fun axisPosition(axis: Char): Double

    /**
     * Dynamically reconfigure axis-to-letter assignment.
     * This is just a mapping change, the move planning is not affected.
     */
    fun configureAxis(vararg axesMap: Pair<String, MotionActuator>)
    /** Set position for some or all axes.
     *
     * Any outstanding moves on the affected axis will be finalized to a full stop.
     */
    fun setPosition(axes: String, position: Position)
    /**
     * Queue a move.
     *
     * A move consists of one or more independent moves synchronized together.
     * With each submove being a straight line in the respective subspace.
     *
     * The requested speed and acceleration are maximum values. The actual achieved speeds
     * are solely at the discretion of the motion planner.
     *
     * The motion planner will take into account previous and next moves to ensure that
     * movements of individual axis are transitioning smoothly and do not exceed
     * the specified speed, acceleration and junction limits.
     *
     * The queue time advances to the move completion time accordingly.
     * The per-axis next move time for involved axes is advanced as well.
     * But NOT the other axis, even if they are linked kinematically.
     *
     * For example when issuing:
     *  - move(queue1, "X",..)
     *  - move(queue2, "Y",..)
     *  the two moves will be allowed to overlap, even if X and Y axis are kinematically linked.
     *  TODO: this needs to be implemented, currently all moves are executed sequentially.
     * */
    fun move(queue: CommandQueue, vararg moves: KinMove)

    suspend fun home(axes: String)

//    fun addMove(vararg moves: KinMove2): QueuedMove
//
//    fun addMoveLate(vararg moves: KinMove2): QueuedMove
//
//    /** Returns number of moves generated. */
//    fun generateMoves(): Int
//
//    /** Flushes all moves and advances next move start time to at least time. */
//    fun flush(actuator: MotionActuator, time: MachineTime): Int

}

interface QueuedMove {
    val startTime: MachineTime
    val endTime: MachineTime
    val minDuration: MachineDuration
}

/** A move where all the axis are kinematically linked and orthogonal to each other.
 *  Which means that the speed will be distributed and junction speeds estimated jointly. */
data class KinMove(val axis: String, val position: Position, var speed: Double?)

data class KinMove2(val actuator: MotionActuator, val position: Position, var speed: Double?)
