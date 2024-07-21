package parts

import MachineTime
import Temperature
import celsius
import kelvins
import kotlinx.coroutines.launch
import machine.CommandQueue
import machine.addLocalCommand
import machine.impl.GcodeParams

interface TemperatureControl {
    /** Computes next power value. */
    fun update(time: MachineTime, currentTemp: Temperature, currentPower: Double, targetTemp: Temperature): Double
}

class Heater(override val config: config.Heater, setup: MachineSetup): MachinePartLifecycle, MachinePart {
    val temperature = setup.acquirePart(config.sensor)
    val heater = setup.acquireMcu(config.pin.mcu).addPwmPin(config.pin)
    var control = makeControl(config.control)
    var power = 0.0
    var commandedTarget: Temperature = 0.kelvins
    private var activeTarget: Temperature = 0.kelvins

    init {
        setup.registerMuxCommand("SET_HEATER_TEMPERATURE", "HEATER", config.name, this::setTemperatureGcode)
    }

    override suspend fun onStart(runtime: MachineRuntime) {
        runtime.scope.launch {
            temperature.value.collect { measurement ->
                // TODO: use SetNow.
                heater.set(runtime.reactor.now+0.3, control.update(measurement.time, measurement.temp, power, activeTarget))
            }
        }
    }

    private fun setTemperatureGcode(queue: CommandQueue, params: GcodeParams) {
        val temperature = params.getDouble("TARGET").celsius
        setTemperature(queue, temperature)
    }

    fun setTemperature(queue: CommandQueue, value: Temperature) {
        require(value >= config.sensor.minTemp)
        require(value <= config.sensor.maxTemp)
        if (value == commandedTarget) return
        commandedTarget = value
        queue.addLocalCommand(this) {
            activeTarget = value
        }
    }

    override fun status(time: MachineTime) = mapOf(
        "power" to power,
        "temperature" to temperature,
        "target" to commandedTarget,
        )
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