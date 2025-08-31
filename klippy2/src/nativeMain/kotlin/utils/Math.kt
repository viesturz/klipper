package utils
import kotlin.math.sqrt

fun Double.squared() = this * this
fun Double.sqrt() = sqrt(this)
fun List<Double>.setValue(index: Int, value: Double) = toMutableList().apply { set(index, value) }.toList()

/** Linearly interpolate 0.11 to the given range */
fun Double.interpolate(to: ClosedFloatingPointRange<Double>): Double =
    this * (to.endInclusive - to.start) + to.start
/** Calculate value as a fraction of a linear range */
fun Double.deinterp(from: ClosedFloatingPointRange<Double>): Double =
    (this - from.start) / (from.endInclusive - from.start)

fun List<Double>.magnitude() = fold(0.0) { acc, d -> acc + d.squared() }.sqrt()
fun List<Double>.distanceTo(other: List<Double>) = foldIndexed(0.0) { i, acc, x -> acc + (x-other[i]).squared() }.sqrt()
fun List<Double>.moveBy(direction: List<Double>, distance: Double = 1.0) = zip(direction) { s, d -> s + d * distance}
fun List<Double>.vectorTo(endPosition: List<Double>) = zip(endPosition) { s, d -> d - s}
fun List<Double>.direction(): List<Double> {
    val mag = magnitude()
    require( mag > 0.0)
    val invMag = 1.0 / magnitude()
    return map { it * invMag }
}

fun dotProduct(prevVector: List<Double>, nextVector: List<Double>) = prevVector.zip(nextVector) { d1, d2 -> d1 * d2 }.sum()

fun List<Double>.lengthAlongDirection(direction: List<Double>): Double {
    return dotProduct(this, direction)
}

fun List<Double>.stdDev(): Double {
    if (size < 2) return 0.0
    val mean = average()
    val variance = map { (it - mean).squared() }.average()
    return variance.sqrt()
}
