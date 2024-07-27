package config
import celsius
import config.mcu.SkrMiniE3V2
import machine.MachineBuilder
import parts.AdcTemperatureSensor
import parts.Heater
import parts.Fan
import parts.HeaterFan

fun MachineBuilder.buildMachine() {
    val mcu = SkrMiniE3V2(serial="/dev/serial/by-id/usb-Klipper_stm32f103xe_31FFD7053030473538690543-if00")
    val fan0 = Fan(
        name = "fan0",
        pin = mcu.fan0,
    )
    val fan1 = Fan(
        name = "fan1",
        pin = mcu.fan1,
    )

    val e0 = Heater(
        name = "extruder",
        pin = mcu.heaterE0,
        sensor = AdcTemperatureSensor(
            name = "extruder",
            pin = mcu.temp0,
            sensor = NTC100K,
            minTemp = 0.celsius,
            maxTemp = 300.celsius,
        ),
        control = PID(
            kP = 10.2,
            kI = 2.0,
            kD = 100.0,)
    )
    HeaterFan("extruder fan control", e0, fan1)

//    val stepperE0 = LinearStepper(
//        name = "e0 stepper",
//        pins = mcu.stepper3,
//        stepsPerRotation = 200,
//        rotationDistance = 50.0,
//        gearRatio = 1.0/5,
//        driver = TMC2209(
//            name = "e0 driver",
//            pins = mcu.stepper3Uart,
//            microsteps = 16,
//            runCurrent = 0.65,
//            idleCurrent = 0.1,
//            stealthchopTreshold = 999999,
//        )
//    )
}

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
