import io.github.oshai.kotlinlogging.KLogger
import machine.SCHEDULING_TIME
import machine.getNextMoveTime
import parts.kinematics.LinearRail
import parts.kinematics.LinearRailActuator
import parts.motionplanner.KinMove2
import parts.motionplanner.Position
import parts.motionplanner.SimpleMotionPlanner
import utils.lengthAlongDirection
import utils.stdDev
import kotlin.math.absoluteValue
import kotlin.math.roundToLong

class StepsToPosition(val referenceSteps: Long, val referencePosition: Double, val stepDistance: Double) {
    fun toSteps(position: Double) = ((position - referencePosition) / stepDistance + referenceSteps).roundToLong()
    fun toPosition(steps: Long): Double = (steps - referenceSteps) * stepDistance + referencePosition
}

fun getBestTriggerPosition(samples: List<Position>, direction: Position): BestTriggerPosition<Position> {
    if (samples.size < 2) return BestTriggerPosition(samples.last(), 0.0, 0.0)
    val samplesAlongDirection = samples.sortedBy { it.lengthAlongDirection(direction) }
    val positions = samples.map { it.lengthAlongDirection(direction) }.sorted()
    val maxError = (positions.last() - positions.first()).absoluteValue
    val stdDev = positions.stdDev()
    val medianPosition = samplesAlongDirection[samplesAlongDirection.size / 2]
    return BestTriggerPosition(medianPosition, maxError, stdDev)
}

fun getBestTriggerPosition(samples: List<Double>): BestTriggerPosition<Double> {
    if (samples.size < 2) return BestTriggerPosition(samples.last(), 0.0, 0.0)
    val sorted = samples.sorted()
    val maxError = (sorted.last() - sorted.first()).absoluteValue
    val stdDev = sorted.stdDev()
    val medianPosition = sorted[sorted.size / 2]
    return BestTriggerPosition(medianPosition, maxError, stdDev)
}

suspend fun moveRailsTo(rails: List<LinearRail>, position: Double, speed: Double? = null): MachineTime {
    val moves = rails.map { KinMove2(LinearRailActuator(it), listOf(position), speed) }
    val startTime = getNextMoveTime()
    val planner = SimpleMotionPlanner(startTime, checkLimits = false)
    return planner.moveTo(*moves.toTypedArray()) + SCHEDULING_TIME
}

suspend fun alignPositionsAfterTrigger(rails: List<LinearRail>, logger: KLogger?): MachineTime? {
    if (rails.size < 2) return null
    val pos = rails[0].commandedPosition
    val maxSkew = rails.fold(0.0) { cur, new -> cur.coerceAtLeast((pos - new.commandedPosition).absoluteValue) }
    if (maxSkew == 0.0) return null

    logger?.info { "Correcting Skew of $maxSkew after trigger, commanded position is $pos" }
    return moveRailsTo(rails, pos)
}

data class BestTriggerPosition<T>(val position: T, val maxError: Double, val stdDev: Double)