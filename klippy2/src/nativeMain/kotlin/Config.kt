import config.NTC100K
import config.PID
import config.mcus.SkrMiniE3V2
import kotlinx.coroutines.delay
import parts.*
import parts.drivers.TMC2209
import parts.kinematics.CoreXYKinematics
import parts.kinematics.Homing
import parts.kinematics.HomingDirection
import parts.kinematics.LinearRailActuator
import parts.kinematics.LinearRange
import parts.kinematics.LinearSpeeds
import parts.PinTrigger
import parts.kinematics.CombineLinearStepper
import parts.kinematics.GantryActuator
import parts.kinematics.GantryRail
import parts.kinematics.LinearRail
import parts.kinematics.LinearStepper

fun MachineBuilder.buildMachine() {
    val mcu =
        SkrMiniE3V2(serial = "/dev/serial/by-id/usb-Klipper_stm32f103xe_31FFD7053030473538690543-if00")
    val fan1 = Fan(pin = mcu.fan1, name="partFan", kickStartTime = 0.5)
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
    val bedHeater = Heater(
        pin = mcu.heaterBed,
        sensor = AdcTemperatureSensor(
            pin = mcu.bedTemp,
            sensor = NTC100K,
            minTemp = 0.celsius,
            maxTemp = 120.celsius,
        ),
        control = PID(kP = 63.0, kI = 2.4, kD = 412.0)
    )
    val zStepper = LinearStepper(
        pins = mcu.stepper2,
        driver = TMC2209(
            pins = mcu.stepper2Uart,
            enablePin = mcu.stepper2.enablePin,
            microsteps = 16,
            interpolate = true,
            runCurrent = 0.45,
            senseResistor = 0.110.ohms
        ),
        stepsPerRotation = 200,
        rotationDistance = 40.0,
        speed = LinearSpeeds(speed = 400.0, accel = 500.0),
        range = LinearRange(
            positionMin = 0.0,
            positionMax = 125.0,
        ),
        xHoming = Homing(
            endstopPosition = 120.0,
            endstopTrigger = PinTrigger(mcu.endstop1),
            direction = HomingDirection.INCREASING,
            speed = 20.0,
            secondSpeed = 3.0,
            retractDist = 5.0,
        ),
        yRange = LinearRange(
            positionMin = 0.0,
            positionMax = 125.0,
        ),
        yHoming = Homing(
            endstopPosition = 120.0,
            endstopTrigger = PinTrigger(mcu.endstop0),
            direction = HomingDirection.INCREASING,
            speed = 20.0,
            secondSpeed = 3.0,
            retractDist = 5.0,
        ),
        xSpeed = LinearSpeeds(speed = 300.0, accel = 8000.0),
        ySpeed = LinearSpeeds(speed = 300.0, accel = 8000.0),
    )
//    GCodeMove(
//        motion = MotionPlanner {
//            axis("XY", xyAxis)
//            axis('Z', zStepper)
//            axis('E', eStepper)
//        },
//        positionalAxis = "XYZ",
//        extrudeAxis = "E",
//    )
    GCodePrinter(
        heater = he0,
        partFan = fan1,
        bedHeater = bedHeater,
    )
    GCodeScript("PRINT_START") { params ->
        val bedTemp = params.getInt("BED_TEMP", 0)
        val nozzleTemp = params.getInt("TEMP", 0)
        gcode("M140 S$bedTemp")
        gcode("G28")
        gcode("M109 S$nozzleTemp")
    }

    val potSensor = PotSensor(
        pin = mcu.bedTemp.copy(reportInterval = 0.03, sampleCount = 6u),
        minResistance = 0.ohms,
        maxResistance = 10_700.ohms,
    )
    val servo = Servo(
        pin = mcu.zProbeServo.copy(hardwarePwm = false, cycleTime = 0.01),
        minPulse = 0.000_5,
        maxPulse = 0.002_5,
        minAngle = 0.0,
        maxAngle = 180.0,
    )

    val openDegrees = 180
    val closedDegrees = 83

    ControlLoop {
        potSensor.flow.collect {
            servo.setAngle(closedDegrees + (openDegrees - closedDegrees) * it.value*3)
        }
    }

    ControlLoop { runtime ->
        delay(1000)
        while (true) {
            xyAxis.home(listOf(0,1))
        }
    }

}
