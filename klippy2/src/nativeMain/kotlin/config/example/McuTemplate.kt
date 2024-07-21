package config.example

import config.*

class SkrMiniE3V2(serial: String): McuTemplate(McuConfig(SerialConnection(serial), "SkrMiniE3V2")) {
    val bedTemp = analogPin(pin="PC3")
    val temp0 = analogPin(pin="PA0")
    val fan0 = digitalOutPin(pin="PC6")
    val fan1 = digitalOutPin(pin="PC7")
    val heaterBed = digitalOutPin(pin="PC9")
    val heaterE0 = digitalOutPin(pin="PC8")
    val stepper0 = stepperPins(dirPin=digitalOutPin("PB12"), stepPin=digitalOutPin("PB13"), enablePin=digitalOutPin("PB14", invert = true))
    val stepper1 = stepperPins(dirPin=digitalOutPin("PB2"), stepPin=digitalOutPin("PB10"), enablePin=digitalOutPin("PB11", invert = true))
    val stepper2 = stepperPins(dirPin=digitalOutPin("PC5"), stepPin=digitalOutPin("PB0"), enablePin=digitalOutPin("PB1", invert = true))
    val stepper3 = stepperPins(dirPin=digitalOutPin("PB4"), stepPin=digitalOutPin("PB3"), enablePin=digitalOutPin("PB4", invert = true))
    val endstop0 = digitalPin(pin="PC0")
    val endstop1 = digitalPin(pin="PC1")
    val endstop2 = digitalPin(pin="PC2")
}

class BigtreetchM8P(serial: String): McuTemplate(McuConfig(SerialConnection(serial), "SkrMiniE3V2")) {
    val temp0 = analogPin(pin="50")
    val temp1 = analogPin(pin="51")
    val temp2 = analogPin(pin="52")
    val temp3 = analogPin(pin="53")
    val temp4 = analogPin(pin="54")
    val fan1 = digitalOutPin(pin="60")
    val fan2 = digitalOutPin(pin="61")
    val fan3 = digitalOutPin(pin="63")
    val heaterBed = digitalOutPin(pin="63")
    val heaterE0 = digitalOutPin(pin="64")
    val heaterE1 = digitalOutPin(pin="65")
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
