package parts

import io.github.oshai.kotlinlogging.KotlinLogging
import machine.CommandQueue
import machine.addBasicMcuCommand
import machine.impl.GCodeCommand
import celsius
import config.DigitalOutPin
import machine.MachineBuilder
import machine.MachinePart
import machine.impl.PartLifecycle

fun MachineBuilder.Fan(
    name: String,
    maxPower: Double = 1.0,
    pin: DigitalOutPin): Fan = FanImpl(name, maxPower, pin, this).also { addPart(it) }

fun MachineBuilder.HeaterFan(
    name: String,
    heater: Heater,
    fan: Fan) = ControlLoop(
    name = name,
    control = { runtime ->
        heater.sensor.measurement.collect { temp ->
            val speed = when {
                temp.temp > 50.celsius -> 1.0
                heater.target > 0.celsius -> 1.0
                else -> 0.0
            }
            fan.setSpeed(speed)
        }
    },
)

interface Fan: MachinePart {
    val speed: Double
    /** Queues a speed change, the reported speed with change immediately. */
    fun queueSpeed(queue: CommandQueue, speed: Double)
    /** Sets speed immediately. */
    fun setSpeed(speed: Double)
}

private class FanImpl(
    override val name: String,
    val maxPower: Double,
    pinConfig: DigitalOutPin,
    setup: MachineBuilder,
): PartLifecycle, Fan {
    val pin = setup.setupMcu(pinConfig.mcu).addPwmPin(pinConfig.copy(watchdogDuration = 0.0))
    val logger = KotlinLogging.logger("Fan $name")
    var _speed = 0.0
    override val speed: Double
        get() = _speed

    init {
        setup.registerMuxCommand("SET_FAN_SPEED", "FAN", name, this::setSpeedGcode)
    }

    private fun setSpeedGcode(queue: CommandQueue, params: GCodeCommand) {
        val speed = params.getDouble("SPEED")
        queueSpeed(queue, speed)
    }
    override fun queueSpeed(queue: CommandQueue, value: Double) {
        require(value in 0f..1f)
        logger.info { "Fan set speed $value" }
        _speed = speed.coerceAtMost(maxPower)
        queue.addBasicMcuCommand(this) { time ->
            pin.set(time, value)
        }
    }
    override fun setSpeed(speed: Double) {
        _speed = speed.coerceAtMost(maxPower)
        pin.setNow(speed)
    }

    override fun status(): Map<String, Any> = mapOf("speed" to _speed)
}
