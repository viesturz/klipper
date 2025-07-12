package parts.kinematics

import MachineTime
import chelper.stepper_kinematics
import kotlinx.cinterop.ExperimentalForeignApi
import mcu.GcWrapper
import parts.LinearStepper

/** Multiple motors driving the same rail. */
class CombineLinearStepper(vararg railArgs: LinearStepper) : LinearStepper {
    private val steppers: List<LinearStepper> = railArgs.toList()
    override val speeds: LinearSpeeds
    override val range: LinearRange
    override val homing: Homing? = null
    init {
        require(railArgs.isNotEmpty())
        var sp = railArgs[0].speeds
        var ra = railArgs[0].range
        for (a in railArgs) {
            val s = a.speeds
            val r = a.range
            sp = sp.intersection(s)
            ra = ra.intersection(r)
        }
        speeds = sp
        range = ra
    }

    override fun setupHomingMove(homingMove: HomingMove) {
        steppers.forEach { it.setupHomingMove(homingMove) }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun assignToKinematics(kinematicsProvider: () -> GcWrapper<stepper_kinematics>) {
        steppers.forEach { assignToKinematics(kinematicsProvider)}
    }

    override var commandedPosition: Double
        get() = steppers[0].commandedPosition
        set(value) {
            for (a in steppers) {
                a.commandedPosition = value
            }
        }
    override val commandedEndTime: Double
        get() = steppers[0].commandedEndTime

    override fun checkMove(start: Double, end: Double): LinearSpeeds {
        var sp = steppers[0].checkMove(start, end)
        for (a in steppers) {
            sp = sp.intersection(a.checkMove(start, end))
        }
        return sp
    }
    override fun initializePosition(time: MachineTime, position: Double,  homed: Boolean) {
        for (a in steppers) {
            a.initializePosition(time, position, homed)
        }
    }

    override val railStatus: RailStatus
        get() = steppers.map { it.railStatus }.reduce { a,b -> a.combine(b)  }

    override suspend fun setPowered(time: MachineTime, value: Boolean) {
        for (a in steppers) {
            a.setPowered(time, value)
        }
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