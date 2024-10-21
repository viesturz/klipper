package parts.kinematics

import MachineTime
import machine.CommandQueue
import machine.MachineBuilder

typealias Position = List<Double>

interface MotionPlanner {
    /** Currently configured axis, uppercase letters. */
    val axis: String
    fun axisPosition(axis: Char): Double
    /** Initialize position for some or all axis.
     *
     * Any outstanding moves on the affected axis will be finalized to a full stop.
     */
    fun initializePosition(axis: String, position: Position)
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
     *  - move(queue1, "x",..)
     *  - move(queue2, "y",..)
     *  the two moves will be allowed to overlap, even if X and Y axis are kinematically linked.
     *  TODO: this needs to be implemented, currently all moves are executed sequentially.
     * */
    fun move(queue: CommandQueue, vararg moves: KinMove)
    /**
     * Dynamically reconfigure axis-to-letter assignment.
     * This is just a mapping change, the move planning is not affected.
     */
    fun configureAxis(vararg axisMap: Pair<String, MotionActuator>)
}

/** A move where all the axis are kinematically linked and orthogonal to each other.
 *  Which means that the speed will be distributed and junction speeds estimated jointly. */
data class KinMove(val axis: String, val position: Position, var speed: Double?)

enum class MotionType {
    OTHER,
    LINEAR,
    ROTATION,
}

/** A motion actuator.
 * PositionT can be either Double or DoubleArray. */
interface MotionActuator {
    // Number of coordinates
    val size: Int
    val positionTypes: List<MotionType>
    var commandedPosition: List<Double>

    /** Check move validity and return speed restrictions for the move. */
    fun checkMove(start: List<Double>, end: List<Double>): LinearSpeeds

    /** Sets a homed position for the actuator. Should not perform any moves. */
    fun initializePosition(time: MachineTime, position: List<Double>)
    /* A constant-acceleration move to a new position. */
    fun moveTo(endTime: MachineTime, endPosition: List<Double>, endSpeed: Double)
    fun flush(time: MachineTime)
}

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
}
