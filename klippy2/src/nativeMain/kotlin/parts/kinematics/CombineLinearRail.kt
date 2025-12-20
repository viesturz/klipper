package parts.kinematics

import EndstopSync
import EndstopSyncBuilder
import MachineRuntime
import MachineTime
import alignPositionsAfterTrigger
import io.github.oshai.kotlinlogging.KotlinLogging
import mcu.connection.StepQueueImpl

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

    override fun assignToKinematics(register: (stepDistance: Double, s: StepQueueImpl) -> Unit ) {
        steppers.forEach { assignToKinematics(register)}
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
        val alignEndTime = alignPositionsAfterTrigger(steppers, logger)
        if (alignEndTime != null) {
            generate(alignEndTime)
            runtime.flushMoves(alignEndTime)
            runtime.reactor.waitUntil(alignEndTime)
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