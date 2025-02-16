import config.NTC100K
import config.PID
import config.mcus.SkrMiniE3V2
import parts.*
import parts.drivers.TMC2209
import parts.kinematics.LinearRange
import parts.kinematics.LinearSpeeds
import parts.kinematics.MotionPlanner

fun MachineBuilder.buildMachine() {
    val mcu =
        SkrMiniE3V2(serial = "/dev/serial/by-id/usb-Klipper_stm32f103xe_31FFD7053030473538690543-if00")
    val fan1 = Fan(pin = mcu.fan1)
    val he0 = Heater(
        pin = mcu.heaterE0,
        sensor = AdcTemperatureSensor(
            pin = mcu.tempE0,
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
    val aDriver = TMC2209(
        pins = mcu.stepper0Uart,
        enablePin = mcu.stepper0.enablePin,
        microsteps = 16,
        runCurrent = 0.32,
        senseResistor = 0.110.ohms
    )
    val bDriver = TMC2209(
        pins = mcu.stepper1Uart,
        enablePin = mcu.stepper1.enablePin,
        microsteps = 16,
        runCurrent = 0.32,
        senseResistor = 0.110.ohms
    )
    val zDriver = TMC2209(
        pins = mcu.stepper2Uart,
        enablePin = mcu.stepper2.enablePin,
        microsteps = 1,
        interpolate = true,
        runCurrent = 0.42,
        senseResistor = 0.110.ohms
    )
    val eDriver = TMC2209(
        pins = mcu.stepper3Uart,
        enablePin = mcu.stepper3.enablePin,
        microsteps = 16,
        runCurrent = 0.32,
        senseResistor = 0.110.ohms
    )
    val aStepper = LinearStepper(
        pins = mcu.stepper0,
        driver = aDriver,
        rotationDistance = 40.0,
        speed = LinearSpeeds(speed = 400.0)
    )
    val bStepper = LinearStepper(
        pins = mcu.stepper1,
        driver = bDriver,
        rotationDistance = 40.0,
        speed = LinearSpeeds(speed = 400.0)
    )
    val zStepper = LinearStepper(
        pins = mcu.stepper2,
        driver = zDriver,
        stepsPerRotation = 200,
        rotationDistance = 40.0,
        speed = LinearSpeeds(speed = 40.0, accel = 100.0),
        range = LinearRange(
            positionMin = 0.0,
            positionMax = 125.0),
//        homing = Homing(
//            endstopPosition = 122.0,
//            endstopTrigger = PinTrigger(pin = mcu.endstop2.copy(invert = true, pullup = 1)),
//            direction = HomingDirection.MAX,
//            speed = 20.0,
//            secondSpeed = 3.0,
//            retractDist = 3.0,
//        )
    )

    val eStepper = LinearStepper(
        pins = mcu.stepper3,
        driver = eDriver,
        rotationDistance = 50.0,
        gearRatio = 1.0 / 5,
    )
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
//        xSpeed = LinearSpeeds(maxSpeed = 300.0, accel = 8000.0),
//        ySpeed = LinearSpeeds(maxSpeed = 300.0, accel = 8000.0),
//    )
    GCodeMove(
        motion = MotionPlanner {
//            axis("XY", xyAxis)
            axis('Z', zStepper)
            axis('E', eStepper)
        },
        positionalAxis = "XYZ",
        extrudeAxis = "E",
    )

    GCodeScript("PRINT_START") { params ->
        val bedTemp = params.getInt("BED_TEMP", 0)
        val nozzleTemp = params.getInt("TEMP", 0)
        gcode("M140 S$bedTemp")
        gcode("G28")
        gcode("M109 S$nozzleTemp")
    }

    val potSensor = PotSensor(
        pin = mcu.bedTemp.copy(reportInterval = 0.3, sampleCount = 6u),
        minResistance = 500.ohms,
        maxResistance = 9_500.ohms,
    )
    val servo = Servo(
        pin = mcu.zProbeServo.copy(hardwarePwm = true, cycleTime = 0.01),
        minPulse = 0.000_5,
        maxPulse = 0.002_5,
        minAngle = 0.0,
        maxAngle = 180.0,
    )

    ControlLoop("Fan + POT") {
        potSensor.flow.collect {
            fan1.setSpeed(it.value)
            servo.setAngle(180.0 * it.value)
        }
    }
//    ControlLoop("Servo") {
//        while (true) {
//            servo.setAngle(180.0 * Random.nextDouble())
//            delay(5000)
//        }
//    }
}
