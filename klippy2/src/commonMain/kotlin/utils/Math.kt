package utils

fun Double.squared() = this * this

fun Double.interpolate(to: ClosedFloatingPointRange<Double>): Double =
    this * (to.endInclusive - to.start) + to.start
fun Double.deinterp(from: ClosedFloatingPointRange<Double>): Double =
    (this - from.start) / (from.endInclusive - from.start)

fun dotProduct(startPosition: DoubleArray, endPosition: DoubleArray, startPosition1: DoubleArray, endPosition1: DoubleArray): Double {
    var result = 0.0
    for (i in startPosition.indices) {
        val d1 = endPosition[i] - startPosition[i]
        val d2 = endPosition1[i] - startPosition1[i]
        result += d1 * d2
    }
    return result
}

