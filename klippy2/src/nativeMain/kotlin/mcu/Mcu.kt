package mcu

typealias McuClock = ULong
typealias McuDuration = Long

interface Mcu {
    val config: config.McuConfig
    fun addButton(pin: config.DigitalInPin): Button
    fun addPulseCounter(pin: config.DigitalInPin): PulseCounter
    fun addDigitalOutPin(config: config.DigitalOutPin): DigitalOutPin
    fun addPwmPin(config: config.PwmPin): PwmPin
    fun addI2C(config: config.I2CPins): I2CBus
    fun addSpi(config: config.SpiPins): SPIBus
    fun addNeopixel(config: config.DigitalOutPin): Neopixel
    fun addStepperMotor(config: config.StepperPins): StepperMotor
    fun addEndstop(pin: config.DigitalInPin, motors: List<StepperMotor>): Endstop

    fun start()
    fun abort()
}

// Inputs
interface Button {
    val mcu: Mcu
    fun lastValue(): Boolean
    fun addListener(listener: Any, handler: (b:Button) -> Unit)
    fun removeListener(listener: Any)
}

// Outputs
interface DigitalOutPin {
    val mcu: Mcu
    fun setValue(value: Boolean)
}
interface PulseCounter {
    val mcu: Mcu
    val count: Int
}
interface PwmPin{
    val mcu: Mcu
    fun setValue(value: Float)
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
