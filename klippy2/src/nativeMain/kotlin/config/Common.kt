package config

import MachineTime
import Temperature
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.takeWhile
import MachinePart


sealed interface TemperatureControl
data class Watermark(val maxDelta: Double = 2.0): TemperatureControl
data class PID(
    val kP: Double,
    val kI: Double,
    val kD: Double,
    ): TemperatureControl

interface ValueSensor<ValueType: Comparable<ValueType>>: MachinePart {
    data class Measurement<ValueType>(val time: MachineTime, val value: ValueType)
    val value: Measurement<ValueType>
    val flow: SharedFlow<Measurement<ValueType>>
    val minValue: ValueType
    val maxValue: ValueType
    suspend fun waitFor(min: ValueType = minValue, max: ValueType = maxValue) {
        flow.takeWhile { it.value < min || it.value > max }.count()
    }
}

typealias TemperatureSensor = ValueSensor<Temperature>