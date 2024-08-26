package parts

import machine.MachineBuilder
import machine.MachineRuntime
import machine.impl.GCodeHandler
import machine.impl.PartLifecycle

fun MachineBuilder.GCodeScript(
    name: String,
    block: GCodeHandler
) = GCodeScriptImpl(name, block).also { addPart(it) }

class GCodeScriptImpl(override val name: String, val block: GCodeHandler): PartLifecycle {
    override suspend fun onStart(runtime: MachineRuntime) {
        runtime.gCode.registerCommand(name, block)
    }
}
