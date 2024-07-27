package config


sealed interface TemperatureControl
data class Watermark(val maxDelta: Double = 2.0): TemperatureControl
data class PID(
    val kP: Double,
    val kI: Double,
    val kD: Double,
    ): TemperatureControl
