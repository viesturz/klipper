import config.McuConfig
import kotlinx.coroutines.flow.StateFlow
import machine.CommandQueue
import machine.Reactor

typealias ActionBlock = suspend (m: MachineRuntime) -> Unit

/** Interface to build the machine config from parts.
 * All the parts define extension functions to this for adding themselves to the machine */
interface MachineBuilder
{
    fun setupMcu(config: McuConfig): McuSetup
    fun defaultName(className: String): String
    fun addPart(part: PartLifecycle)
    fun registerCommand(command: String, rawText: Boolean = false, handler: GCodeHandler)
    fun registerMuxCommand(command: String, muxParam: String, muxValue: String, handler: GCodeHandler)
}

/** External API to a machine */
interface Machine {
    /** Starts the machine - this can take a few seconds to complete.
     *  Will throw exception if the startup fails.
     * */
    suspend fun start()
    fun shutdown(reason: String, emergency: Boolean = false)

    /** Runs a gcode.
     * Most gcodes schedule the moves and return immediately.
     * But some may take significant time to complete, like wait for temperature.
     * */
    suspend fun gcode(command: String, responseHandler: ((response: String) -> Unit) = {})

    val shutdownReason: String
    val status: Map<String, String>
    val state: StateFlow<State>

    enum class State {
        /** New machine */
        NEW,
        /** The configuration is being established */
        CONFIGURING,
        /** The parts are starting. */
        STARTING,
        /** All parts report up and running. */
        RUNNING,
        STOPPING,
        SHUTDOWN,
    }
}

/** API available to its parts at the run time.  */
interface MachineRuntime: Machine {
    val parts: List<MachinePart>
    val reactor: Reactor

    /** Start a new queue, starting as soon as possible. */
    fun newQueue(): CommandQueue
    /** Create a new queue that will be started on Join with another queue.
     *  Aiming to complete together with the join point.
     *  Note that if this queue takes longer than remaining commands in the target queue,
     *  it may stall it until this queue completes. */
    fun newBackdatingQueue(): CommandQueue
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
