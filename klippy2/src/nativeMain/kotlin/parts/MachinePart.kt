package parts

import config.McuConfig
import config.PartConfig
import machine.CommandQueue
import machine.QueueManager
import machine.impl.GCode
import machine.impl.GcodeParams
import MachineTime
import Temperature
import config.TemperatureSensorPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import machine.impl.Reactor
import mcu.McuSetup

typealias GCodeHandler = (queue: CommandQueue, params: GcodeParams) -> Unit

/** API for parts interacting with other parts */
interface MachinePart {}

interface TemperatureSensor: MachinePart {
    data class Measurement(val time: MachineTime, val temp: Temperature)
    val value: StateFlow<Measurement>
}

/** API for part lifecycle management. */
interface MachinePartLifecycle {
    val config: PartConfig

    fun status(time: MachineTime): Map<String, Any> = mapOf()

    // Called when all MCUs are configured and parts components initialized.
    suspend fun onStart(runtime: MachineRuntime){}

    // Called when printer session is over and it enters idle state.
    fun onSessionEnd(){}
    fun shutdown(){}
}

/** API available at part setup time. */
interface MachineSetup {
    /** Creates or retrieves an MCU from the config. */
    fun acquireMcu(config: McuConfig): McuSetup
    /** Creates or retrieves another part from it's config. */
    fun acquirePart(config: PartConfig): MachinePart
    fun acquirePart(config: TemperatureSensorPart): TemperatureSensor
    fun registerCommand(command: String, handler: GCodeHandler)
    fun registerMuxCommand(command: String, muxParam: String, muxValue: String, handler: GCodeHandler)
}

/** API available at the run time.  */
interface MachineRuntime {
    val scope: CoroutineScope
    val reactor: Reactor
    val gCode: GCode
    val queueManager: QueueManager
}
