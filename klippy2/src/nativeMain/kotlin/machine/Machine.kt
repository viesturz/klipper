package machine

import kotlinx.coroutines.flow.StateFlow

interface Machine {
    suspend fun run()
    fun shutdown(reason: String)
    fun runGcode(cmd: String)

    val shutdownReason: String
    val status: Map<String, String>
    val state: StateFlow<MachineState>
}

enum class MachineState {
    /** New machine */
    NEW,
    /** The configuration is being established */
    CONFIGURING,
    /** The parts are starting. */
    STARTING,
    /** All parts report up and running. */
    RUNNING,
    SHUTDOWN,
}