package machine

import kotlinx.coroutines.flow.StateFlow
import machine.impl.GCode

interface Machine {
    suspend fun run()
    fun shutdown(reason: String)

    val shutdownReason: String
    val status: Map<String, String>
    val state: StateFlow<State>
    val queueManager: QueueManager
    val gCode: GCode

    enum class State {
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
}
