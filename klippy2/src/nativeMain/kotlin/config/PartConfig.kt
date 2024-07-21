package config

import MachineDuration
import Temperature
import celsius
import parts.MachineRuntime
import kotlin.math.max
import kotlin.math.min

sealed class PartConfig(
    val name: String
)

typealias ActionBlock = suspend (m: MachineRuntime) -> Unit

class Fan(
    name: String,
    val pin: DigitalOutPin,
    val offBelow: Float = 0f,
    val kickStartTime: Float = 0.1f,
    val shutdownSpeed: Float = 0f,
) : PartConfig(name) {
    init {
        require(offBelow in 0f..1f)
        require(kickStartTime in 0f..10f)
        require(shutdownSpeed in 0f..1f)
    }
}

class Button(
    name: String,
    val pin: DigitalInPin,
    val onClicked: ActionBlock
) : PartConfig(name)

abstract class TemperatureSensorPart(name: String): PartConfig(name){
    abstract val minTemp: Temperature
    abstract val maxTemp: Temperature
}

sealed interface TemperatureControl
data class Watermark(val maxDelta: Double = 2.0): TemperatureControl
data class PID(val P: Double, val I: Double, val D: Double, val timeWindow: MachineDuration): TemperatureControl

class AdcTemperatureSensor(
    name: String,
    pin: AnalogInPin,
    val sensor: TemperatureCalibration,
    override val minTemp: Temperature = 0.celsius,
    override val maxTemp: Temperature = 300.celsius,
) : TemperatureSensorPart(name) {
    val pin = pin.copy(
        minValue = min(pin.fromResistance(sensor.tempToResistance(minTemp)), pin.fromResistance(sensor.tempToResistance(maxTemp))),
        maxValue = max(pin.fromResistance(sensor.tempToResistance(minTemp)), pin.fromResistance(sensor.tempToResistance(maxTemp))),
    )
}

class Heater(
    name: String,
    val pin: DigitalOutPin,
    val sensor: TemperatureSensorPart,
    val control: TemperatureControl,
): PartConfig(name)
