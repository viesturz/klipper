package mcu.components

import io.github.oshai.kotlinlogging.KotlinLogging
import MachineTime
import DigitalOutPin
import Mcu
import mcu.McuClock32
import mcu.McuComponent
import mcu.McuConfigure
import mcu.McuObjectCommand
import mcu.McuRuntime
import mcu.ObjectId
import mcu.PinName
import utils.RegisterMcuMessage
import kotlin.math.max

class McuDigitalPin(override val mcu: Mcu, val config: config.DigitalOutPin, initialize: McuConfigure) : DigitalOutPin,
    McuComponent {
    val id = initialize.makeOid()
    val queue = initialize.makeCommandQueue("McuDigitalPin ${config.pin}", 3)
    var _enabled = config.startValue > 0
    private val logger = KotlinLogging.logger("McuDigitalPin ${config.pin}")
    private lateinit var runtime: McuRuntime
    override val value: Boolean
        get() = _enabled

    override fun configure(configure: McuConfigure) {
        logger.trace { "Configure" }
        configure.configCommand(CommandConfigDigitalOut(id, config.pin, (config.startValue > 0) != config.invert, (config.shutdownValue > 0) != config.invert,configure.durationToClock(config.watchdogDuration)))
        configure.initCommand(CommandUpdateDigitalOut(id, _enabled != config.invert))
    }

    override fun start(runtime: McuRuntime) {
        this.runtime = runtime
    }

    override fun set(time: MachineTime, value: Boolean) {
        if (time == 0.0) {
            setNow(value)
            return
        }
        val lastClock = queue.lastClock
        if (value != _enabled || config.watchdogDuration > 0) {
            _enabled = value
            val clock = max(runtime.timeToClock(time),queue.lastClock)
            queue.send(CommandQueueDigitalOut(id, clock.toUInt(), onTicks = if (_enabled != config.invert) 1U else 0U ),
                minClock = lastClock,
                reqClock = clock)
        }
    }

    override fun setNow(value: Boolean) {
        val curClock = runtime.reactor.now
        val lastClock = queue.lastClock
        if (value != _enabled || config.watchdogDuration > 0) {
            _enabled = value
            queue.send(CommandUpdateDigitalOut(id, _enabled != config.invert),
                minClock = lastClock,
                reqClock = runtime.timeToClock(curClock))
        }
    }
}

@RegisterMcuMessage(signature = "config_digital_out oid=%c pin=%u value=%c default_value=%c max_duration=%u")
data class CommandConfigDigitalOut(override val id: ObjectId, val pin: PinName, val value: Boolean, val defaultValue: Boolean, val maxDuration: UInt): McuObjectCommand
@RegisterMcuMessage(signature = "set_digital_out_pwm_cycle oid=%c cycle_ticks=%u")
data class CommandSetDigitalOutPwmCycle(override val id: ObjectId, val cycleTicks: UInt): McuObjectCommand
@RegisterMcuMessage(signature = "update_digital_out_pwm oid=%c on_ticks=%u")
data class CommandUpdateDigitalOutPwm(override val id: ObjectId, val onTicks: UInt): McuObjectCommand
@RegisterMcuMessage(signature = "queue_digital_out oid=%c clock=%u on_ticks=%u")
// onTicks act as on/off when PWM cycle is not set.
data class CommandQueueDigitalOut(override val id: ObjectId, val clock: McuClock32, val onTicks: UInt): McuObjectCommand
@RegisterMcuMessage(signature = "update_digital_out oid=%c value=%c")
data class CommandUpdateDigitalOut(override val id: ObjectId, val value: Boolean): McuObjectCommand
