package parts

import config.McuConfig
import config.PartConfig
import mcu.Mcu
import machine.impl.Gcode
import machine.impl.GcodeParams
import machine.impl.MachineTime
import machine.impl.Reactor
import mcu.McuSetup

interface MachinePart<ConfigType: PartConfig> {
    val config: ConfigType

    fun status(time: MachineTime): Map<String, Any> = mapOf()

    // Called when all MCUs are configured and parts components initialized.
    suspend fun onStart(runtime: MachineRuntime){}

    // Called when printer session is over and it enters idle state.
    fun onSessionEnd(){}
    fun shutdown(){}
}

interface MachineSetup {
    /** Creates or retrieves an MCU from the config. */
    fun acquireMcu(config: McuConfig): McuSetup
    /** Creates or retrieves another part from it's config. */
    fun acquirePart(config: PartConfig): MachinePart<PartConfig>
    fun registerCommand(command: String, handler: (params: GcodeParams) -> Unit)
    fun registerMuxCommand(command: String, muxParam: String, muxValue: String, handler: (params: GcodeParams) -> Unit)
}

interface MachineRuntime {
    val reactor: Reactor
    val gcode: Gcode
}
