package parts

import MachineTime
import io.github.oshai.kotlinlogging.KotlinLogging
import kelvins
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AdcTemperatureSensor(override val config: config.AdcTemperatureSensor, val setup: MachineSetup): MachinePartLifecycle, TemperatureSensor {
    val logger = KotlinLogging.logger("AdcTemperatureSensor ${config.name}")
    val adc = setup.acquireMcu(config.pin.mcu).addAnalogPin(config.pin)
    val _value = MutableStateFlow(TemperatureSensor.Measurement(0.0, 0.kelvins))
    override val value: StateFlow<TemperatureSensor.Measurement>
        get() = _value

    override suspend fun onStart(runtime: MachineRuntime) {
        adc.setListener { m ->
            val measurement = TemperatureSensor.Measurement(m.time, config.sensor.resistanceToTemp(m.resistance))
            logger.info { "AdcTemperatureSensor ${config.name} = ${measurement.temp.celsius}" }
            _value.value = measurement
        }
    }

    override fun status(time: MachineTime) = mapOf("temperature" to value.value.temp)
}