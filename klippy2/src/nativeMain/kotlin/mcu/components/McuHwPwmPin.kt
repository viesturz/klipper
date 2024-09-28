package mcu.components

import MachineDuration
import config.DigitalOutPin
import io.github.oshai.kotlinlogging.KotlinLogging
import mcu.Mcu
import mcu.PwmPin
import mcu.impl.McuComponent
import mcu.impl.McuConfigure
import mcu.impl.McuRuntime

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
        configure.configCommand(
            "config_pwm_out oid=%c pin=%u cycle_ticks=%u value=%hu default_value=%hu max_duration=%u"
        ) {
            addId(id);addEnum("pin", config.pin)
            addU(configure.firmware.durationToTicks(_cycleTime))
            addHU((config.startValue * pwmMax).toInt().toUShort())
            addHU((config.startValue * pwmMax).toInt().toUShort())
            addU(configure.firmware.durationToTicks(config.watchdogDuration))
        }
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
                minClock = lastClock,
                reqClock = clock,
                data = queue.build("queue_pwm_out oid=%c clock=%u value=%hu") {
                    addId(id);addU(clock.toUInt());addHU(dutyToValue(_dutyCycle))
                })
        }
    }

    override fun setNow(dutyCycle: Double, cycleTime: MachineDuration?) {
        if (_dutyCycle == dutyCycle && cycleTime == _cycleTime) return
        _dutyCycle = dutyCycle
        cycleTime?.let { _cycleTime = it }
        val duty = dutyToValue(_dutyCycle)
        logger.debug { "SetNow $duty" }
        queue.send(
            minClock = queue.lastClock,
            reqClock = runtime.timeToClock(runtime.reactor.now),
            signature = "set_pwm_out pin=%u cycle_ticks=%u value=%hu"
        ) {
            addEnum("pin", config.pin)
            addU(configure.firmware.durationToTicks(_cycleTime))
            addHU(duty)
        }
    }
}