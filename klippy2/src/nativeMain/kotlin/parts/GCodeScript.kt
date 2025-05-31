package parts

import GCodeHandler
import MachineBuilder
import PartLifecycle

fun MachineBuilder.GCodeScript(
    name: String,
    block: GCodeHandler
) = GCodeScriptImpl(name, block, this).also { addPart(it) }

class GCodeScriptImpl(override val name: String, val block: GCodeHandler, builder: MachineBuilder): PartLifecycle {
    init {
        builder.registerCommand(name, rawText = false, block)
    }
}
