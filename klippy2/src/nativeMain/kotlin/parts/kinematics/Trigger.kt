package parts.kinematics

import config.DigitalInPin
import machine.MachineBuilder

interface Trigger {
    val triggered: Boolean
    suspend fun waitForTrigger(expect: Boolean = true)
}

fun MachineBuilder.PinTrigger(
    pin: DigitalInPin,
): Trigger {
    TODO()
}
