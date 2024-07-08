package mcu.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import machine.impl.MachineTime
import mcu.Mcu
import mcu.PwmPin
import kotlin.math.max

class McuPwmPin(override val mcu: Mcu, val config: config.DigitalOutPin, initialize: McuConfigure) : PwmPin,
    McuComponent {
    val id = initialize.makeOid()
    val queue = initialize.makeCommandQueue("McuPwmPin ${config.pin}")
    var _dutyCycle = config.startValue
    var _cycleTime = config.cycleTime
    var cycleTicks: McuClock32 = 0u
    private val logger = KotlinLogging.logger("McuPwmPin ${config.pin}")

    private var runtime: McuRuntime? = null

    override val dutyCycle: Float
        get() = _dutyCycle
    override val cycleTime: Float
        get() = _cycleTime

    override fun configure(configure: McuConfigure) {
        logger.info { "Configure" }
        configure.configCommand("config_digital_out oid=%c pin=%u value=%c default_value=%c max_duration=%u") {
            addId(id);addEnum("pin", config.pin)
            addC(config.startValue > 0)
            addC(config.shutdownValue > 0)
            addU(0u) // PWM pins do not use max duration.
        }
    }

    override fun start(runtime: McuRuntime) {
        this.runtime = runtime
        cycleTicks = runtime.durationToClock(config.cycleTime)
        val time = runtime.reactor.now + 0.2
        val clock = runtime.timeToClock(time)
        logger.info { "Start cycleTicks = ${cycleTicks}, time=$time, clock = $clock" }
        queue.send("set_digital_out_pwm_cycle oid=%c cycle_ticks=%u") {
            addId(id);addU(cycleTicks)
        }
        queue.send(
            minClock = queue.lastClock,
            reqClock = clock,
            signature = "queue_digital_out oid=%c clock=%u on_ticks=%u") {
            addId(id);addU(clock.toUInt());addU(dutyToTicks(_dutyCycle))
        }
    }

    private fun dutyToTicks(d: Float) = (d * cycleTicks.toDouble() + 0.5f).toUInt()

    override fun set(time: MachineTime, dutyCycle: Float, cycleTime: Float?) {
        var cycleChange = false
        val lastClock = queue.lastClock
        if (cycleTime != null && _cycleTime != cycleTime) {
            cycleChange = true
            _cycleTime = cycleTime
            runtime?.let { runtime ->
                cycleTicks = runtime.durationToClock(config.cycleTime)
                queue.send(
                    minClock = lastClock,
                    reqClock = runtime.timeToClock(time),
                    data = queue.build("set_digital_out_pwm_cycle oid=%c cycle_ticks=%u") {
                        addId(id);addU(cycleTicks)
                    })
            }
        }
        if (cycleChange || dutyCycle != _dutyCycle) {
            _dutyCycle = dutyCycle
            runtime?.let { runtime ->
                val clock = max(runtime.timeToClock(time),lastClock)
                logger.info { "Set value=${dutyCycle}, time=$time, clock=$clock" }
                queue.send(
                    minClock = lastClock,
                    reqClock = clock,
                    data = queue.build("queue_digital_out oid=%c clock=%u on_ticks=%u") {
                        addId(id);addU(clock.toUInt());addU(dutyToTicks(_dutyCycle))
                    })
            }
        }
    }
}