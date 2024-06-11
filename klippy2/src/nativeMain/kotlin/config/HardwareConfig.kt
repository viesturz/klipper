package config

data class McuConfig(val serial: String? = null, val canbus: String? = null)

// Base pin configs
data class DigitalInPin(val mcu: McuConfig, val pin: String, val invert: Boolean = false, val pullup: Int = 0, val shared: Boolean = false)
data class DigitalOutPin(val mcu: McuConfig, val pin: String, val invert: Boolean = false)
data class AnalogInPin(val mcu: McuConfig, val pin: String)
data class AnalogOutPin(val mcu: McuConfig, val pin: String)
data class PwmPin(val mcu: McuConfig, val pin: String, val invert: Boolean = false, val cycleTime: Float = 0.01f, val hardwarePwm: Boolean = false)

// Composite 
data class StepperPins(val mcu: McuConfig, val enablePin: DigitalOutPin, val stepPin: DigitalOutPin, val dirPin: DigitalOutPin)
data class I2CPins(val mcu: McuConfig, val csPin: DigitalOutPin, val clkPin: DigitalOutPin, val mosiPin: DigitalOutPin, val misoPin: DigitalOutPin)
data class SpiPins(val mcu: McuConfig, val csPin: DigitalOutPin, val clkPin: DigitalOutPin, val mosiPin: DigitalOutPin, val misoPin: DigitalOutPin)
