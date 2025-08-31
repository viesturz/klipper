package mcu

import MachineDuration
import MachineTime
import machine.Reactor
import McuClock
import mcu.connection.CommandQueue
import mcu.connection.StepQueueImpl
import kotlin.reflect.KClass

/** McuComponent controls a specific hardware feature in the MCU
 * It's tightly coupled with the MCU code and provides an API to the low level
 * communication protocol.
 *  */
interface McuComponent {
    /** Run to configure the part, with basic MCU identification data available. */
    fun configure(configure: McuConfigure){}
    /** Run when MCU is configured and time synchronized, can send initial commands. */
    suspend fun start(runtime: McuRuntime){}
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
    fun addComponent(component: McuComponent)
    fun makeCommandQueue(name:String, numCommands: Int): CommandQueue
    /** Create a new stepper command queue */
    fun makeStepQueue(id: ObjectId): StepQueueImpl
    /** Add a configuration command for the MCU */
    fun configCommand(command: McuCommand)
    /** On soft restart, restart commands are used instead of configuration. */
    fun restartCommand(command: McuCommand)
    /** Add initialization command to be run right after configuration is done */
    fun initCommand(command: McuCommand)
    /** Add a query command, run after all initialization commands to start querying sensors. */
    fun queryCommand(block: (clock: McuClock32)-> McuCommand)
    /** Add a handler for an event sent by the MCU. */
    fun <ResponseType: McuResponse> responseHandler(response: KClass<ResponseType>, id: ObjectId, handler: suspend (message: ResponseType) -> Unit)
    fun durationToClock(durationSeconds: MachineDuration) = firmware.durationToTicks(durationSeconds)
}

interface McuRuntime {
    val firmware: FirmwareConfig
    val reactor: Reactor
    val defaultQueue: CommandQueue

    fun durationToClock(duration: MachineDuration): McuClock32
    fun timeToClock(time: MachineTime): McuClock
    fun timeToClock32(time: MachineTime): McuClock32
    fun clockToTime(clock: McuClock32): MachineTime
    fun clock32ToClock(clock: McuClock32): McuClock
    /** Add a handler for an event sent by the MCU. */
    fun <ResponseType: McuResponse> responseHandler(response: KClass<ResponseType>, id: ObjectId, handler: suspend (message: ResponseType) -> Unit)
}

inline fun <reified ResponseType: McuResponse> McuRuntime.responseHandler(id: ObjectId, noinline handler: suspend (message: ResponseType) -> Unit) = responseHandler<ResponseType>(ResponseType::class, id, handler)
inline fun <reified ResponseType: McuResponse> McuConfigure.responseHandler(id: ObjectId, noinline handler: suspend (message: ResponseType) -> Unit) = responseHandler<ResponseType>(ResponseType::class, id, handler)
