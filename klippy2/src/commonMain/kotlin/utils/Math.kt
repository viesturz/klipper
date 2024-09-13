package utils

fun Double.interpolate(to: ClosedFloatingPointRange<Double>): Double =
    this * (to.endInclusive - to.start) + to.start
fun Double.deinterp(from: ClosedFloatingPointRange<Double>): Double =
    (this - from.start) / (from.endInclusive - from.start)