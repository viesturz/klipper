package parts

import config.PartConfig

@Suppress("UNCHECKED_CAST")
fun buildPart(config: PartConfig, setup: MachineSetup): MachinePart<PartConfig> = when(config) {
    is config.Fan -> Fan(config, setup) as MachinePart<PartConfig>
    is config.Button -> Button(config, setup) as MachinePart<PartConfig>
    else -> TODO("Part ${config.name} not implemented")
}