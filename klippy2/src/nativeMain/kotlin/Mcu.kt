import kotlinx.coroutines.flow.StateFlow
import config.DigitalInPin
import config.McuConfig
import config.StepperPins
import config.UartPins
import kotlinx.coroutines.Deferred
import machine.Reactor
import mcu.ObjectId

typealias McuClock = ULong
typealias McuDuration = Long

interface McuSetup {
    val config: McuConfig

    fun addButton(pin: DigitalInPin): Button
    fun addDigitalPin(config: config.DigitalOutPin): DigitalOutPin
    fun addAnalogPin(pin: config.AnalogInPin): AnalogInPin
    fun addPwmPin(config: config.DigitalOutPin): PwmPin
    fun addTmcUart(config: UartPins): MessageBus
    fun addStepperMotor(config: StepperPins, driver: StepperDriver): StepperMotor
    fun addEndstop(pin: config.DigitalInPin): Endstop
    fun addEndstopSync(block:  (EndstopSyncBuilder) -> Unit): EndstopSync

    //    fun addPulseCounter(pin: config.DigitalInPin): PulseCounter
//    fun addI2C(config: config.I2CPins): MessageBus
//    fun addSpi(config: config.SpiPins): MessageBus
//    fun addUart(config: config.UartPins): MessageBus
//    fun addNeopixel(config: config.DigitalOutPin): Neopixel

    suspend fun start(reactor: Reactor): Mcu
}

interface Mcu {
    val config: McuConfig
    val state: StateFlow<McuState>
    val stateReason: String
    /** Generates all commanded moves up to this time. */
    fun flushMoves(time: MachineTime, clearHistoryTime: MachineTime)
    fun shutdown(reason: String, emergency: Boolean = false)

    /** Builds a new synchronization between endstops and motors.
     * At runtime a limited number of syncs are available, so they need to be released after use.*/
    fun addEndstopSync(block:  (EndstopSyncBuilder) -> Unit): EndstopSync
}

enum class McuState {
    STARTING,
    RUNNING,
    ERROR,
    SHUTDOWN,
}

// Inputs
typealias ButtonListener = suspend (b: Button) -> Unit
interface Button {
    val mcu: Mcu
    val pressed: Boolean
    fun setListener(handler: ButtonListener?)
}

interface AnalogInPin {
    val mcu: Mcu
    /** Value in range 0..1 */
    val value: Measurement
    fun setListener(handler: suspend (m: Measurement) -> Unit)

    data class Measurement(
        val time: MachineTime,
        // Raw ADC value in the range of 0..1
        val value: Double,
        val config: config.AnalogInPin
    ) {
        val voltage: Voltage
            get() = config.toVoltage(value)
        val resistance: Resistance
            get()  = config.toResistance(value)
    }
}

// Outputs
interface DigitalOutPin {
    val mcu: Mcu
    val value: Boolean
    fun set(time: MachineTime, value: Boolean)
    fun setNow(value: Boolean)
}
interface PulseCounter {
    val mcu: Mcu
    val count: Int
}
interface PwmPin{
    val mcu: Mcu
    // Duty cycle, 0..1 range
    val dutyCycle: Double
    // Milliseconds each cycle takes
    val cycleTime: MachineDuration
    fun set(time: MachineTime, dutyCycle: Double, cycleTime: MachineDuration? = null)
    fun setNow(dutyCycle: Double, cycleTime: MachineDuration? = null)
}

interface MessageBus{
    val mcu: Mcu
    /** Returns null if the CRC check failed. Need to retry. */
    suspend fun sendReply(data: UByteArray, readBytes:Int, sendTime: MachineTime): UByteArray?
    /** Grants exclusive access to the bus during the transaction. */
    suspend fun <ResultType> transaction(function: suspend () -> ResultType): ResultType
}

// Motions
interface StepperDriver {
    val microsteps: Int
    val stepBothEdges: Boolean
    val pulseDuration: Double
    val enabled: Boolean
    suspend fun setEnabled(time: MachineTime, enabled: Boolean)
}

interface StepperMotor {
    val mcu: Mcu
    val stepQueue: StepQueue
    val driver: StepperDriver
    fun setPosition(time: MachineTime, pos: Long)
    suspend fun getPosition(): Long

    interface StepQueue
}

interface Endstop {
    val mcu: Mcu
    val id: ObjectId
    val lastState: Boolean
    suspend fun queryState(): Boolean
}

interface EndstopSyncBuilder {
    fun addEndstop(endstop: Endstop)
    fun addStepperMotor(motor: StepperMotor)
}

/** Synchronizes motor movement with one or more endstops.
 * When active, the motors will automatically stop when an endstop is triggered.
 * Supports cross-MCU endstops and motors.
 * */
interface EndstopSync {
    val state: StateFlow<State>

    /** Initiates the endstop sync and returns a deferred trigger result. */
    suspend fun start(startTime: MachineTime, timeoutTime: MachineTime): Deferred<State>

    /** Resets any ongoing triggering. Allows stopped motors to move again. */
    suspend fun reset()
    /* Releases the sync resources, it cannot be used anymore after this. */
    suspend fun release()

    sealed interface State
    object StateIdle: State
    object StateRunning: State
    data class StateTriggered(val triggerTime: MachineTime): State
    object StateAlreadyTriggered: State
    object StateReleased: State
    object StatePastEndTime: State
    object StateReset: State
    object StateCommsTimeout: State
}

// Other stuff
interface Neopixel{
    val mcu: Mcu
    fun update(color: Int, pos: Int = 0)
}
