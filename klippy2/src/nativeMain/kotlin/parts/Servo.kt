package parts

import config.DigitalOutPin
import io.github.oshai.kotlinlogging.KotlinLogging
import machine.CommandQueue
import machine.MachineBuilder
import machine.MachinePart
import machine.impl.PartLifecycle
import utils.deinterp
import utils.interpolate

/** A basic geared servo motor with ~180 degree rotation, driven by a standard 1..2 ms PWM signal.
 *  */
fun MachineBuilder.Servo(
    name: String,
    pin: DigitalOutPin,
    minPulse: Double = 0.001, // 1ms pulse for min angle
    maxPulse: Double = 0.002, // 2ms pulse for max angle
    minAngle: Double = -90.0, //Angle at 1ms PWM
    maxAngle: Double = 90.0, //Angle at 2ms PWM
    speed: Double = 200.0, // Speed in degrees/sec
): Servo = ServoImpl(
    name,
    pin,
    minPulse, maxPulse,
    minAngle, maxAngle,
    speed,
    this,
).also { addPart(it) }

interface Servo: MachinePart {
    val angle: Double
    /** Queues an angle change, the reported ange with change immediately. */
    fun queueAngle(queue: CommandQueue, angle: Double)
    /** Sets speed immediately. */
    fun setAngle(angle: Double)
}

private class ServoImpl(
    override val name: String,
    pin: DigitalOutPin,
    minPulse: Double,
    maxPulse: Double,
    val minAngle: Double,
    val maxAngle: Double,
    val speed: Double,
    setup: MachineBuilder,
) : PartLifecycle, Servo {
    val pin = setup.setupMcu(pin.mcu).addPwmPin(pin.copy(watchdogDuration = 0.0))
    // min pulse 1ms, max pulse 2ms.
    val minDuty = minPulse / pin.cycleTime
    val maxDuty = maxPulse / pin.cycleTime
    val logger = KotlinLogging.logger("Servo $name")
    var _angle = 0.0

    override val angle: Double
        get() = _angle

    override fun queueAngle(queue: CommandQueue, angle: Double) {
        _angle = angle
        val duty = toDuty(angle)
        queue.add { time -> pin.set(time, duty) }
    }
    override fun setAngle(angle: Double) {
        _angle = angle
        val duty = toDuty(angle)
        logger.info { "SetAngle $angle, duty = $duty" }
        pin.setNow(duty)
    }

    fun toDuty(angle: Double) = angle.coerceIn(minAngle, maxAngle).deinterp(minAngle..maxAngle).interpolate(minDuty..maxDuty)

    override fun status(): Map<String, Any> = mapOf("angle" to _angle)
}