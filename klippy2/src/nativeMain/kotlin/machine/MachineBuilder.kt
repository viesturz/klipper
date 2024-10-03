package machine

import config.McuConfig
import mcu.McuSetup

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
    fun flushMoves(machineTime: Double)
    val parts: List<MachinePart>
    val reactor: Reactor
    val gCode: GCode
    val queueManager: QueueManager
}

/** Base class for all parts. */
interface MachinePart {
    val name: String
}

/** API for part lifecycle management. */
interface PartLifecycle: MachinePart {
    fun status(): Map<String, Any> = mapOf()
    // Called when all MCUs are configured and parts components initialized.
    suspend fun onStart(runtime: MachineRuntime){}
    // Called when printer session is over and it enters idle state.
    fun onSessionEnd(){}
    fun shutdown(){}
}

inline fun <reified PartType> MachineRuntime.getPartByName(name: String): PartType? =
    parts.first { it.name == name && it is PartType } as PartType?

/** Get a list of all parts implementing a specific API. */
inline fun <reified PartApi> MachineRuntime.getPartsImplementing() = parts.filterIsInstance<PartApi>()
