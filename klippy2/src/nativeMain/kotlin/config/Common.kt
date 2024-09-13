package config

import MachineTime
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.takeWhile


sealed interface TemperatureControl
data class Watermark(val maxDelta: Double = 2.0): TemperatureControl
data class PID(
    val kP: Double,
    val kI: Double,
    val kD: Double,
    ): TemperatureControl

interface ValueSensor<ValueType: Comparable<ValueType>> {
    data class Measurement<ValueType>(val time: MachineTime, val value: ValueType)
    val measurement: StateFlow<Measurement<ValueType>>
    val minValue: ValueType
    val maxValue: ValueType
    suspend fun waitFor(min: ValueType = minValue, max: ValueType = maxValue) {
        measurement.takeWhile { it.value < min || it.value > max }.count()
    }
}
