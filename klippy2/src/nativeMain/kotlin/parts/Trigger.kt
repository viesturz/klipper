package parts

import config.DigitalInPin
import MachineBuilder
import PartLifecycle
import parts.kinematics.HomingMove

interface Trigger {
    suspend fun state(): Boolean
    fun setupHomingMove(move: HomingMove)
}

fun MachineBuilder.PinTrigger(
    pin: DigitalInPin,
): Trigger = PinTriggerImpl("PinTrigger ${pin.pin}", pin, this).also { addPart(it) }

class PinTriggerImpl(
    override val name: String,
    pinConfig: DigitalInPin,
    setup: MachineBuilder): Trigger, PartLifecycle {
    val mcuEndstop = setup.setupMcu(pinConfig.mcu).addEndstop(pinConfig)

    override suspend fun state(): Boolean = mcuEndstop.queryState()
    override fun setupHomingMove(move: HomingMove) = move.addEndstop(mcuEndstop)
}
