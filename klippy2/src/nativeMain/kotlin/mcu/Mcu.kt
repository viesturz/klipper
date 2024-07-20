package mcu

import kotlinx.coroutines.flow.StateFlow
import machine.impl.MachineTime
import machine.impl.Reactor
import mcu.impl.McuComponent

typealias McuClock = ULong
typealias McuDuration = Long

interface McuSetup {
    val config: config.McuConfig

    fun addButton(pin: config.DigitalInPin): Button
    fun addPulseCounter(pin: config.DigitalInPin): PulseCounter
    fun addDigitalOutPin(config: config.DigitalOutPin): DigitalOutPin
    fun addPwmPin(config: config.DigitalOutPin): PwmPin
    fun addI2C(config: config.I2CPins): I2CBus
    fun addSpi(config: config.SpiPins): SPIBus
    fun addNeopixel(config: config.DigitalOutPin): Neopixel
    fun addStepperMotor(config: config.StepperPins): StepperMotor
    fun addEndstop(pin: config.DigitalInPin, motors: List<StepperMotor>): Endstop

    suspend fun start(reactor: Reactor): Mcu
}

interface Mcu {
    val config: config.McuConfig
    val state: StateFlow<McuState>
    val components: List<McuComponent>
    val stateReason: String
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

// Outputs
interface DigitalOutPin {
    val mcu: Mcu
    var value: Boolean
}
interface PulseCounter {
    val mcu: Mcu
    val count: Int
}
interface PwmPin{
    val mcu: Mcu
    val dutyCycle: Float
    val cycleTime: Float
    fun set(time: MachineTime, dutyCycle: Float, cycleTime: Float? = null)
    fun setNow(dutyCycle: Float, cycleTime: Float? = null)
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
    fun resetClock()
    // Move number of steps, with interval ticks between, modify interval on each step by add.
    fun queueMove(steps: Int, interval: McuDuration, add: McuDuration)
    suspend fun getPosition()
}

// Buses
interface I2CBus{
    val mcu: Mcu
    fun send(data: Array<Byte>)
    suspend fun read(): Array<Byte>
}
interface SPIBus{
    val mcu: Mcu
    fun send(data: Array<Byte>)
    suspend fun read(): Array<Byte>
}
