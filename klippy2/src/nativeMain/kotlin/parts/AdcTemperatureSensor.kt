package parts

import MachineTime
import Temperature
import celsius
import config.AnalogInPin
import config.TemperatureCalibration
import io.github.oshai.kotlinlogging.KotlinLogging
import kelvins
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import machine.MachineBuilder
import machine.MachinePart
import machine.MachineRuntime
import machine.impl.PartLifecycle
import kotlin.math.max
import kotlin.math.min

fun MachineBuilder.AdcTemperatureSensor(
    name: String,
    pin: AnalogInPin,
    sensor: TemperatureCalibration,
    minTemp: Temperature = 0.celsius,
    maxTemp: Temperature = 300.celsius,
): TemperatureSensor {
    val impl = AdcTemperatureSensorImpl(
        name,pin,sensor,minTemp,maxTemp,this)
    addPart(impl)
    return impl
}

interface TemperatureSensor: MachinePart {
    data class Measurement(val time: MachineTime, val temp: Temperature)
    val value: StateFlow<Measurement>
    val minTemp: Temperature
    val maxTemp: Temperature
}

private class AdcTemperatureSensorImpl(
    override val name: String,
    pinConfig: AnalogInPin,
    val sensor: TemperatureCalibration,
    override val minTemp: Temperature,
    override val maxTemp: Temperature,
    setup: MachineBuilder): PartLifecycle, TemperatureSensor {
    val logger = KotlinLogging.logger("AdcTemperatureSensor $name")
    val adc = setup.setupMcu(pinConfig.mcu).addAnalogPin(limitPin(pinConfig, sensor, minTemp, maxTemp))
    val _value = MutableStateFlow(TemperatureSensor.Measurement(0.0, 0.kelvins))
    override val value: StateFlow<TemperatureSensor.Measurement>
        get() = _value

    override suspend fun onStart(runtime: MachineRuntime) {
        adc.setListener { m ->
            val measurement = TemperatureSensor.Measurement(m.time, sensor.resistanceToTemp(m.resistance))
            logger.info { "AdcTemperatureSensor $name = ${measurement.temp.celsius}" }
            _value.value = measurement
        }
    }

    override fun status() = mapOf("temperature" to value.value.temp)
}

fun limitPin(pin: AnalogInPin, sensor: TemperatureCalibration, minTemp: Temperature, maxTemp: Temperature) = pin.copy(
    minValue = min(pin.fromResistance(sensor.tempToResistance(minTemp)), pin.fromResistance(sensor.tempToResistance(maxTemp))),
    maxValue = max(pin.fromResistance(sensor.tempToResistance(minTemp)), pin.fromResistance(sensor.tempToResistance(maxTemp))),
)
