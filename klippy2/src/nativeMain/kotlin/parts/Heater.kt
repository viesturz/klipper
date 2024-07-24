package parts

import MachineTime
import Temperature
import celsius
import config.DigitalOutPin
import kelvins
import machine.CommandQueue
import machine.MachineBuilder
import machine.MachinePart
import machine.MachineRuntime
import machine.addLocalCommand
import machine.impl.GcodeParams
import machine.impl.PartLifecycle
import machine.impl.Reactor
import kotlin.math.min

fun MachineBuilder.Heater(
    name: String,
    pin: DigitalOutPin,
    sensor: TemperatureSensor,
    maxPower: Double = 1.0,
    control: config.TemperatureControl,
): Heater = HeaterImpl(name, pin, sensor, maxPower, control, this).also { addPart(it) }

interface Heater: MachinePart {
    val sensor: TemperatureSensor
    val target: Temperature
    fun setTarget(queue: CommandQueue, t: Temperature)
}

class HeaterImpl(
    override val name: String,
    pinConfig: DigitalOutPin,
    override val sensor: TemperatureSensor,
    maxPower: Double,
    controlConfig: config.TemperatureControl,
    setup: MachineBuilder): PartLifecycle, Heater {
    private val loop = HeaterLoop(sensor, pinConfig, makeControl(controlConfig),  maxPower, setup)

    var _target: Temperature = 0.kelvins
    override val target: Temperature
        get() = _target
    init {
        setup.registerMuxCommand("SET_HEATER_TEMPERATURE", "HEATER", name, this::setTargetGcode)
    }

    override suspend fun onStart(runtime: MachineRuntime) {
        runtime.reactor.launch { loop.runLoop() }
    }

    private fun setTargetGcode(queue: CommandQueue, params: GcodeParams) {
        val temperature = params.getDouble("TARGET").celsius
        setTarget(queue, temperature)
    }

    override fun setTarget(queue: CommandQueue, t: Temperature) {
        require(t >= sensor.minTemp)
        require(t <= sensor.maxTemp)
        if (t == _target) return
        _target = t
        queue.addLocalCommand(this) {
            loop.target = t
        }
    }

    override fun status() = mapOf(
        "power" to loop.power,
        "temperature" to loop.sensor.value,
        "target" to target,
        )
}

/** Heater control loop. */
private class HeaterLoop(val sensor: TemperatureSensor,
                         pinConfig: DigitalOutPin,
                         val control: TemperatureControl,
                         val maxPower: Double, setup: MachineBuilder) {
    val heater = setup.setupMcu(pinConfig.mcu).addPwmPin(pinConfig)
    var power = 0.0
    var target: Temperature = 0.kelvins

    suspend fun runLoop() {
            sensor.value.collect { measurement ->
                power = min(control.update(measurement.time, measurement.temp, power, target), maxPower)
                // TODO: use SetNow.
                heater.set(Reactor.now+0.2, power)
            }
        }
}

interface TemperatureControl {
    /** Computes next power value. */
    fun update(time: MachineTime, currentTemp: Temperature, currentPower: Double, targetTemp: Temperature): Double
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

class ControlPID(val config: config.PID): TemperatureControl {
    override fun update(
        time: MachineTime,
        currentTemp: Temperature,
        currentPower: Double,
        targetTemp: Temperature
    ):Double {
        TODO("Implement this")
    }
}
