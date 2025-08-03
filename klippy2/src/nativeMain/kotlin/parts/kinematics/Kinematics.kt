package parts.kinematics

import EndstopSyncBuilder
import MachineTime
import machine.MoveOutsideRangeException

enum class MotionType {
    OTHER,
    LINEAR,
    ROTATION,
}

/** A motion actuator. Commands one or more axis simultaneously. */
interface MotionActuator {
    // Number of coordinates
    val size: Int
    val positionTypes: List<MotionType>
    /** The position requested by the commands.  */
    var commandedPosition: List<Double>
    val axisStatus: List<RailStatus>

    val commandedEndTime: MachineTime

    /** Compute speed restrictions for the move. */
    fun computeMaxSpeeds(start: List<Double>, end: List<Double>): LinearSpeeds
    fun checkMoveInBounds(start: List<Double>, end: List<Double>): MoveOutsideRangeException?

    /** Home at least the specified axis, does nothing if the list is empty. */
    suspend fun home(axis: List<Int>): HomeResult

    /** Setup actuator and endstop/probe trigger sync */
    fun setupTriggerSync(sync: EndstopSyncBuilder)

    /** Sets a position for the actuator. Should not perform any moves.
     *  Clears any planned moves after the time. */
    fun initializePosition(time: MachineTime, position: List<Double>)
    /* A constant-acceleration move to a new position. */
    fun moveTo(startTime: MachineTime, endTime: MachineTime,
               startSpeed: Double, endSpeed: Double,
               endPosition: List<Double>)
    /** Generates move commands up to the given time. */
    fun generate(time: MachineTime)
}

enum class HomeResult() {
    SUCCESS, FAIL
}
