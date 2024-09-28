package mcu.impl

import MachineDuration
import MachineTime
import machine.Reactor
import mcu.McuClock
import mcu.connection.CommandQueue
import mcu.connection.StepQueueImpl

/** McuComponent controls a specific hardware feature in the MCU
 * It's tightly coupled with the MCU code and provides provides an API to the low level
 * communication protocol.
 *  */
interface McuComponent {
    /** Run to configure the part, with basic MCU identification data available. */
    fun configure(configure: McuConfigure){}
    /** Run when MCU is configured and time synchronized, can send initial commands. */
    fun start(runtime: McuRuntime){}
    fun shutdown(){}
}

/** MCU component configuration interface.
 * Available in the constructor and Configure call.
 */
interface McuConfigure {
    /** MCU self identification data. */
    val firmware: FirmwareConfig
    /** Allocate new object ID. */
    fun makeOid(): ObjectId
    /** Create a new command queue, separate from the step queues.
     *  NumMoves is the number of commands simultaneously queued.
     * Can only be used after start. */
    fun makeCommandQueue(name:String, numCommands: Int): CommandQueue
    /** Create a new stepper command queue */
    fun makeStepQueue(id: ObjectId): StepQueueImpl
    /** Add a configuration command for the MCU */
    fun configCommand(signature: String, block: CommandBuilder.()->Unit)
    /** On soft restart, restart commands are used instead of configuration. */
    fun restartCommand(signature: String, block: CommandBuilder.()->Unit)
    /** Add a initialization command to be run right after configuration is done */
    fun initCommand(signature: String, block: CommandBuilder.()->Unit)
    /** Add a query command, run after all initialization commands to start querying sensors. */
    fun queryCommand(signature: String, block: CommandBuilder.(clock: McuClock32)->Unit)
    /** Add a handler for an event sent by the MCU. */
    fun <ResponseType: McuResponse> responseHandler(parser: ResponseParser<ResponseType>, id: ObjectId, handler: suspend (message: ResponseType) -> Unit)
    fun durationToClock(durationSeconds: MachineDuration) = firmware.durationToTicks(durationSeconds)
}

interface McuRuntime {
    val firmware: FirmwareConfig
    val reactor: Reactor
    val defaultQueue: CommandQueue

    fun durationToClock(duration: MachineDuration): McuClock32
    fun timeToClock(time: MachineTime): McuClock
    fun clockToTime(clock: McuClock32): MachineTime
    /** Add a handler for an event sent by the MCU. */
    fun <ResponseType: McuResponse> responseHandler(parser: ResponseParser<ResponseType>, id: ObjectId, handler: suspend (message: ResponseType) -> Unit)
}
