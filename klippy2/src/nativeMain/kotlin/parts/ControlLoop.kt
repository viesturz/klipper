package parts

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import MachineBuilder
import MachineRuntime
import PartLifecycle
import withLogger

fun MachineBuilder.ControlLoop(
    name: String = defaultName("ControlLoop"),
    autostart: Boolean = true,
    control: suspend (runtime: MachineRuntime) -> Unit,
): ControlLoop = LocalControlImpl(name, control, autostart).also { addPart(it) }

interface ControlLoop {
    val running: Boolean
    fun start()
    fun stop()
}

private class LocalControlImpl(
    override val name: String,
    val control: suspend (runtime: MachineRuntime) -> Unit,
    val autostart: Boolean,
) : ControlLoop, PartLifecycle {
    private lateinit var runtime: MachineRuntime
    private var job: Job? = null
    private val logger = KotlinLogging.logger("ControlLoop $name")
    override val running
        get() = job != null

    override suspend fun onStart(runtime: MachineRuntime) {
        this.runtime = runtime
        if (autostart) start()
    }

    override fun start() {
        job = runtime.reactor.launch {
            try {
                control(runtime.withLogger(name))
            } catch (e: CancellationException) {
                logger.info { "Cancelled" }
            } catch (e: Exception) {
                logger.error(e) { "Crashed" }
                runtime.shutdown(reason = "$name crashed with ${e.message}", emergency = true)
            } finally {
                job = null
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    override fun shutdown() {
        stop()
    }

    override fun status() = mapOf("running" to running)
}