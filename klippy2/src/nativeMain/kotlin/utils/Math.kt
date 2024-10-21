package utils
import kotlin.math.sqrt

fun Double.squared() = this * this
fun Double.sqrt() = sqrt(this)

fun Double.interpolate(to: ClosedFloatingPointRange<Double>): Double =
    this * (to.endInclusive - to.start) + to.start
fun Double.deinterp(from: ClosedFloatingPointRange<Double>): Double =
    (this - from.start) / (from.endInclusive - from.start)

fun List<Double>.magnitude() = fold(0.0) { acc, d -> acc + d.squared() }.sqrt()
fun List<Double>.distanceTo(other: List<Double>) = foldIndexed(0.0) { i, acc, x -> acc + (x-other[i]).squared() }.sqrt()

fun dotProduct(prevVector: List<Double>, nextVector: List<Double>) =
    prevVector.zip(nextVector) { d1, d2 -> d1 * d2 }.sum()

//fun crossProductMangnitude(prevPos: List<Double>, junctionPos: List<Double>, nextPos: List<Double>): Double {
//    var result = 0.0
//    for (i in prevPos.indices) {
//
//
//        val d1 = junctionPos[i] - prevPos[i]
//        val d2 = nextPos[i] - junctionPos[i]
//        result += d1 * d2
//    }
//    return result
//}
