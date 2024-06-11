val mcu = object {
    val temp0 = analogPin(pin=50)
    val temp1 = analogPin(pin=51)
    val temp2 = analogPin(pin=52)
    val temp3 = analogPin(pin=53)
    val temp4 = analogPin(pin=54)
    val fan1 = pwmPin(pin=60)
    val fan2 = pwmPin(pin=61)
    val fan3 = pwmPin(pin=63)
    val heaterBed = pwmPin(pin=63)
    val heaterE0 = pwmPin(pin=64)
    val heaterE1 = pwmPin(pin=65)
    val stepper0 = stepperPins(dirPin=10, stepPin=11, enablePin=9)
    val stepper1 = stepperPins(dirPin=10, stepPin=11, enablePin=9)
    val stepper2 = stepperPins(dirPin=10, stepPin=11, enablePin=9)
    val stepper3 = stepperPins(dirPin=10, stepPin=11, enablePin=9)
    val stepper4 = stepperPins(dirPin=10, stepPin=11, enablePin=9)
    val stepper5 = stepperPins(dirPin=10, stepPin=11, enablePin=9)
    val stepper6 = stepperPins(dirPin=10, stepPin=11, enablePin=9)
    val stepper7 = stepperPins(dirPin=10, stepPin=11, enablePin=9)
    val endstop0 = digitalPin(pin=30)
    val endstop1 = digitalPin(pin=31)
    val endstop2 = digitalPin(pin=32)
    val endstop3 = digitalPin(pin=33)
    val endstop4 = digitalPin(pin=34)
    val endstop5 = digitalPin(pin=35)
}
val extruder = makeLinearStepper {
    pins = mcu.stepper3
    stepsPerRotation = 200
    rotationDistance = 50
    gearRatio = 1/5
    microsteps = 16
}
val hotend = makeHeater {
    power = mcu.heaterE0
    sensor = makeTemperatureSensor {
        type = "ATC Semitec 104NT-4-R025H42G"
        pin = mcu.temp1
        smoothTime = 0.3
    }
    control = Control.PID
    pidKp = 38.187
    pidKi = 4.896
    pidKd = 74.465
    minTemp = -10
    maxTemp = 300
}
val bed = makeHeater {
    power = mcu.heaterBed
    sensor = makeTemperatureSensor {
        type = "ATC Semitec 104NT-4-R025H42G"
        pin = mcu.temp0
    }
    control = Control.PID
    pidKp = 38.187
    pidKi = 4.896
    pidKd = 74.465
    minTemp = -10
    maxTemp = 120
}

val axisXY = makeCoreXYAxis {
    motorA = makeLinearStepper {
        pins = mcu.stepper0
        stepsPerRotation = 200
        rotationDistance = 40
        microsteps = 16
    }
    motorB = makeLinearStepper {
        pins = mcu.stepper1
        stepsPerRotation = 200
        rotationDistance = 40
        microsteps = 16
    }
    rangeX = makeLinearRange {
        positionMin = 0
        positionMax = 125
        positionEndstop = 122
        endstopTrigger = makePinTrigger(pin = mcu.endstop0, trigger = Trigger.LOW, pullup = true)
        endstopDir = Direction.MAX
        homingSpeed = 20
        secondHomingSpeed = 3
        homingRetractDist = 3
    }
    rangeY = makeLinearRange {
        positionMin = 0
        positionMax = 125
        positionEndstop = 122
        endstopTrigger = makePinTrigger(pin = mcu.endstop1, trigger = Trigger.LOW, pullup = true)
        endstopDir = Direction.MAX
        homingSpeed = 20
        secondHomingSpeed = 3
        homingRetractDist = 3
    }
}
val axisZ = makeLinearAxis {
    motor = makeLinearStepper {
        pins = mcu.stepper2
        stepsPerRotation = 200
        rotationDistance = 40
        microsteps = 16
    }
    range = makeLinearRange {
        positionMin = 0
        positionMax = 125
        positionEndstop = 0.1
        endstopTrigger = makePinTrigger(pin = mcu.endstop2, trigger = Trigger.LOW, pullup = true)
        endstopDir = Direction.MIN
        homingSpeed = 20
        secondHomingSpeed = 3
        homingRetractDist = 3
    }
}

val printer = makePrinter {
    axisX = axisXY.axisX
    axisY = axisXY.axisY
    axisZ = axisZ
    extruder = extruder
    hotend = hotend
    bedHeater = bed
}

