package utils

import Temperature
import kotlin.math.absoluteValue
import kotlin.math.pow

fun Double.format(mainDigits: Int, fractionDigits: Int = 0): String {
    if (!this.isFinite() || this.absoluteValue >= Long.MAX_VALUE) {
        return this.toString()
    }
    val negative = this < 0.0
    val value = if (negative) -this else this
    val main = value.toULong()
    return buildString {
        if (negative) append("-")
        appendULong(main, mainDigits)
        if (fractionDigits > 0) {
            append(".")
            val intFraction = ((value - main.toDouble()) * 10.0.pow(fractionDigits)).toULong()
            appendULong(intFraction, fractionDigits)
        }
    }
}

fun Comparable<*>.format(mainDigits: Int, fractionDigits: Int = 0) = when (this) {
    is Double -> this.format(mainDigits, fractionDigits)
    is Temperature -> "${celsius.format(mainDigits,fractionDigits)}°C"
    else -> this.toString()
}

fun StringBuilder.appendULong(v: ULong, digits: Int) {
    var x: ULong = 1u
    for(i in 1..digits) {
        if (v < x) append("0")
        x *= 10u
    }
    append(v)
}