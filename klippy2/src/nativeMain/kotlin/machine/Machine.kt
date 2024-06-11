package machine

interface Machine {
    fun setup()
    suspend fun run()
    fun shutdown(reason: String)
    fun runGcode(cmd: String)

    val status: Map<String, String>
    val state: MachineState
}

enum class MachineState {
    INITIALIZING,
    RUNNING,
    SHUTDOWN,
}