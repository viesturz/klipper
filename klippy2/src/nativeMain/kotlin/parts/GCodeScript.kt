package parts

import machine.GCodeHandler
import machine.MachineBuilder
import machine.MachineRuntime
import machine.PartLifecycle

fun MachineBuilder.GCodeScript(
    name: String,
    block: GCodeHandler
) = GCodeScriptImpl(name, block).also { addPart(it) }

class GCodeScriptImpl(override val name: String, val block: GCodeHandler): PartLifecycle {
    override suspend fun onStart(runtime: MachineRuntime) {
        runtime.gCode.registerCommand(name, rawText = false, block)
    }
}
