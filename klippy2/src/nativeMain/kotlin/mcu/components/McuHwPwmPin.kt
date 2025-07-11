package mcu.components

import MachineDuration
import config.DigitalOutPin
import io.github.oshai.kotlinlogging.KotlinLogging
import Mcu
import PwmPin
import mcu.McuClock32
import mcu.McuCommand
import mcu.McuComponent
import mcu.McuConfigure
import mcu.McuObjectCommand
import mcu.McuRuntime
import mcu.ObjectId
import mcu.PinName
import utils.RegisterMcuMessage

class McuHwPwmPin(
    override val mcu: Mcu,
    val config: DigitalOutPin,
    val configure: McuConfigure
) : PwmPin,
    McuComponent {
    private val logger = KotlinLogging.logger("McuHwPwmPin ${config.pin}")
    val id = configure.makeOid()
    private val pwmMax = configure.firmware.configLong("PWM_MAX", 255)
    private val queue = configure.makeCommandQueue("McuHwPwmPin ${config.pin}", 3)
    var _dutyCycle = config.startValue
    var _cycleTime = config.cycleTime
    private lateinit var runtime: McuRuntime

    override val dutyCycle: Double
        get() = _dutyCycle
    override val cycleTime: MachineDuration
        get() = _cycleTime

    override fun configure(configure: McuConfigure) {
        configure.configCommand(CommandConfigPwmOut(id,
            pin = config.pin,
            cycleTicks = configure.firmware.durationToTicks(_cycleTime),
            value = (config.startValue * pwmMax).toInt().toUShort(),
            defaultValue = (config.startValue * pwmMax).toInt().toUShort(),
            maxDuration = configure.firmware.durationToTicks(config.watchdogDuration)))
    }

    override fun start(runtime: McuRuntime) {
        this.runtime = runtime
    }

    private fun dutyToValue(d: Double) = (d * pwmMax + 0.5f).toUInt().toUShort()

    override fun set(time: Double, dutyCycle: Double, cycleTime: MachineDuration?) {
        val lastClock = queue.lastClock
        if (cycleTime != null) {
            logger.error { "Hardware PWM does not support cycle time changes" }
        }
        if (dutyCycle != _dutyCycle) {
            _dutyCycle = dutyCycle
            val clock = runtime.timeToClock(time)
            queue.send(
                CommandQueuePwmOut(id, clock.toUInt(), dutyToValue(_dutyCycle)),
                minClock = lastClock,
                reqClock = clock,
            )
        }
    }

    override fun setNow(dutyCycle: Double, cycleTime: MachineDuration?) {
        if (_dutyCycle == dutyCycle && cycleTime == _cycleTime) return
        _dutyCycle = dutyCycle
        cycleTime?.let { _cycleTime = it }
        val duty = dutyToValue(_dutyCycle)
        logger.debug { "SetNow $duty" }
        queue.send(
            CommandSetPwmOut(config.pin, configure.firmware.durationToTicks(_cycleTime), duty),
            minClock = queue.lastClock,
            reqClock = runtime.timeToClock(runtime.reactor.now),
        )
    }
}

@RegisterMcuMessage(signature = "config_pwm_out oid=%c pin=%u cycle_ticks=%u value=%hu default_value=%hu max_duration=%u")
data class CommandConfigPwmOut(override val id: ObjectId, val pin: PinName, val cycleTicks: McuClock32, val value: UShort, val defaultValue: UShort, val maxDuration: McuClock32): McuObjectCommand
@RegisterMcuMessage(signature = "queue_pwm_out oid=%c clock=%u value=%hu")
data class CommandQueuePwmOut(override val id: ObjectId, val clock: McuClock32, val value: UShort): McuObjectCommand
@RegisterMcuMessage(signature = "set_pwm_out pin=%u cycle_ticks=%u value=%hu")
data class CommandSetPwmOut(val pin: PinName, val cycleTicks: McuClock32, val value: UShort): McuCommand
