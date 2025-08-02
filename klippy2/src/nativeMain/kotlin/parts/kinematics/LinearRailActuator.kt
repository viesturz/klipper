package parts.kinematics

import EndstopSyncBuilder
import MachineBuilder
import MachineRuntime
import MachineTime
import PartLifecycle
import io.github.oshai.kotlinlogging.KotlinLogging
import machine.MoveOutsideRangeException
import machine.getNextMoveTime
import machine.getNow

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

    override fun initializePosition(time: MachineTime, position: List<Double>) {
        rail.initializePosition(time, position[0], false)
    }

    override fun setupTriggerSync(sync: EndstopSyncBuilder) {
        rail.setupTriggerSync(sync)
    }

    override val axisStatus: List<RailStatus>
        get() = listOf(rail.railStatus)

    override suspend fun home(axis: List<Int>): HomeResult {
        require(axis.size == 1)
        require(axis[0] == 0)
        val homing = rail.homing ?: throw IllegalStateException("Homing not configured")
        logger.info { "Homing start" }

        val initialPosition = when (homing.direction) {
            HomingDirection.INCREASING -> rail.range.positionMin - (homing.endstopPosition - rail.range.positionMin) * 0.2
            HomingDirection.DECREASING -> rail.range.positionMax + (rail.range.positionMax - homing.endstopPosition) * 0.2
        }
        val startTime = getNextMoveTime()
        initializePosition(startTime, listOf(initialPosition))
        if (!rail.railStatus.powered) {
            rail.setPowered(time = startTime, value = true)
        }
        val session = makeProbingSession {
            addActuator(this@LinearRailActuatorImpl)
            addTrigger(homing.endstopTrigger)
        }
        session.use {
            val homingMove = HomingMove(session, this, runtime)
            val result = homingMove.home(listOf(homing.endstopPosition),homing)
            logger.info { "Homing result $result" }
            if (result == HomeResult.SUCCESS) {
                rail.setHomed(true)
            }
            return result
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