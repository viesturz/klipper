package parts

import config.PartConfig
import mcu.ConfigurationException

@Suppress("UNCHECKED_CAST")
fun buildPart(config: PartConfig, setup: MachineSetup): MachinePart = when(config) {
    is config.Fan -> Fan(config, setup)
    is config.Button -> Button(config, setup)
    is config.Heater -> Heater(config, setup)
    else -> throw ConfigurationException("Part ${config::class.simpleName} not implemented")
}

fun buildTemperatureSensor(config: config.TemperatureSensorPart, setup: MachineSetup): TemperatureSensor = when(config) {
    is config.AdcTemperatureSensor -> AdcTemperatureSensor(config, setup)
    else -> throw ConfigurationException("Part ${config::class.simpleName} not implemented")
}