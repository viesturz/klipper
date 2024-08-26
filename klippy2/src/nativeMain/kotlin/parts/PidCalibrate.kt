package parts

import MachineTime
import Temperature
import celsius
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import machine.MachineBuilder
import machine.MachinePart
import machine.impl.CommandException
import machine.impl.PartLifecycle
import machine.impl.waitUntil
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

fun MachineBuilder.PidCalibrate(
    name: String = ""): PidCalibrate = PidCalibrateImpl(name, this).also { addPart(it) }

interface PidCalibrate: MachinePart {
    suspend fun calibrate(heater: Heater, target: Temperature, tolerance: Double = 0.02): config.PID
}

private class PidCalibrateImpl(override val name: String, setup: MachineBuilder) : PidCalibrate, PartLifecycle {
    private val logger = KotlinLogging.logger("PidCalibrate $name")
    init {
        setup.registerCommand("PID_CALIBRATE") { params ->
            val target = params.getCelsius("TARGET")
            val heater = params.getPartByName<Heater>("HEATER")
            val tolerance = params.getDouble("TOLERANCE", 0.02)
            waitUntil(params.queue.flush())
            val pid = calibrate(heater, target, tolerance)
            heater.setControl(pid)
        }
    }

    override suspend fun calibrate(heater: Heater, target: Temperature, tolerance: Double): config.PID {
        logger.info { "Pid calibrate start, target=$target, tolerance=$tolerance" }
        val calibrator = CalibrateControl(logger, target, heater.maxPower, tolerance)
        val prevControl = heater.setControl(calibrator)
        try {
            heater.setTarget(target)
            logger.info { "Pid calibrate - waiting for samples" }
            heater.waitForStable()
            logger.info { "Pid calibrate - waiting for samples done, status = ${calibrator.state}, stable = ${calibrator.isStable()}" }
            return calibrator.calculateResult()
        }
        finally {
            heater.setTarget(0.celsius)
            heater.setControl(prevControl)
        }
    }
}

val DELTA = 5.0
val SAMPLES = 3
val MAX_SAMPLES = 30

private class CalibrateControl(
    val logger: KLogger,
    val target: Temperature,
    val maxPower: Double,
    val tolerance: Double
): TemperatureControl {
    val tempHigh = target + DELTA / 2.0
    val tempLow = target - DELTA / 2.0
    var powerToUse = maxPower

    /**
     * Each sample consists of heating cycle up to tempHigh and observing the following peak,
     * then cooling down to tempLow and observing the following low peak.
     * */
    data class Sample(val power: Double, var max: Temperature, var maxTime: MachineTime, var min: Temperature, var minTime: MachineTime, var heatStartTime: MachineTime, var heatStopTime: MachineTime)
    var current = Sample(powerToUse, target, 0.0, target, 0.0, 0.0, 0.0)
    var next = current
    var samples = ArrayList<Sample>()

    enum class State { INITIALIZING, INITIAL_HEATING, HEATING, PEAKING_HIGH, COOLING, PEAKING_LOW, DONE, ERROR}
    var state = State.INITIALIZING

    override fun update(
        time: MachineTime,
        currentTemp: Temperature,
        currentPower: Double,
        targetTemp: Temperature
    ) = when(state) {
        State.INITIALIZING -> {
            if (currentTemp >= tempLow) {
                throw CommandException("Temperature too high to start calibration: $currentTemp but min is $tempLow")
            } else {
                state = State.INITIAL_HEATING
                logger.info { "Initial heating" }
                current.heatStartTime = time
                powerToUse
            }
        }
        // Wait for reaching the target and then go into normal cycle
        State.INITIAL_HEATING -> {
            if (currentTemp >= target) {
                state = State.HEATING
                logger.info { "Target reached, starting cycles" }
            }
            powerToUse
        }
        // Heat up to tempHigh
        State.HEATING -> {
            if (currentTemp > tempHigh) {
                current.max = currentTemp
                current.maxTime = time
                logger.info { "High temp reached, recording high peak" }
                current.heatStopTime = time
                state = State.PEAKING_HIGH
            }
            powerToUse
        }
        // No heating, measure the peak
        State.PEAKING_HIGH -> {
            if (currentTemp > current.max) {
                current.max = currentTemp
                current.maxTime = time
            }
            if (currentTemp < target) {
                logger.info { "High peak recorded, cooling down to low" }
                state = State.COOLING
            }
            0.0
        }
        // Cooling down to tempLow
        State.COOLING -> {
            if (currentTemp < tempLow) {
                current.min = currentTemp
                current.minTime = time
                logger.info { "Low temp reached, recording low peak" }
                updatePowerToUse(current)
                next = Sample(powerToUse, target, 0.0, target, 0.0, time, 0.0)
                state = State.PEAKING_LOW
            }
            0.0
        }
        // Start heating again, but the bottom peak has not been reached yet
        State.PEAKING_LOW -> {
            if (currentTemp < current.min) {
                current.min = currentTemp
                current.minTime = time
            }
            if (currentTemp > target) {
                logger.info { "Sample ${samples.size} finished, ${current}" }
                samples.add(current)
                current = next
                if (checkFinished()) {
                    logger.info { "Got a stable measurement" }
                    state = State.DONE
                } else if (samples.size > MAX_SAMPLES) {
                    logger.error { "Max peaks reached without stable result, aborting" }
                    state = State.ERROR
                } else {
                    logger.info { "Not stable yet, doing one more sample" }
                    state = State.HEATING
                }
            }
            powerToUse
        }
        State.DONE -> 0.0
        State.ERROR -> 0.0
    }

    /** Calculates new power for the next heating cycle. Aiming for the target temp to be in
     * the middle between min and max. */
    private fun updatePowerToUse(current: Sample) {
        if (samples.isEmpty()) return
        val last = samples.last()
        val mid = current.power * (target - last.min) / (current.max - last.min)
        powerToUse = (mid * 2).coerceIn(0.0, maxPower)
        logger.info { "New power: $powerToUse" }
    }

    private fun checkFinished(): Boolean {
        val spread = getPowerSpread()
        return spread != null && spread <= tolerance
    }

    private fun getPowerSpread(): Double? {
        if (!hasEnoughSamples()) return null
        var minP = samples.last().power
        var maxP = samples.last().power
        for (s in relevantSamples()) {
            minP = min(minP, s.power)
            maxP = max(maxP, s.power)
        }
        return maxP - minP
    }

    fun calculateResult(): config.PID {
        val samples = relevantSamples()
        logger.info { "Calculating result from $samples" }
        var tempDiff = 0.0
        var timeDiff = 0.0
        var theta = 0.0
        var power = 0.0

        for (i in (1..<samples.size)) {
            val s = samples[i]
            val prev = samples[i-1]
            tempDiff += s.min - s.max
            timeDiff += s.minTime - prev.minTime
            theta += s.minTime - s.heatStartTime
            power += s.power
        }
        power /= SAMPLES
        tempDiff /= SAMPLES
        timeDiff /= SAMPLES
        theta /= SAMPLES
        val amplitude = 0.5 * tempDiff.absoluteValue

        //calculate the various parameters
        val Ku = 4.0 * power / (PI * amplitude)
        val Tu = timeDiff
        val Wu = (2.0 * PI) / Tu
        val tau = tan(PI - theta * Wu) / Wu
        val Km = -sqrt(tau*tau * Wu*Wu + 1.0) / Ku
        // log the extra details
        logger.info { "Ziegler-Nichols constants: Ku=$Ku Tu=$Tu" }
        logger.info { "Cohen-Coon constants: Km=$Km Theta=$theta Tau=$tau" }
        // Use Ziegler-Nichols method to generate PID parameters
        val Ti = 0.5 * Tu
        val Td = 0.125 * Tu
        val Kp = 0.6 * Ku
        val Ki = Kp / Ti
        val Kd = Kp * Td
        logger.info { "Calculated kP=$Kp, kI=$Ki, kD=$Kd" }
        return config.PID(Kp, Ki, Kd)
    }

    fun hasEnoughSamples() = samples.size >= SAMPLES + 1
    fun relevantSamples()  = samples.subList(samples.size - SAMPLES-1, samples.size)

    override fun reset() {}
    override fun isStable() = state == State.DONE || state == State.ERROR
}
