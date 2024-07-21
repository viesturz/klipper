package parts

import MachineTime

class Button(override val config: config.Button, setup: MachineSetup): MachinePartLifecycle, MachinePart {
    private val button = setup.acquireMcu(config.pin.mcu).addButton(config.pin)

    override suspend fun onStart(runtime: MachineRuntime) {
        button.setListener {
            if (button.pressed) {
                println("Button ${config.name} clicked")
                config.onClicked(runtime)
            }
        }
    }

    override fun status(time: MachineTime): Map<String, Any> = mapOf("pressed" to button.pressed)
}
