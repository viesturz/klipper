package parts

import machine.MachineBuilder
import machine.MachineRuntime
import machine.impl.PartLifecycle

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
    var _running = false
    override val running: Boolean
        get() = _running

    override suspend fun onStart(runtime: MachineRuntime) {
        runtime.reactor.launch {
            _running = true
            control(runtime)
        }
    }

    override fun shutdown() {
        _running = false
    }

    override fun status() = mapOf("running" to running)
}