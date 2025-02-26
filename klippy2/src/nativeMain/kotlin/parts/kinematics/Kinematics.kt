package parts.kinematics

import MachineTime

/** A motion actuator. Commands one or mode axis simultaneously. */
interface MotionActuator {
    // Number of coordinates
    val size: Int
    val positionTypes: List<MotionType>
    /** The position requested by the commands.  */
    var commandedPosition: List<Double>

    /** Check move validity and return speed restrictions for the move. */
    fun checkMove(start: List<Double>, end: List<Double>): LinearSpeeds

    /** Sets a homed position for the actuator. Should not perform any moves. */
    fun initializePosition(time: MachineTime, position: List<Double>)
    /* A constant-acceleration move to a new position. */
    fun moveTo(startTime: MachineTime, endTime: MachineTime,
               startSpeed: Double, endSpeed: Double,
               endPosition: List<Double>)
    /** Generates move commands up to the given time. */
    fun generate(time: MachineTime)
}

/** An actuator that has a single liner rail. */
class LinearRailActuator(val rail: LinearRail): MotionActuator {
    override val size = 1
    override val positionTypes = listOf(MotionType.LINEAR)
    override var commandedPosition: List<Double>
        get() = listOf(rail.commandedPosition)
        set(value) { rail.commandedPosition = value[0]}

    override fun checkMove(start: List<Double>, end: List<Double>): LinearSpeeds {
        return rail.checkMove(start[0], end[0])
    }

    override fun initializePosition(time: MachineTime, position: List<Double>) {
        rail.initializePosition(time, position[0])
    }

    override fun moveTo(
        startTime: MachineTime,
        endTime: MachineTime,
        startSpeed: Double,
        endSpeed: Double,
        endPosition: List<Double>
    ) {
        rail.moveTo(startTime, endTime, startSpeed, endSpeed, endPosition[0])
    }

    override fun generate(time: MachineTime) {
        rail.generate(time)
    }
}