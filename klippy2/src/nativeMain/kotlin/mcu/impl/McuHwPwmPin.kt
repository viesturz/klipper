package mcu.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import mcu.Mcu
import mcu.PwmPin

private val logger = KotlinLogging.logger("McuHwPwmPin")

class McuHwPwmPin(override val mcu: Mcu, val config: config.DigitalOutPin, val configure: McuConfigure) : PwmPin,
    McuComponent {
    val id = configure.makeOid()
    private val pwmMax = configure.identify.configLong("PWM_MAX", 255)
    private val queue = configure.makeCommandQueue("McuHwPwmPin ${config.pin}")
    var _dutyCycle = config.startValue
    var _cycleTime = config.cycleTime
    private var runtime: McuRuntime? = null

    override val dutyCycle: Float
        get() = _dutyCycle
    override val cycleTime: Float
        get() = _cycleTime

    override fun configure(configure: McuConfigure) {
        configure.configCommand(
            "config_pwm_out oid=%c pin=%u cycle_ticks=%u value=%hu default_value=%hu max_duration=%u") {
            addId(id);addEnum("pin", config.pin)
            addU(configure.identify.durationToTicks(_cycleTime))
            addHU((config.startValue * pwmMax).toInt().toUShort())
            addHU((config.startValue * pwmMax).toInt().toUShort())
            addU(configure.identify.durationToTicks(config.maxDuration))
        }
    }

    override fun start(runtime: McuRuntime) {
        this.runtime = runtime
        queue.send(queue.build("queue_pwm_out oid=%c clock=%u value=%hu") {
            addId(id);addU(0u);addHU(dutyToValue(_dutyCycle))
        })
    }

    private fun dutyToValue(d: Float) = (d * pwmMax + 0.5f).toUInt().toUShort()

    override fun set(time: Double, dutyCycle: Float, cycleTime: Float?) {
        val lastClock = queue.lastClock
        if (cycleTime != null) {
            logger.error { "Hardware PWM does not support cycle time changes" }
        }
        if (dutyCycle != _dutyCycle) {
            _dutyCycle = dutyCycle
            runtime?.let { runtime ->
                val clock = runtime.timeToClock(time)
                queue.send(
                    minClock = lastClock,
                    reqClock = clock,
                    data = queue.build("queue_pwm_out oid=%c clock=%u value=%hu") {
                        addId(id);addU(clock.toUInt());addHU(dutyToValue(_dutyCycle))
                    })
            }
        }
    }

    override fun setNow(dutyCycle: Float, cycleTime: Float?) {
        if (_dutyCycle == dutyCycle && cycleTime == _cycleTime) return
        _dutyCycle = dutyCycle
        cycleTime?.let { _cycleTime = it }
        runtime?.let { runtime ->
            queue.send(
                minClock = queue.lastClock,
                reqClock = runtime.timeToClock(runtime.reactor.now),
                signature = "set_pwm_out pin=%u cycle_ticks=%u value=%hu") {
                addEnum("pin", config.pin)
                addU(configure.identify.durationToTicks(_cycleTime))
                addHU(dutyToValue(_dutyCycle))
                }
        }
    }
}