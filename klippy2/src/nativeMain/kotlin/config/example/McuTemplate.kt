package config.example

import config.*

class BigtreetchM8P(serial: String?=null, canbus: String?=null): McuTemplate(McuConfig(serial, canbus)) {
    val temp0 = analogPin(pin="50")
    val temp1 = analogPin(pin="51")
    val temp2 = analogPin(pin="52")
    val temp3 = analogPin(pin="53")
    val temp4 = analogPin(pin="54")
    val fan1 = pwmPin(pin="60")
    val fan2 = pwmPin(pin="61")
    val fan3 = pwmPin(pin="63")
    val heaterBed = pwmPin(pin="63")
    val heaterE0 = pwmPin(pin="64")
    val heaterE1 = pwmPin(pin="65")
    val stepper0 = stepperPins(dirPin=digitalOutPin("10"), stepPin=digitalOutPin("11"), enablePin=digitalOutPin("12"))
    val stepper1 = stepperPins(dirPin=digitalOutPin("10"), stepPin=digitalOutPin("11"), enablePin=digitalOutPin("12"))
    val stepper2 = stepperPins(dirPin=digitalOutPin("10"), stepPin=digitalOutPin("11"), enablePin=digitalOutPin("12"))
    val stepper3 = stepperPins(dirPin=digitalOutPin("10"), stepPin=digitalOutPin("11"), enablePin=digitalOutPin("12"))
    val stepper4 = stepperPins(dirPin=digitalOutPin("10"), stepPin=digitalOutPin("11"), enablePin=digitalOutPin("12"))
    val stepper5 = stepperPins(dirPin=digitalOutPin("10"), stepPin=digitalOutPin("11"), enablePin=digitalOutPin("12"))
    val stepper6 = stepperPins(dirPin=digitalOutPin("10"), stepPin=digitalOutPin("11"), enablePin=digitalOutPin("12"))
    val stepper7 = stepperPins(dirPin=digitalOutPin("10"), stepPin=digitalOutPin("11"), enablePin=digitalOutPin("12"))
    val endstop0 = digitalPin(pin="30")
    val endstop1 = digitalPin(pin="31")
    val endstop2 = digitalPin(pin="32")
    val endstop3 = digitalPin(pin="33")
    val endstop4 = digitalPin(pin="34")
    val endstop5 = digitalPin(pin="35")
}
