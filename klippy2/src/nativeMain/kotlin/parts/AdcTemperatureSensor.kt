package parts

import MachineTime
import Temperature
import celsius
import io.github.oshai.kotlinlogging.KotlinLogging


class AdcTemperatureSensor(override val config: config.AdcTemperatureSensor, val setup: MachineSetup): MachinePart<config.AdcTemperatureSensor> {
    val logger = KotlinLogging.logger("AdcTemperatureSensor ${config.name}")
    val adc = setup.acquireMcu(config.pin.mcu).addAnalogPin(config.pin)
    var temp: Temperature = 0.celsius

    override suspend fun onStart(runtime: MachineRuntime) {
        adc.setListener { m -> temp =
            config.sensor.resistanceToTemp(m.resistance)
            logger.info { "AdcTemperatureSensor ${config.name} = ${temp.celsius}" }
        }
    }

    override fun status(time: MachineTime) = mapOf("temperature" to temp)
}