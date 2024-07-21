package parts

import config.PartConfig
import mcu.ConfigurationException

@Suppress("UNCHECKED_CAST")
fun buildPart(config: PartConfig, setup: MachineSetup): MachinePart<PartConfig> = when(config) {
    is config.Fan -> Fan(config, setup) as MachinePart<PartConfig>
    is config.Button -> Button(config, setup) as MachinePart<PartConfig>
    is config.AdcTemperatureSensor -> AdcTemperatureSensor(config, setup) as MachinePart<PartConfig>
    else -> throw ConfigurationException("Part ${config::class.simpleName} not implemented")
}