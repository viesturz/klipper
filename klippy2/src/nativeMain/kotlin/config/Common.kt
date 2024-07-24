package config

import MachineDuration


sealed interface TemperatureControl
data class Watermark(val maxDelta: Double = 2.0): TemperatureControl
data class PID(val P: Double, val I: Double, val D: Double, val timeWindow: MachineDuration): TemperatureControl
