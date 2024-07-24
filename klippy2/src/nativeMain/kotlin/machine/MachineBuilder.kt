package machine

import config.McuConfig
import machine.impl.GCode
import machine.impl.GCodeHandler
import machine.impl.PartLifecycle
import machine.impl.Reactor
import mcu.McuSetup

typealias ActionBlock = suspend (m: MachineRuntime) -> Unit

/** Interface to build the machine config from parts.
 * All the parts define extension functions to this for adding themselves to the machine */
interface MachineBuilder
{
    fun setupMcu(config: McuConfig): McuSetup
    fun addPart(part: PartLifecycle)
    fun registerCommand(command: String, handler: GCodeHandler)
    fun registerMuxCommand(command: String, muxParam: String, muxValue: String, handler: GCodeHandler)
}

/** API available at the run time.  */
interface MachineRuntime {
    val reactor: Reactor
    val gCode: GCode
    val queueManager: QueueManager
}

/** Base class for all parts. */
interface MachinePart {
    val name: String
}
