import parts.motionplanner.Position
import utils.lengthAlongDirection
import utils.stdDev
import kotlin.math.absoluteValue
import kotlin.math.roundToLong

class StepsToPosition(val referenceSteps: Long, val referencePosition: Double, val stepDistance: Double) {
    fun toSteps(position: Double) = ((position - referencePosition) / stepDistance + referenceSteps).roundToLong()
    fun toPosition(steps: Long): Double = (steps - referenceSteps) * stepDistance + referencePosition
}

fun getBestTriggerPosition(samples: List<Position>, direction: Position): BestTriggerPosition {
    if (samples.size < 2) return BestTriggerPosition(samples.last(), 0.0, 0.0)
    val samplesAlongDirection = samples.sortedBy { it.lengthAlongDirection(direction) }
    val positions = samples.map { it.lengthAlongDirection(direction) }.sorted()
    val maxError = (positions.last() - positions.first()).absoluteValue
    val stdDev = positions.stdDev()
    val medianPosition = samplesAlongDirection[samplesAlongDirection.size / 2]
    return BestTriggerPosition(medianPosition, maxError, stdDev)
}

data class BestTriggerPosition(val position: Position, val maxError: Double, val stdDev: Double)