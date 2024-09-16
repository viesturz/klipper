package mcu

import MachineDuration
import kotlinx.coroutines.flow.StateFlow
import MachineTime
import Resistance
import Voltage
import machine.impl.Reactor
import mcu.connection.StepQueue
import mcu.impl.McuComponent
import parts.drivers.StepperDriver

typealias McuClock = ULong
typealias McuDuration = Long

interface McuSetup {
    val config: config.McuConfig

    fun addButton(pin: config.DigitalInPin): Button
    fun addDigitalPin(config: config.DigitalOutPin): DigitalOutPin
    fun addAnalogPin(pin: config.AnalogInPin): AnalogInPin
    fun addPwmPin(config: config.DigitalOutPin): PwmPin
    fun addTmcUart(config: config.UartPins): MessageBus
    fun addStepperMotor(config: config.StepperPins, driver: StepperDriver): StepperMotor
//    fun addPulseCounter(pin: config.DigitalInPin): PulseCounter
//    fun addI2C(config: config.I2CPins): MessageBus
//    fun addSpi(config: config.SpiPins): MessageBus
//    fun addUart(config: config.UartPins): MessageBus
//    fun addNeopixel(config: config.DigitalOutPin): Neopixel
//    fun addEndstop(pin: config.DigitalInPin, motors: List<StepperMotor>): Endstop

    suspend fun start(reactor: Reactor): Mcu
}

interface Mcu {
    val config: config.McuConfig
    val state: StateFlow<McuState>
    val components: List<McuComponent>
    val stateReason: String
    /** Generates all commanded moves up to this time. */
    fun flushMoves(time: MachineTime, clearHistoryTime: MachineTime)
    fun shutdown(reason: String)
}

enum class McuState {
    STARTING,
    RUNNING,
    ERROR,
    SHUTDOWN,
}

class NeedsRestartException(msg: String): RuntimeException(msg)
class ConfigurationException(msg: String): RuntimeException(msg)

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
        val config: config.AnalogInPin) {
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
    val dutyCycle: Double
    val cycleTime: MachineDuration
    fun set(time: MachineTime, dutyCycle: Double, cycleTime: MachineDuration? = null)
    fun setNow(dutyCycle: Double, cycleTime: MachineDuration? = null)
}
interface Neopixel{
    val mcu: Mcu
    fun update(color: Int, pos: Int = 0)
}

// Motions
interface Endstop {
    val mcu: Mcu
    val lastState: Boolean
    suspend fun queryState(): Boolean
    // Resets any ongoing homing
    fun resetHome()

    // Wait for the homing trigger
    suspend fun waitForHomingTrigger(
        timeout: McuClock,
        pollInterval: McuDuration,
        samplesToCheck: Int,
        checkInterval: McuDuration,
        stopOnValue: Boolean): TriggerResult
    enum class TriggerResult(val id: UByte) {
        TRIGGERED(id = 1u),
        COMMS_TIMEOUT(id = 2u),
        RESET(id = 3u),
        PAST_END_TIME(id = 4u),
    }
}
interface StepperMotor {
    val mcu: Mcu
    val stepQueue: StepQueue
    val driver: StepperDriver
    // Move one step
    fun step(startTime: MachineTime, direction: Int)
    fun setPosition(time: MachineTime, pos: Long)
    suspend fun getPosition(): Long
}

interface MessageBus{
    val mcu: Mcu
    /** Returns null if the CRC check failed. Need to retry. */
    suspend fun sendReply(data: UByteArray, readBytes:Int, sendTime: MachineTime): UByteArray?
    /** Grants exclusive access to the bus during the transaction. */
    suspend fun <ResultType> transaction(function: suspend () -> ResultType): ResultType
}
