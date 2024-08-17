package parts

import MachineTime
import Temperature
import celsius
import config.DigitalOutPin
import io.github.oshai.kotlinlogging.KotlinLogging
import kelvins
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import machine.CommandQueue
import machine.MachineBuilder
import machine.MachinePart
import machine.MachineRuntime
import machine.addLocalCommand
import machine.impl.GCodeCommand
import machine.impl.PartLifecycle
import kotlin.math.absoluteValue
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
    val power: Double
    val maxPower: Double
    fun setTarget(queue: CommandQueue, t: Temperature)
    fun setTarget(t: Temperature)
    fun setControl(control: TemperatureControl): TemperatureControl
    fun setControl(config: config.PID) = setControl(makeControl(config))
    suspend fun waitForStable()
}

interface TemperatureControl {
    /** Computes next power value. */
    fun update(time: MachineTime, currentTemp: Temperature, currentPower: Double, targetTemp: Temperature): Double
    fun reset() {}
    fun isStable(): Boolean
}

private class HeaterImpl(
    override val name: String,
    private val loop: HeaterLoop,
    override val sensor: TemperatureSensor,
    setup: MachineBuilder): PartLifecycle, Heater {

    var _target: Temperature = 0.kelvins
    override val target: Temperature
        get() = _target
    override val power: Double
        get() = loop.power
    override val maxPower: Double
        get() = loop.maxPower
    init {
        setup.registerMuxCommand("SET_HEATER_TEMPERATURE", "HEATER", name, this::setTargetGcode)
    }

    override suspend fun onStart(runtime: MachineRuntime) {
        runtime.reactor.launch { loop.runLoop() }
    }

    private fun setTargetGcode(queue: CommandQueue, params: GCodeCommand) {
        val temperature = params.getCelsius("TARGET")
        setTarget(queue, temperature)
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

    override suspend fun waitForStable() {
        if (_target <= 0.celsius) return
        loop.sensor.measurement.map {_target <= 0.celsius || loop.control.isStable()}.dropWhile { !it }.first()
    }

    override fun setTarget(t: Temperature) {
        require(t >= sensor.minTemp)
        require(t <= sensor.maxTemp)
        if (t == _target) return
        _target = t
        loop.setTarget(t)
    }

    override fun setControl(control: TemperatureControl) = loop.setControl(control)

    override fun status() = mapOf(
        "power" to loop.power,
        "temperature" to loop.sensor.measurement.value,
        "target" to target,
        )
}

/** Heater control loop. */
private class HeaterLoop(name: String,
                        val sensor: TemperatureSensor,
                         pinConfig: DigitalOutPin,
                         var control: TemperatureControl,
                         /** Temp delta to consider as stable. */
                         val maxPower: Double, setup: MachineBuilder) {
    val logger = KotlinLogging.logger("Heater $name")
    val heater = setup.setupMcu(pinConfig.mcu).addPwmPin(pinConfig)
    var power = 0.0
    var target: Temperature = 0.kelvins

    suspend fun runLoop() {
            sensor.measurement.collect { measurement ->
                if (target == 0.celsius) {
                    heater.setNow(0.0)
                    return@collect
                }
                power = control.update(measurement.time, measurement.temp, power, target).coerceAtMost(maxPower)
                logger.info { "Update temp=${measurement.temp}, target=$target, power=$power" }
                heater.setNow(power)
            }
        }

    fun setTarget(t: Temperature) {
        target = t
        control.reset()
    }

    fun setControl(newControl: TemperatureControl): TemperatureControl {
        val old = control
        control = newControl
        control.reset()
        return old
    }
}

private fun makeControl(config: config.TemperatureControl): TemperatureControl = when(config) {
    is config.Watermark -> ControlWatermark(config)
    is config.PID -> ControlPID(config)
}

class ControlWatermark(val config: config.Watermark): TemperatureControl {
    // Watermark is neve really stable, but to have something to wait for.
    var stable = false

    override fun update(
        time: MachineTime,
        currentTemp: Temperature,
        currentPower: Double,
        targetTemp: Temperature
    ) = when {
        currentTemp >= targetTemp + config.maxDelta -> 0.0
        currentTemp <= targetTemp - config.maxDelta -> 1.0
        else -> currentPower
    }. also { stable = currentTemp >= targetTemp }
    override fun reset() {
        stable = false
    }
    override fun isStable() = stable
}

// Positional (PID) control alg from https://github.com/DangerKlippers/danger-klipper/pull/210
class ControlPID(val config: config.PID): TemperatureControl {
    var previousTemp = 0.celsius
    var previousError = 0.0
    var previousDer = 0.0
    var previousSlope = 0.0
    var previousIntegral = 0.0
    var lastTime = 0.0
    val smoothTime = 1.0
    var previousTarget = 0.celsius
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
            previousSlope = previousError
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
        derivative = ((smoothCoef - 1.0) * previousDer + derivative) / smoothCoef
        // calculate the output
        val output = config.kP * error + config.kI * integral + config.kD * derivative
        val clampedOutput = output.coerceIn(0.0..1.0)
        logger.trace { "Update, P = $error, D=$derivative, I=$integral" }
        if (output == clampedOutput || sign(output-0.1) != sign(ic)) {
            // Integral update is allowed when not saturated or opposite to the integral.
            previousIntegral = integral
        }
        previousTemp = currentTemp
        previousSlope = (error - previousError) / timeDiff
        previousError = error
        previousDer = derivative
        previousTarget = targetTemp
        lastTime = time
        return clampedOutput
    }

    override fun reset() {
        lastTime = 0.0
        previousIntegral = 0.0
        previousError = 0.0
    }

    // Less than 1 degree off and temp change less that 0.2 deg/sec
    override fun isStable() = previousError.absoluteValue < 1 && previousSlope < 0.2
}
