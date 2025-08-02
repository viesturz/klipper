package utils
import kotlin.math.sqrt

fun Double.squared() = this * this
fun Double.sqrt() = sqrt(this)

/** Linear interpolate 0.11 to the given range */
fun Double.interpolate(to: ClosedFloatingPointRange<Double>): Double =
    this * (to.endInclusive - to.start) + to.start
/** Calculate value as a fraction of linear range */
fun Double.deinterp(from: ClosedFloatingPointRange<Double>): Double =
    (this - from.start) / (from.endInclusive - from.start)

fun List<Double>.magnitude() = fold(0.0) { acc, d -> acc + d.squared() }.sqrt()
fun List<Double>.distanceTo(other: List<Double>) = foldIndexed(0.0) { i, acc, x -> acc + (x-other[i]).squared() }.sqrt()
fun List<Double>.moveBy(direction: List<Double>, distance: Double) = zip(direction) { s, d -> s + d * distance}
fun List<Double>.vectorTo(endPosition: List<Double>) = zip(endPosition) { s, d -> d - s}
fun List<Double>.direction(): List<Double> {
    val mag = magnitude()
    require( mag > 0.0)
    val invMag = 1.0 / magnitude()
    return map { it * invMag }
}

fun dotProduct(prevVector: List<Double>, nextVector: List<Double>) = prevVector.zip(nextVector) { d1, d2 -> d1 * d2 }.sum()
