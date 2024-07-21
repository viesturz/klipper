package config

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

abstract class TemperatureSensorPart(name: String): PartConfig(name)

class AdcTemperatureSensor(
    name: String,
    pin: AnalogInPin,
    val sensor: TemperatureSensor,
    val min: Temperature = 0.celsius,
    val max: Temperature = 300.celsius,
) : TemperatureSensorPart(name) {
    val pin = pin.copy(
        minValue = min(pin.fromResistance(sensor.tempToResistance(min)), pin.fromResistance(sensor.tempToResistance(max))),
        maxValue = max(pin.fromResistance(sensor.tempToResistance(min)), pin.fromResistance(sensor.tempToResistance(max))),
    )
}
