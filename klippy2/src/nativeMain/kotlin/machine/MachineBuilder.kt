package machine

import config.McuConfig
import machine.impl.GCode
import machine.impl.GCodeCommand
import machine.impl.PartLifecycle
import machine.impl.Reactor
import mcu.McuSetup

typealias GCodeHandler = suspend GCodeRunner.(cmd: GCodeCommand) -> Unit
interface GCodeRunner {
    suspend fun gcode(cmd: String)
    fun respond(msg: String)
}

typealias ActionBlock = suspend (m: MachineRuntime) -> Unit

/** Interface to build the machine config from parts.
 * All the parts define extension functions to this for adding themselves to the machine */
interface MachineBuilder
{
    fun setupMcu(config: McuConfig): McuSetup
    fun addPart(part: PartLifecycle)
    fun registerCommand(command: String, rawText: Boolean = false, handler: GCodeHandler)
    fun registerMuxCommand(command: String, muxParam: String, muxValue: String, handler: GCodeHandler)
}

/** API available at the run time.  */
interface MachineRuntime {
    val parts: List<MachinePart>
    val reactor: Reactor
    val gCode: GCode
    val queueManager: QueueManager
}

/** Base class for all parts. */
interface MachinePart {
    val name: String
}

inline fun <reified PartType> MachineRuntime.getPartByName(name: String): PartType? =
    parts.first { it.name == name && it is PartType } as PartType?
