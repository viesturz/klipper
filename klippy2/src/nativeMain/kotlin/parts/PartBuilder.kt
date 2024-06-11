package parts

import config.PartConfig

fun buildPart(config: PartConfig, setup: MachineRuntime) = when(config) {
    is config.Fan -> Fan(config, setup)
}