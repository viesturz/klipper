package parts.kinematics

import EndstopSync
import MachineBuilder
import MachineRuntime
import MachineTime
import PartLifecycle
import io.github.oshai.kotlinlogging.KotlinLogging
import machine.MoveOutsideRangeException
import machine.SCHEDULING_TIME
import machine.getNow
import parts.motionplanner.KinMove2
import parts.motionplanner.Position
import parts.motionplanner.SimpleMotionPlanner

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
    val logger = KotlinLogging.logger(name)

    override suspend fun onStart(runtime: MachineRuntime) {
        this.runtime = runtime
    }

    override fun computeMaxSpeeds(start: List<Double>, end: List<Double>): LinearSpeeds = rail.speeds
    override fun checkMoveInBounds(start: List<Double>,end: List<Double>): MoveOutsideRangeException? {
        if (rail.range.outsideRange(start[0])) return MoveOutsideRangeException("X=${start[0]} is outside the range ${rail.range}")
        if (rail.range.outsideRange(end[0])) return MoveOutsideRangeException("X=${end[0]} is outside the range ${rail.range}")
        return null
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

        val startTime = getNow() + SCHEDULING_TIME
        rail.initializePosition(startTime, 0.0, false)
        if (!rail.railStatus.powered) {
            rail.setPowered(time = startTime, value = true)
        }

        val homingMove = HomingMove()
        rail.setupHomingMove(homingMove)
        homing.endstopTrigger.setupHomingMove(homingMove)
        homingMove.use {
            val endPosition = (range.positionMax - range.positionMin) * 1.2 * homing.direction.multipler
            val homeResult = doHomingMove(homingMove, listOf(endPosition), homing.speed)
            if (homeResult !is EndstopSync.StateTriggered) {
                return HomeResult.FAIL
            }

            rail.initializePosition(getNow(), homing.endstopPosition, true)

            val retractedPosition = homing.endstopPosition - homing.direction.multipler * homing.retractDist
            doRetract(listOf(retractedPosition), homing.speed)

            val secondEndPosition = homing.endstopPosition + homing.direction.multipler * homing.retractDist * 2
            val homingSamples = buildList {
                repeat(homing.attempts - 1) {
                    val homeResult = doHomingMove(homingMove, listOf(secondEndPosition), homing.secondSpeed)
                    println("Homing move result: $homeResult")
                    if (homeResult !is EndstopSync.StateTriggered) {
                        return HomeResult.FAIL
                    }
                    // TODO: query the actual motor position
                    add(rail.commandedPosition)
                    rail.initializePosition(getNow(), homing.endstopPosition, true)
                    doRetract(listOf(retractedPosition), homing.speed)
                }
            }
            // TODO check homing sample accuracy.
            rail.commandedPosition = retractedPosition
            println("Homing success")
            return HomeResult.SUCCESS
        }
    }

    suspend fun doRetract(targetPosition: Position, speed: Double) {
        val startTime = getNow() + SCHEDULING_TIME
        val endTime = SimpleMotionPlanner(startTime, checkLimits = false).moveTo(KinMove2(
            actuator = this,
            position = targetPosition,
            speed = speed,
        )) + 0.1
        rail.generate(endTime)
        runtime.flushMoves(endTime)
        runtime.reactor.waitUntil(endTime)
    }

    suspend fun doHomingMove(homingMove: HomingMove, targetPosition: Position, speed: Double): EndstopSync.State {
        val startTime = getNow() + SCHEDULING_TIME
        val endTime = SimpleMotionPlanner(startTime, checkLimits = false).moveTo(KinMove2(
            actuator = this,
            position = targetPosition,
            speed = speed,
        ))
        homingMove.start(startTime, timeoutTime = endTime + 1.0)
        val flushTime = endTime + 0.1
        rail.generate(flushTime)
        runtime.flushMoves(flushTime)
        val result = homingMove.wait()
        homingMove.allowMoves()
        return result
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