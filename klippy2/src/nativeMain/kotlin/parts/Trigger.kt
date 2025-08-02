package parts

import EndstopSyncBuilder
import config.DigitalInPin
import MachineBuilder
import PartLifecycle

interface Trigger {
    suspend fun state(): Boolean
    fun setupTriggerSync(sync: EndstopSyncBuilder)
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
    override fun setupTriggerSync(sync: EndstopSyncBuilder) = sync.addEndstop(mcuEndstop)
}
