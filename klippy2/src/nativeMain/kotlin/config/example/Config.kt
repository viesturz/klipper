package config.example
import config.*

val mcu = SkrMiniE3V2(serial="/dev/serial/by-id/usb-Klipper_stm32f103xe_31FFD7053030473538690543-if00")
val fan0 = Fan(
    name = "fan0",
    pin = mcu.fan0,
)
val fan1 = Fan(
    name = "fan1",
    pin = mcu.fan1,
)
val tempE0 = AdcTemperatureSensor(
    name = "extruder temp",
    pin = mcu.temp0,
    sensor = NTC100K
)

val machine = MachineConfig(
    parts = listOf(tempE0),
//    parts = listOf(fan0, fan1, tempE0),
)

//
//val extruder = stepperRail(
//    pins = mcu.stepper3,
//    stepsPerRotation = 200,
//    rotationDistance = 50,
//    gearRatio = 1/5,
//    microsteps = 16,
//)
//val hotend = makeHeater {
//    power = mcu.heaterE0
//    sensor = makeTemperatureSensor {
//        type = "ATC Semitec 104NT-4-R025H42G"
//        pin = mcu.temp1
//        smoothTime = 0.3
//    }
//    control = Control.PID
//    pidKp = 38.187
//    pidKi = 4.896
//    pidKd = 74.465
//    minTemp = -10
//    maxTemp = 300
//}
//val bed = makeHeater {
//    power = mcu.heaterBed
//    sensor = makeTemperatureSensor {
//        type = "ATC Semitec 104NT-4-R025H42G"
//        pin = mcu.temp0
//    }
//    control = Control.PID
//    pidKp = 38.187
//    pidKi = 4.896
//    pidKd = 74.465
//    minTemp = -10
//    maxTemp = 120
//}
//
//val axisXY = makeCoreXYAxis {
//    motorA = makeLinearStepper {
//        pins = mcu.stepper0
//        stepsPerRotation = 200
//        rotationDistance = 40
//        microsteps = 16
//    }
//    motorB = makeLinearStepper {
//        pins = mcu.stepper1
//        stepsPerRotation = 200
//        rotationDistance = 40
//        microsteps = 16
//    }
//    rangeX = makeLinearRange {
//        positionMin = 0
//        positionMax = 125
//        positionEndstop = 122
//        endstopTrigger = makePinTrigger(pin = mcu.endstop0, trigger = Trigger.LOW, pullup = true)
//        endstopDir = Direction.MAX
//        homingSpeed = 20
//        secondHomingSpeed = 3
//        homingRetractDist = 3
//    }
//    rangeY = makeLinearRange {
//        positionMin = 0
//        positionMax = 125
//        positionEndstop = 122
//        endstopTrigger = makePinTrigger(pin = mcu.endstop1, trigger = Trigger.LOW, pullup = true)
//        endstopDir = Direction.MAX
//        homingSpeed = 20
//        secondHomingSpeed = 3
//        homingRetractDist = 3
//    }
//}
//val axisZ = makeLinearAxis {
//    motor = makeLinearStepper {
//        pins = mcu.stepper2
//        stepsPerRotation = 200
//        rotationDistance = 40
//        microsteps = 16
//    }
//    range = makeLinearRange {
//        positionMin = 0
//        positionMax = 125
//        positionEndstop = 0.1
//        endstopTrigger = makePinTrigger(pin = mcu.endstop2, trigger = Trigger.LOW, pullup = true)
//        endstopDir = Direction.MIN
//        homingSpeed = 20
//        secondHomingSpeed = 3
//        homingRetractDist = 3
//    }
//}
//
//val printer = makePrinter {
//    axisX = axisXY.axisX
//    axisY = axisXY.axisY
//    axisZ = axisZ
//    extruder = extruder
//    hotend = hotend
//    bedHeater = bed
//}
//
