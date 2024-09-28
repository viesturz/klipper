package parts

import machine.MachineBuilder
import machine.PartLifecycle

fun MachineBuilder.PrinterCommands(
): PrinterCommands = PrinterCommandsImpl(this).also { addPart(it) }

interface PrinterCommands

class PrinterCommandsImpl(setup: MachineBuilder): PrinterCommands, PartLifecycle {
    override val name = "PrinterCommands"
    init {

    }
}