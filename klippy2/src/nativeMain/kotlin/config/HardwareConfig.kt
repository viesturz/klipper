package config

sealed interface Connection
data class SerialConnection(val file: String, val baud: Int = 250000): Connection
data class CanbusConnection(val uid: String, val iface: String = "can0"): Connection

data class McuConfig(val connection: Connection, val name: String)

// Base pin configs
data class DigitalInPin(val mcu: McuConfig, val pin: String, val invert: Boolean = false, val pullup: Int = 0, val shared: Boolean = false)
data class DigitalOutPin(val mcu: McuConfig,
                         val pin: String,
                         val invert: Boolean = false,
                         val startValue: Float = 0f,
                         val shutdownValue: Float = 0f,
                         val maxDuration: Float = 2f,
                         val hardwarePwm: Boolean = false,
                         val cycleTime: Float = 0.01f) {
    init {
        require(startValue in (0f..1f))
        if (!hardwarePwm) {
            require(shutdownValue == 0f || shutdownValue == 1f)
        }
        require(maxDuration in (0f..5f))
        require(cycleTime in (0.001f..maxDuration))
    }
}
data class AnalogInPin(val mcu: McuConfig, val pin: String)
data class AnalogOutPin(val mcu: McuConfig, val pin: String)

// Composite 
data class StepperPins(val mcu: McuConfig, val enablePin: DigitalOutPin, val stepPin: DigitalOutPin, val dirPin: DigitalOutPin)
data class I2CPins(val mcu: McuConfig, val csPin: DigitalOutPin, val clkPin: DigitalOutPin, val mosiPin: DigitalOutPin, val misoPin: DigitalOutPin)
data class SpiPins(val mcu: McuConfig, val csPin: DigitalOutPin, val clkPin: DigitalOutPin, val mosiPin: DigitalOutPin, val misoPin: DigitalOutPin)
