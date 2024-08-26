package parts

import MachineTime
import Temperature
import celsius
import config.AnalogInPin
import config.TemperatureCalibration
import io.github.oshai.kotlinlogging.KotlinLogging
import kelvins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.takeWhile
import machine.CommandQueue
import machine.MachineBuilder
import machine.MachinePart
import machine.MachineRuntime
import machine.addLongRunningCommand
import machine.impl.GCodeCommand
import machine.impl.PartLifecycle
import machine.impl.Reactor
import machine.impl.waitUntil
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds


suspend fun CoroutineScope.waitUntil(time: MachineTime) =
    delay(max(0.0, time - Reactor.now).seconds)


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
    val measurement: StateFlow<Measurement>
    val minTemp: Temperature
    val maxTemp: Temperature
    suspend fun waitForTemp(min: Temperature = minTemp, max: Temperature = maxTemp)
}

private class AdcTemperatureSensorImpl(
    override val name: String,
    pinConfig: AnalogInPin,
    val sensor: TemperatureCalibration,
    override val minTemp: Temperature,
    override val maxTemp: Temperature,
    setup: MachineBuilder): PartLifecycle, TemperatureSensor {
    val adc = setup.setupMcu(pinConfig.mcu).addAnalogPin(limitPin(pinConfig, sensor, minTemp, maxTemp))
    val logger = KotlinLogging.logger("AdcTemperatureSensorImpl $name")
    val _value = MutableStateFlow(TemperatureSensor.Measurement(0.0, 0.kelvins))
    override val measurement: StateFlow<TemperatureSensor.Measurement>
        get() = _value

    init {
        setup.registerMuxCommand("TEMPERATURE_WAIT", "SENSOR", name) { params ->
            val min = params.getCelsius("MINIMUM", minTemp)
            val max = params.getCelsius("MAXIMUM", maxTemp)
            waitUntil(params.queue.flush())
            waitForTemp(min, max)
        }
    }

    override suspend fun waitForTemp(min: Temperature, max: Temperature) {
        logger.info { "Waiting for min=$min, max=$max" }
        measurement.takeWhile { it.temp < min || it.temp > max }.count()
        logger.info { "Done waiting for min=$min, max=$max, temp = ${measurement.value.temp}" }
    }

    override suspend fun onStart(runtime: MachineRuntime) {
        adc.setListener { m ->
            val measurement = TemperatureSensor.Measurement(m.time, sensor.resistanceToTemp(m.resistance))
            _value.value = measurement
        }
    }

    override fun status() = mapOf("temperature" to measurement.value.temp)
}

fun limitPin(pin: AnalogInPin, sensor: TemperatureCalibration, minTemp: Temperature, maxTemp: Temperature) = pin.copy(
    minValue = min(pin.fromResistance(sensor.tempToResistance(minTemp)), pin.fromResistance(sensor.tempToResistance(maxTemp))),
    maxValue = max(pin.fromResistance(sensor.tempToResistance(minTemp)), pin.fromResistance(sensor.tempToResistance(maxTemp))),
)
