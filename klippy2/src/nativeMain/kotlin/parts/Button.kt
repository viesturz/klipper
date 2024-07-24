package parts

import config.DigitalInPin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import machine.ActionBlock
import machine.MachineBuilder
import machine.MachinePart
import machine.MachineRuntime
import machine.impl.PartLifecycle

fun MachineBuilder.Button(
    name: String,
    pin: DigitalInPin,
    onClicked: ActionBlock = {}
): Button = ButtonImpl(name, pin, onClicked, this).also { addPart(it) }

interface Button: MachinePart {
    val state: StateFlow<Boolean>
}

private class ButtonImpl(
    override val name: String,
    pinConfig: DigitalInPin,
    val onClicked: ActionBlock,
    setup: MachineBuilder): PartLifecycle, Button {
    private val button = setup.setupMcu(pinConfig.mcu).addButton(pinConfig)
    val _state = MutableStateFlow(false)
    override val state: StateFlow<Boolean>
        get() = _state

    override suspend fun onStart(runtime: MachineRuntime) {
        button.setListener {
            _state.value = button.pressed
            if (button.pressed) {
                println("Button $name clicked")
                onClicked(runtime)
            }
        }
    }
    override fun status(): Map<String, Any> = mapOf("pressed" to button.pressed)
}
