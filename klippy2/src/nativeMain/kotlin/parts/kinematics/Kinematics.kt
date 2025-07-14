package parts.kinematics

import EndstopSync
import MachineBuilder
import MachineRuntime
import MachineTime
import PartLifecycle
import machine.SCHEDULING_TIME
import machine.getNow
import kotlin.math.absoluteValue

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
    suspend fun home(axis: List<Int>): HomeResult

    /** Sets a position for the actuator. Should not perform any moves. */
    fun initializePosition(time: MachineTime, position: List<Double>, homed: List<Boolean>)
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

fun MachineBuilder.LinearRailActuator(rail: LinearRail) = LinearRailActuatorImpl(defaultName("LinearActuator"), rail).also { addPart(it) }

/** An actuator that has a single linear rail. */
class LinearRailActuatorImpl(override val name: String, val rail: LinearRail): MotionActuator, PartLifecycle {
    override val size = 1
    override val positionTypes = listOf(MotionType.LINEAR)
    override var commandedPosition: List<Double>
        get() = listOf(rail.commandedPosition)
        set(value) { rail.commandedPosition = value[0]}
    override val commandedEndTime: MachineTime
        get() = rail.commandedEndTime
    lateinit var runtime: MachineRuntime

    override suspend fun onStart(runtime: MachineRuntime) {
        this.runtime = runtime
    }

    override fun checkMove(start: List<Double>, end: List<Double>): LinearSpeeds {
        return rail.checkMove(start[0], end[0])
    }

    override fun initializePosition(time: MachineTime, position: List<Double>, homed: List<Boolean>) {
        rail.initializePosition(time, position[0], homed[0])
    }

    override val axisStatus: List<RailStatus>
        get() = listOf(rail.railStatus)

    override suspend fun home(axis: List<Int>): HomeResult {
        require(axis.size == 1)
        require(axis[0] == 0)
        val homing = rail.homing ?: throw IllegalStateException("Homing not configured")
        val range = rail.range
        val homingMove = HomingMove()
        var startTime = getNow() + SCHEDULING_TIME
        val endPosition = (range.positionMax - range.positionMin) * 1.2 * homing.direction.multipler
        val accel = rail.speeds.accel
        val speed = homing.speed.coerceAtMost(rail.speeds.speed) * homing.direction.multipler
        if (!rail.railStatus.powered) {
            rail.setPowered(time = startTime, value = true)
            startTime += 0.2 // Wait a bit after powered
        }
        rail.initializePosition(startTime, 0.0, false)
        rail.setupHomingMove(homingMove)
        homing.endstopTrigger.setupHomingMove(homingMove)
        // Acceleration move
        val accelDuration = (speed / accel).absoluteValue
        val accelDonePosition = accelDuration * speed * 0.5
        val cruiseDuration = (endPosition - accelDonePosition) / speed
        val endTime = startTime + accelDuration + cruiseDuration
        homingMove.start(startTime, endTime + 1.0)
        rail.moveTo(
            startTime = startTime,
            endTime = startTime + accelDuration,
            startSpeed = 0.0,
            endSpeed = speed,
            endPosition = accelDonePosition,
        )
        startTime += accelDuration
        // Steady speed move
        rail.moveTo(
            startTime = startTime,
            endTime = startTime + cruiseDuration,
            startSpeed = speed,
            endSpeed = speed,
            endPosition = endPosition,
        )
        // No deccel if homing fails, this stops hard
        val flushTime = startTime + cruiseDuration + 0.1
        rail.generate(flushTime)
        runtime.flushMoves(flushTime)
        val result = homingMove.wait()

        // Clear any pending moves
        // self.trapq_finalize_moves(self.trapq, reactor.NEVER, 0)

        if (result is EndstopSync.StateTriggered) {
            rail.setHomed(true)
            rail.commandedPosition = homing.endstopPosition
        }
        return when (result) {
            is EndstopSync.StateTriggered -> HomeResult.SUCCESS
            else -> HomeResult.FAIL
        }
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