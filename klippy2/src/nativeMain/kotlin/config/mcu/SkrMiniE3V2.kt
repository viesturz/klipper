package config.mcu

import config.McuConfig
import config.McuTemplate
import config.SerialConnection

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