package parts

import io.github.oshai.kotlinlogging.KotlinLogging
import machine.CommandQueue
import celsius
import config.DigitalOutPin
import MachineBuilder
import MachinePart
import MachineRuntime
import MachineTime
import PartLifecycle
import machine.getNextMoveTime
import utils.interpolate

fun MachineBuilder.Fan(
    name: String = defaultName("Fan"),
    kickStartTime: Double = 0.0,
    offBelow: Double = 0.0,
    maxPower: Double = 1.0,
    pin: DigitalOutPin
): Fan = FanImpl(name, maxPower, kickStartTime, offBelow, pin, this).also { addPart(it) }

fun MachineBuilder.HeaterFan(
    name: String = defaultName("HeaterFan"),
    heater: Heater,
    fan: Fan) = ControlLoop(
    name = name,
    control = { runtime ->
        heater.sensor.flow.collect { temp ->
            val speed = when {
                temp.value > 50.celsius -> 1.0
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
    /** Sets speed immediately. Not that this does NOT cancel any queued speed updates. */
    fun setSpeed(speed: Double)
}

private class FanImpl(
    override val name: String,
    val maxPower: Double,
    val kickStartTime: Double,
    val offBelow: Double,
    pinConfig: DigitalOutPin,
    setup: MachineBuilder,
): PartLifecycle, Fan {
    private val pin = setup.setupMcu(pinConfig.mcu).addPwmPin(pinConfig.copy(watchdogDuration = 0.0))
    private val logger = KotlinLogging.logger("Fan $name")
    private var _speed = 0.0
    private var lastFanTime: MachineTime = 0.0
    lateinit var runtime: MachineRuntime
    override val speed: Double
        get() = _speed

    init {
        setup.registerMuxCommand("SET_FAN_SPEED", "FAN", name) { params ->
            val speed = params.getDouble("SPEED")
            queueSpeed(params.queue, speed)
        }
    }

    override suspend fun onStart(runtime: MachineRuntime) {
        this.runtime = runtime
    }

    override fun queueSpeed(queue: CommandQueue, speed: Double) = queue.add { time -> applySpeed(speed, time.coerceAtLeast(lastFanTime + runtime.reactor.SCHEDULING_TIME)) }
    override fun setSpeed(speed: Double) = applySpeed(speed, runtime.reactor.now.coerceAtLeast(lastFanTime))

    fun applySpeed(newSpeed: Double, time: MachineTime) {
        require(newSpeed in 0f..1f)
        val effectiveSpeed = if (newSpeed >= offBelow) newSpeed else 0.0
        var effectiveTime = time
        val effectivePower = effectiveSpeed.interpolate(0.0.. maxPower)
        val needsKickStart = kickStartTime > 0 && effectiveSpeed > 0 && (speed == 0.0 || effectiveSpeed > speed * 2)

        if (needsKickStart) {
            setPower(maxPower, effectiveTime)
            effectiveTime += kickStartTime
        }
        setPower(effectivePower, effectiveTime)

        lastFanTime = effectiveTime
        _speed = newSpeed
    }

    private fun setPower(power: Double, time: MachineTime) {
        if (time >= getNextMoveTime()) {
            pin.set(time, power)
        } else {
            pin.setNow(power)
        }
    }

    override fun status(): Map<String, Any> = mapOf("speed" to _speed)
}
