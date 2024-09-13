package config

import celsius
import config.mcus.SkrMiniE3V2
import machine.MachineBuilder
import ohms
import parts.AdcTemperatureSensor
import parts.Heater
import parts.Fan
import parts.GCodeMove
import parts.GCodeScript
import parts.HeaterFan
import parts.LinearStepper
import parts.PidCalibrate
import parts.PotSensor
import parts.drivers.TMC2209
import parts.kinematics.CoreXYKinematics
import parts.kinematics.Homing
import parts.kinematics.HomingDirection
import parts.kinematics.LinearRange
import parts.kinematics.LinearSpeed
import parts.kinematics.MotionPlanner
import parts.kinematics.PinTrigger

fun MachineBuilder.buildMachine() {
    val mcu =
        SkrMiniE3V2(serial = "/dev/serial/by-id/usb-Klipper_stm32f103xe_31FFD7053030473538690543-if00")
    Fan(
        name = "partFan",
        pin = mcu.fan1,
    )
    val he0 = Heater(
        name = "extruder",
        pin = mcu.heaterE0,
        sensor = AdcTemperatureSensor(
            name = "extruder",
            pin = mcu.temp0,
            sensor = NTC100K,
            minTemp = 0.celsius,
            maxTemp = 300.celsius,
        ),
        control = PID(kP = 0.05931248913992124, kI = 0.002886204038118223, kD = 0.3047230307967899)
    )
    HeaterFan("extruder fan control", he0, Fan(
        name = "fan0",
        pin = mcu.fan0.copy(shutdownValue = 1.0),
        maxPower = 0.6,
    ))

    val potSensor = PotSensor(
        name = "POT",
        pin = mcu.bedTemp.copy(reportInterval = 0.1, sampleCount = 4u),
        minResistance = 3.ohms,
        maxResistance = 9_855.ohms,
    )
//
//    val aDriver = TMC2209(
//        name = "driverX",
//        pins = mcu.stepper0Uart,
//        microsteps = 16,
//        runCurrent = 0.32,
//        senseResistor = 0.110.ohms
//    )
//    val bDriver = TMC2209(
//        name = "driverY",
//        pins = mcu.stepper1Uart,
//        microsteps = 16,
//        runCurrent = 0.32,
//        senseResistor = 0.110.ohms
//    )
//    val zDriver = TMC2209(
//        name = "driverZ",
//        pins = mcu.stepper2Uart,
//        microsteps = 16,
//        runCurrent = 0.32,
//        senseResistor = 0.110.ohms
//    )
//    val eDriver = TMC2209(
//        name = "driverE",
//        pins = mcu.stepper3Uart,
//        microsteps = 16,
//        runCurrent = 0.32,
//        senseResistor = 0.110.ohms
//    )
//    val aStepper = LinearStepper(
//        name = "stepperA",
//        pins = mcu.stepper0,
//        driver = aDriver,
//        rotationDistance = 40.0,
//        speed = LinearSpeed(maxSpeed = 400.0)
//    )
//    val bStepper = LinearStepper(
//        name = "stepperB",
//        pins = mcu.stepper1,
//        driver = bDriver,
//        rotationDistance = 40.0,
//        speed = LinearSpeed(maxSpeed = 400.0)
//    )
//    val zStepper = LinearStepper(
//        name = "stepperE",
//        pins = mcu.stepper2,
//        driver = zDriver,
//        rotationDistance = 40.0,
//        speed = LinearSpeed(maxSpeed = 40.0, accel = 100.0),
//        range = LinearRange(
//            positionMin = 0.0,
//            positionMax = 125.0),
//        homing = Homing(
//            endstopPosition = 122.0,
//            endstopTrigger = PinTrigger(pin = mcu.endstop2.copy(invert = true, pullup = 1)),
//            direction = HomingDirection.MAX,
//            speed = 20.0,
//            secondSpeed = 3.0,
//            retractDist = 3.0,
//        )
//    )
//    val eStepper = LinearStepper(
//        name = "stepperE",
//        pins = mcu.stepper3,
//        driver = eDriver,
//        rotationDistance = 50.0,
//        gearRatio = 1.0 / 5,
//    )
//    val xyAxis = CoreXYKinematics(
//        a = aStepper,
//        b = bStepper,
//        xRange = LinearRange(
//            positionMin = 0.0,
//            positionMax = 125.0,
//        ),
//        xHoming = Homing(
//            endstopPosition = 120.0,
//            endstopTrigger = PinTrigger(mcu.endstop0),
//            direction = HomingDirection.MAX,
//            speed = 20.0,
//            secondSpeed = 3.0,
//            retractDist = 3.0
//        ),
//        yRange = LinearRange(
//            positionMin = 0.0,
//            positionMax = 125.0,
//        ),
//        yHoming = Homing(
//            endstopPosition = 120.0,
//            endstopTrigger = PinTrigger(mcu.endstop1),
//            direction = HomingDirection.MAX,
//            speed = 20.0,
//            secondSpeed = 3.0,
//            retractDist = 3.0
//        ),
//        xSpeed = LinearSpeed(maxSpeed = 300.0, accel = 8000.0),
//        ySpeed = LinearSpeed(maxSpeed = 300.0, accel = 8000.0),
//    )
//    GCodeMove(
//        planner = MotionPlanner {
//            axis("xy", xyAxis)
//            axis('z', zStepper)
//            axis('e', eStepper)
//        },
//        speedAxis = "xyz"
//    )

    GCodeScript("PRINT_START") { params ->
        val bedTemp = params.getInt("BED_TEMP", 0)
        val nozzleTemp = params.getInt("TEMP", 0)
        gcode("M140 S$bedTemp")
        gcode("G28")
        gcode("M109 S$nozzleTemp")
    }
}
