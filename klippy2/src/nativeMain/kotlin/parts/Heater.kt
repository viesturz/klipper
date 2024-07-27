package parts

import MachineTime
import Temperature
import celsius
import config.DigitalOutPin
import io.github.oshai.kotlinlogging.KotlinLogging
import kelvins
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.takeWhile
import machine.CommandQueue
import machine.MachineBuilder
import machine.MachinePart
import machine.MachineRuntime
import machine.addLocalCommand
import machine.addWaitForCommand
import machine.impl.GcodeParams
import machine.impl.PartLifecycle
import kotlin.math.min
import kotlin.math.sign

fun MachineBuilder.Heater(
    name: String,
    pin: DigitalOutPin,
    sensor: TemperatureSensor,
    maxPower: Double = 1.0,
    stableDelta: Temperature = 1.celsius,
    control: config.TemperatureControl,
): Heater {
    val controller = HeaterLoop(name, sensor, pin, makeControl(control), maxPower, this)
    return HeaterImpl(name, controller, sensor, this).also { addPart(it) }
}

interface Heater: MachinePart {
    val sensor: TemperatureSensor
    val target: Temperature
    fun setTarget(queue: CommandQueue, t: Temperature)
}

private class HeaterImpl(
    override val name: String,
    private val loop: HeaterLoop,
    override val sensor: TemperatureSensor,
    setup: MachineBuilder): PartLifecycle, Heater {

    var _target: Temperature = 0.kelvins
    override val target: Temperature
        get() = _target
    init {
        setup.registerMuxCommand("SET_HEATER_TEMPERATURE", "HEATER", name, this::setTargetGcode)
        setup.registerMuxCommand("TEMPERATURE_WAIT", "HEATER", name, this::waitForTempGcode)
    }

    override suspend fun onStart(runtime: MachineRuntime) {
        runtime.reactor.launch { loop.runLoop() }
    }

    private fun setTargetGcode(queue: CommandQueue, params: GcodeParams) {
        val temperature = params.getDouble("TARGET").celsius
        setTarget(queue, temperature)
    }

    private fun waitForTempGcode(queue: CommandQueue, params: GcodeParams) {
        val min = params.getCelsius("MIN", sensor.minTemp)
        val max = params.getCelsius("MAX", sensor.maxTemp)
//        queue.addWaitForCommand(this) {
//            tempWait(min, max)
//        }
    }

    private suspend fun tempWait(min: Temperature, max: Temperature) {
        sensor.value.takeWhile { it.temp > min || it.temp < max }.count()
    }

    override fun setTarget(queue: CommandQueue, t: Temperature) {
        require(t >= sensor.minTemp)
        require(t <= sensor.maxTemp)
        if (t == _target) return
        _target = t
        queue.addLocalCommand(this) {
            loop.setTarget(t)
        }
    }

    override fun status() = mapOf(
        "power" to loop.power,
        "temperature" to loop.sensor.value,
        "target" to target,
        )
}

/** Heater control loop. */
private class HeaterLoop(name: String,
                        val sensor: TemperatureSensor,
                         pinConfig: DigitalOutPin,
                         val control: TemperatureControl,
                         /** Temp delta to consider as stable. */
                         val maxPower: Double, setup: MachineBuilder) {
    val logger = KotlinLogging.logger("Heater $name")
    val heater = setup.setupMcu(pinConfig.mcu).addPwmPin(pinConfig)
    var power = 0.0
    var target: Temperature = 0.kelvins

    suspend fun runLoop() {
            sensor.value.collect { measurement ->
                if (target == 0.celsius) {
                    heater.setNow(0.0)
                    return@collect
                }
                power = min(control.update(measurement.time, measurement.temp, power, target), maxPower)
                logger.info { "Update temp=${measurement.temp}, target=$target, power=$power" }
                heater.setNow(power)
            }
        }

    fun setTarget(t: Temperature) {
        target = t
        control.reset()
    }
}

interface TemperatureControl {
    /** Computes next power value. */
    fun update(time: MachineTime, currentTemp: Temperature, currentPower: Double, targetTemp: Temperature): Double
    fun reset() {}
}

private fun makeControl(config: config.TemperatureControl): TemperatureControl = when(config) {
    is config.Watermark -> ControlWatermark(config)
    is config.PID -> ControlPID(config)
}

class ControlWatermark(val config: config.Watermark): TemperatureControl {
    override fun update(
        time: MachineTime,
        currentTemp: Temperature,
        currentPower: Double,
        targetTemp: Temperature
    ) = when {
        currentTemp >= targetTemp + config.maxDelta -> 0.0
        currentTemp <= targetTemp - config.maxDelta -> 1.0
        else -> currentPower
    }
}

// Positional (PID) control alg from https://github.com/DangerKlippers/danger-klipper/pull/210
class ControlPID(val config: config.PID): TemperatureControl {
    var previousTemp = 0.celsius
    var previousError = 0.0
    var previousDerivation = 0.0
    var previousIntegral = 0.0
    var lastTime = 0.0
    val smoothTime = 1.0
    val logger = KotlinLogging.logger("PID")

    override fun update(
        time: MachineTime,
        currentTemp: Temperature,
        currentPower: Double,
        targetTemp: Temperature
    ):Double {
        val error = targetTemp - currentTemp
        if (lastTime == 0.0) {
            lastTime = time
            previousError = error
            previousTemp = currentTemp
            return currentPower
        }
        val timeDiff = time - lastTime
        val smoothCoef = 1.0 + smoothTime / timeDiff
        // calculate the current integral amount using the Trapezoidal rule
        val ic = ((previousError + error) / 2.0) * timeDiff
        val integral = previousIntegral + ic
        // calculate the current derivative using a modified moving average,
        // and derivative on measurement, to account for derivative kick
        // when the set point changes
        var derivative = -(currentTemp - previousTemp) / timeDiff
        derivative = ((smoothCoef - 1.0) * previousError + derivative) / smoothCoef
        // calculate the output
        val output = config.kP * error + config.kI * integral + config.kD * derivative
        val clampedOutput = output.coerceIn(0.0..1.0)
        logger.info { "Update, P = $error, D=$derivative, I=$integral" }
        if (output == clampedOutput || sign(output) != sign(ic)) {
            // Integral update is allowed when not saturated or opposite to the integral.
            previousIntegral = integral
        }
        previousTemp = currentTemp
        previousError = error
        return clampedOutput
    }

    override fun reset() {
        lastTime = 0.0
        previousIntegral = 0.0
        previousDerivation = 0.0
        previousError = 0.0
    }
}
