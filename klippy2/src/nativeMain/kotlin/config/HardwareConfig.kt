package config

import MachineDuration
import Resistance
import Voltage
import ohms
import kotlin.math.min

sealed interface Connection
data class SerialConnection(val file: String, val baud: Int = 250000): Connection
data class CanbusConnection(val uid: String, val iface: String = "can0"): Connection

data class McuConfig(val connection: Connection, val name: String)

// Base pin configs
data class DigitalInPin(val mcu: McuConfig, val pin: String, val invert: Boolean = false, val pullup: Int = 0, val shared: Boolean = false)
data class DigitalOutPin(val mcu: McuConfig,
                         val pin: String,
                         val invert: Boolean = false,
                         val startValue: Double = 0.0,
                         val shutdownValue: Double = 0.0,
                         // Emergency shutdown, if the pin is left on without further commands from MCU. Set to 0 to disable.
                         // Also applies to PWM, where on is anything > 0
                         val watchdogDuration: MachineDuration = 2.0,
                         val hardwarePwm: Boolean = false,
                         val cycleTime: MachineDuration = 0.01) {
    init {
        require(startValue in (0.0..1.0))
        if (!hardwarePwm) {
            require(shutdownValue == 0.0 || shutdownValue == 1.0)
        }
        require(watchdogDuration in (0.0..5.0)) { "watchdogDuration $watchdogDuration out of range" }
        if (watchdogDuration > 0) require(cycleTime in (0.001..watchdogDuration))  { "cycleTime longer than watchdogDuration" }
    }
}
data class AnalogInPin(
    val mcu: McuConfig,
    val pin: String,
    val pullupResistor: Resistance = 4_700.ohms,
    val inlineResistor: Resistance = 0.ohms,
    val referenceVoltage: Voltage = 5.0,
    val minValue: Double = 0.0,
    val maxValue: Double = 1.0,
    val reportInterval: MachineDuration = 0.300,
    val sampleInterval: MachineDuration = 0.001,
    val sampleCount: UInt = 8u,
    val rangeCheckCount: UByte = 4u) {
    fun toVoltage(v: Double) = v * referenceVoltage
    fun fromVoltage(v: Voltage) = v / referenceVoltage
    fun toResistance(v:Double) = v * pullupResistor/(1.0-min(v, 0.99999)) - inlineResistor
    fun fromResistance(r: Resistance) = (r + inlineResistor) / (r + inlineResistor + pullupResistor)
}

data class AnalogOutPin(val mcu: McuConfig, val pin: String)

// Composite 
data class StepperPins(val mcu: McuConfig, val enablePin: DigitalOutPin, val stepPin: DigitalOutPin, val dirPin: DigitalOutPin)
data class UartPins(val mcu: McuConfig, val uartPin: DigitalOutPin, val txPin: DigitalOutPin, val address: Int)
data class I2CPins(val mcu: McuConfig, val csPin: DigitalOutPin, val clkPin: DigitalOutPin, val mosiPin: DigitalOutPin, val misoPin: DigitalOutPin)
data class SpiPins(val mcu: McuConfig, val csPin: DigitalOutPin, val clkPin: DigitalOutPin, val mosiPin: DigitalOutPin, val misoPin: DigitalOutPin)

// Helper class to define MCU templates
open class McuTemplate(val mcu: McuConfig) {
    fun analogPin(pin: String)= AnalogInPin(mcu, pin)
    fun digitalPin(pin: String)= DigitalInPin(mcu, pin)
    fun digitalOutPin(pin: String, invert: Boolean = false) = DigitalOutPin(mcu, pin, invert)
    fun analogOutPin(pin: String) = AnalogOutPin(mcu, pin)

    fun stepperPins(enablePin: DigitalOutPin, stepPin: DigitalOutPin, dirPin: DigitalOutPin) = StepperPins(mcu, enablePin, stepPin, dirPin)
    fun uartPins(uartPin: DigitalOutPin, txPin: DigitalOutPin, address: Int) = UartPins(mcu, uartPin, txPin, address)
}