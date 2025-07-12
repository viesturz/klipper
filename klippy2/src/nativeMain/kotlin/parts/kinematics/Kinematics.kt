package parts.kinematics

import MachineTime

/** A motion actuator. Commands one or more axis simultaneously. */
interface MotionActuator {
    // Number of coordinates
    val size: Int
    val positionTypes: List<MotionType>
    /** The position requested by the commands.  */
    var commandedPosition: List<Double>
    val axisStatus: List<RailStatus>

    val commandedEndTime: MachineTime

    /** Check move validity and return speed restrictions for the move. */
    fun checkMove(start: List<Double>, end: List<Double>): LinearSpeeds

    // Home at least the specified axis
    suspend fun home(axis: List<Int>)

    /** Sets a position for the actuator. Should not perform any moves. */
    fun initializePosition(time: MachineTime, position: List<Double>, homed: List<Boolean>)
    /* A constant-acceleration move to a new position. */
    fun moveTo(startTime: MachineTime, endTime: MachineTime,
               startSpeed: Double, endSpeed: Double,
               endPosition: List<Double>)
    /** Generates move commands up to the given time. */
    fun generate(time: MachineTime)
}

/** An actuator that has a single linear rail. */
class LinearRailActuator(val rail: LinearRail): MotionActuator {
    override val size = 1
    override val positionTypes = listOf(MotionType.LINEAR)
    override var commandedPosition: List<Double>
        get() = listOf(rail.commandedPosition)
        set(value) { rail.commandedPosition = value[0]}
    override val commandedEndTime: MachineTime
        get() = rail.commandedEndTime

    override fun checkMove(start: List<Double>, end: List<Double>): LinearSpeeds {
        return rail.checkMove(start[0], end[0])
    }

    override fun initializePosition(time: MachineTime, position: List<Double>, homed: List<Boolean>) {
        rail.initializePosition(time, position[0], homed[0])
    }

    override val axisStatus: List<RailStatus>
        get() = listOf(rail.railStatus)

    override suspend fun home(axis: List<Int>) {
        require(axis.size == 1)
        require(axis[0] == 0)
        val homing = rail.homing
        if (homing == null) {
            throw IllegalStateException("Homing not configured")
        }

        val homingMove = HomingMove()
        rail.commandedPosition = 0.0
        rail.setupHomingMove(homingMove)
        homing.endstopTrigger.setupHomingMove(homingMove)
        homingMove.start()
        rail.setPowered(time=0.0, value = true)
        rail.moveTo(
            startTime = 0.0,
            endTime = 0.0,
            startSpeed = 0.0,
            endSpeed = 0.0,
            endPosition = homing.endstopPosition,
        )
        rail.generate(time=0.0)
        homingMove.wait()
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