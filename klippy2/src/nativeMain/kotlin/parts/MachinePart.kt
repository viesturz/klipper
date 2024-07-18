package parts

import config.McuConfig
import config.PartConfig
import machine.CommandQueue
import machine.QueueManager
import machine.impl.GCode
import machine.impl.GcodeParams
import machine.impl.MachineTime
import machine.impl.Reactor
import mcu.McuSetup

typealias GCodeHandler = (queue: CommandQueue, params: GcodeParams) -> Unit

/** A part of the machine, can anything. */
interface MachinePart<ConfigType: PartConfig> {
    val config: ConfigType

    fun status(time: MachineTime): Map<String, Any> = mapOf()

    // Called when all MCUs are configured and parts components initialized.
    suspend fun onStart(runtime: MachineRuntime){}

    // Called when printer session is over and it enters idle state.
    fun onSessionEnd(){}
    fun shutdown(){}
}

/** API available at the setup time. */
interface MachineSetup {
    /** Creates or retrieves an MCU from the config. */
    fun acquireMcu(config: McuConfig): McuSetup
    /** Creates or retrieves another part from it's config. */
    fun acquirePart(config: PartConfig): MachinePart<PartConfig>
    fun registerCommand(command: String, handler: GCodeHandler)
    fun registerMuxCommand(command: String, muxParam: String, muxValue: String, handler: GCodeHandler)
}

/** API available at the run time.  */
interface MachineRuntime {
    val reactor: Reactor
    val gCode: GCode
    val queueManager: QueueManager
}
