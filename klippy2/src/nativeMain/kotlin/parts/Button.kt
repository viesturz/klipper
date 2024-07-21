package parts

import machine.impl.Reactor
import MachineTime

class Button(override val config: config.Button, setup: MachineSetup): MachinePartLifecycle, MachinePart {
    private val button = setup.acquireMcu(config.pin.mcu).addButton(config.pin)
    private var reactor: Reactor? = null

    override suspend fun onStart(runtime: MachineRuntime) {
        reactor = runtime.reactor
        button.setListener {
            if (button.pressed) {
                println("Button ${config.name} clicked")
                reactor?.runNow {
                    config.onClicked(runtime)
                }
            }
        }
    }

    override fun status(time: MachineTime): Map<String, Any> = mapOf("pressed" to button.pressed)
}
