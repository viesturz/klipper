package parts

import Temperature
import celsius
import config.AnalogInPin
import config.TemperatureCalibration
import config.ValueSensor
import io.github.oshai.kotlinlogging.KotlinLogging
import kelvins
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import machine.MachineBuilder
import machine.MachineRuntime
import machine.impl.PartLifecycle
import machine.impl.waitUntil

fun MachineBuilder.AdcTemperatureSensor(
    name: String,
    pin: AnalogInPin,
    sensor: TemperatureCalibration,
    minTemp: Temperature = 0.celsius,
    maxTemp: Temperature = 300.celsius,
): ValueSensor<Temperature>  = AdcTemperatureSensorImpl(
        name,pin,sensor,minTemp,maxTemp,this).also { addPart(it) }

private class AdcTemperatureSensorImpl(
    override val name: String,
    pinConfig: AnalogInPin,
    val sensor: TemperatureCalibration,
    override val minValue: Temperature,
    override val maxValue: Temperature,
    setup: MachineBuilder): PartLifecycle, ValueSensor<Temperature> {
    val adc = setup.setupMcu(pinConfig.mcu).addAnalogPin(pinConfig.validResistanceRange(sensor.tempToResistance(minValue), sensor.tempToResistance(maxValue)))
    val logger = KotlinLogging.logger("AdcTemperatureSensorImpl $name")
    val _value = MutableStateFlow(ValueSensor.Measurement(0.0, 0.kelvins))
    override val measurement: StateFlow<ValueSensor.Measurement<Temperature>>
        get() = _value

    init {
        setup.registerMuxCommand("TEMPERATURE_WAIT", "SENSOR", name) { params ->
            val min = params.getCelsius("MINIMUM", minValue)
            val max = params.getCelsius("MAXIMUM", maxValue)
            waitUntil(params.queue.flush())
            waitFor(min, max)
        }
    }

    override suspend fun onStart(runtime: MachineRuntime) {
        adc.setListener { m ->
            val measurement = ValueSensor.Measurement(m.time, sensor.resistanceToTemp(m.resistance))
            _value.value = measurement
        }
    }

    override fun status() = mapOf("temperature" to measurement.value.value)
}
