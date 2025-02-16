package parts

import machine.GCodeHandler
import MachineBuilder
import MachineRuntime
import PartLifecycle

fun MachineBuilder.GCodeScript(
    name: String,
    block: GCodeHandler
) = GCodeScriptImpl(name, block).also { addPart(it) }

class GCodeScriptImpl(override val name: String, val block: GCodeHandler): PartLifecycle {
    override suspend fun onStart(runtime: MachineRuntime) {
        runtime.gCode.registerCommand(name, rawText = false, block)
    }
}
