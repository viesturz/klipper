package machine.impl

import config.MachineConfig
import config.McuConfig
import config.PartConfig
import machine.Machine
import machine.MachineState
import mcu.Mcu
import mcu.impl.McuImpl
import parts.MachinePart
import parts.MachineRuntime
import parts.buildPart

class MachineImpl(val config: MachineConfig) : Machine {
    var _state = MachineState.INITIALIZING
    override val state: MachineState
        get() = _state

    val reactor = Reactor()
    val parts = ArrayList<MachinePart>()
    val partsMap = HashMap<PartConfig, MachinePart>()
    val mcus = HashMap<McuConfig, Mcu>()
    val gcode = Gcode()

    override fun setup() {
        setupParts()
    }

    override suspend fun run() {
        _state = MachineState.RUNNING
        reactor.runEventLoop()
        _state = MachineState.SHUTDOWN
        shutdownParts()
    }

    override fun shutdown(reason: String) {
        reactor.shutdown()
    }

    override fun runGcode(cmd: String) = gcode.run(cmd)

    override val status: Map<String, String>
        get() = buildMap {
            val time = reactor.curTime
            for (part in parts) {
                put(part.partConfig.name, part.status(time).toString())
            }
        }

    private fun setupParts() {
        val setup = MachineRuntime(
            reactor = reactor,
            gcode = gcode,
            mcus = mcus,
        )
        for (p in config.parts) acquirePart(p, setup)
    }

    private fun shutdownParts() {
        for (p in parts) p.onShutdown()
    }

    fun acquirePart(partConfig: PartConfig, setup: MachineRuntime) = partsMap.getOrElse(partConfig) {
        val p = buildPart(partConfig, setup)
        parts.add(p)
        partsMap[partConfig] = p
        p
    }
}