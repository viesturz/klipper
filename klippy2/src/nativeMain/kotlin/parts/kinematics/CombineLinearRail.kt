package parts.kinematics

import EndstopSync
import EndstopSyncBuilder
import MachineRuntime
import MachineTime
import chelper.stepper_kinematics
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import machine.SCHEDULING_TIME
import machine.getNextMoveTime
import mcu.GcWrapper
import parts.motionplanner.KinMove2
import parts.motionplanner.SimpleMotionPlanner
import kotlin.math.absoluteValue

/** Multiple motors driving the same rail, both moving in lock-step. */
class CombineLinearStepper(vararg railArgs: LinearStepper,
                           val name: String = "CombineLinearStepper",
                           override val speed: LinearSpeeds,
                           override val range: LinearRange,
                           override val homing: Homing? = null) : LinearStepper {
    private val steppers: List<LinearStepper> = railArgs.toList()
    private val logger = KotlinLogging.logger("CombineLinearStepper")
    override val runtime: MachineRuntime
        get() = steppers[0].runtime
    override fun setupTriggerSync(sync: EndstopSyncBuilder) {
        steppers.forEach { it.setupTriggerSync(sync) }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun assignToKinematics(kinematicsProvider: () -> GcWrapper<stepper_kinematics>) {
        steppers.forEach { assignToKinematics(kinematicsProvider)}
    }

    override val commandedPosition: Double
        get() = steppers[0].commandedPosition
    override val commandedEndTime: Double
        get() = steppers[0].commandedEndTime

    override suspend fun initializePosition(time: MachineTime, position: Double,  homed: Boolean) {
        steppers.forEach { it.initializePosition(time, position, homed) }
    }

    override suspend fun updatePositionAfterTrigger(sync: EndstopSync) {
        steppers.forEach { it.updatePositionAfterTrigger(sync) }
        val pos = steppers[0].commandedPosition
        val maxSkew = steppers.fold(0.0) { cur, new -> cur.coerceAtLeast((pos - new.commandedPosition).absoluteValue) }
        if (maxSkew > 0.0) {
            logger.info { "Correcting Skew of $maxSkew after trigger in $name, commanded position is $pos" }
            val moves = steppers.map{KinMove2(LinearRailActuator(it), listOf(pos))}
            val startTime = getNextMoveTime()
            val planner = SimpleMotionPlanner(startTime, checkLimits = false)
            val endTime = planner.moveTo(*moves.toTypedArray()) + SCHEDULING_TIME
            generate(endTime)
            runtime.flushMoves(endTime)
            runtime.reactor.waitUntil(endTime)
            logger.info { "Skew correction done, took ${endTime - startTime} seconds" }
        }
    }

    override val railStatus: RailStatus
        get() = steppers.map { it.railStatus }.reduce { a,b -> a.combine(b)  }

    override suspend fun setPowered(time: MachineTime, value: Boolean) {
        steppers.forEach { it.setPowered(time, value) }
    }

    override fun setHomed(value: Boolean) {
        steppers.forEach { it.setHomed(value) }
    }

    override fun moveTo(
        startTime: MachineTime,
        endTime: MachineTime,
        startSpeed: Double,
        endSpeed: Double,
        endPosition: Double
    ) {
        for (a in steppers) {
            a.moveTo(startTime, endTime, startSpeed, endSpeed, endPosition)
        }
    }

    override fun generate(time: MachineTime) {
        for (a in steppers) {
            a.generate(time)
        }
    }
}