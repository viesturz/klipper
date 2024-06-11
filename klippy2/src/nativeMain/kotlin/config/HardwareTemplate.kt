package config

// Helper class to define MCU templates
open class McuTemplate(val mcu: McuConfig) {
    fun analogPin(pin: String)= AnalogInPin(mcu, pin)
    fun digitalPin(pin: String)= DigitalInPin(mcu, pin)
    fun pwmPin(pin: String, invert: Boolean = false) = PwmPin(mcu, pin, invert)
    fun digitalOutPin(pin: String, invert: Boolean = false) = DigitalOutPin(mcu, pin, invert)
    fun analogOutPin(pin: String) = AnalogOutPin(mcu, pin)
    
    fun stepperPins(enablePin: DigitalOutPin, stepPin: DigitalOutPin, dirPin: DigitalOutPin) = StepperPins(mcu, enablePin, stepPin, dirPin)
}