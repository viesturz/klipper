package parts

import config.McuConfig
import config.PartConfig
import mcu.Mcu
import machine.impl.Gcode
import machine.impl.ReactorTime
import machine.impl.Reactor

interface MachinePart {
    val partConfig: PartConfig

    fun status(time: ReactorTime): Map<String, Any> = mapOf()

    // Called when printer session is over and it enters idle state.
    fun onSessionEnd(){}
    fun onShutdown(){}
}

data class MachineRuntime (
    val reactor: Reactor,
    val gcode: Gcode,
    val mcus: HashMap<McuConfig, Mcu>,
)