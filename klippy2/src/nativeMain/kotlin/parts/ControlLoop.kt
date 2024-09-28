package parts

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import machine.MachineBuilder
import machine.MachineRuntime
import machine.PartLifecycle

fun MachineBuilder.ControlLoop(
    name: String,
    control: suspend (runtime: MachineRuntime) -> Unit
): ControlLoop = LocalControlImpl(name, control).also { addPart(it) }

interface ControlLoop {
    val running: Boolean
}

private class LocalControlImpl(
    override val name: String,
    val control: suspend (runtime: MachineRuntime) -> Unit
) : ControlLoop, PartLifecycle {
    private var job: Job? = null
    private val logger = KotlinLogging.logger("ControlLoop $name")
    override val running = job?.isActive ?: false

    override suspend fun onStart(runtime: MachineRuntime) {
        job = runtime.reactor.launch {
            try {
                control(runtime)
            } catch (e: CancellationException) {
                logger.info { "Cancelled" }
            }
            catch (e: Exception) {
                logger.error(e) { "Crashed" }
            }
        }
    }

    override fun shutdown() {
        job?.cancel()
    }

    override fun status() = mapOf("running" to running)
}