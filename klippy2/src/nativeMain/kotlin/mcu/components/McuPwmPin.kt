package mcu.components

import MachineDuration
import io.github.oshai.kotlinlogging.KotlinLogging
import MachineTime
import kotlinx.coroutines.launch
import mcu.Mcu
import mcu.PwmPin
import mcu.impl.McuClock32
import mcu.impl.McuComponent
import mcu.impl.McuConfigure
import mcu.impl.McuRuntime
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

    override val dutyCycle: Double
        get() = _dutyCycle
    override val cycleTime: MachineDuration
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

    private fun dutyToTicks(d: Double) = (d * cycleTicks.toDouble() + 0.5f).toUInt()

    override fun set(time: MachineTime, dutyCycle: Double, cycleTime: MachineDuration?) {
        val lastClock = queue.lastClock
        var cycleChange = setCycleTime(time, cycleTime)
        if (cycleChange || dutyCycle != _dutyCycle) {
            _dutyCycle = dutyCycle
            runtime?.let { runtime ->
                val clock = max(runtime.timeToClock(time),queue.lastClock)
                logger.info { "Set value=${dutyCycle}, time=$time, clock=$clock" }
                runtime.reactor.scope.launch {
                    logger.info { "Sending PWM cmd" }
                    queue.sendWaitAck(
                        minClock = lastClock,
                        reqClock = clock,
                        data = queue.build("queue_digital_out oid=%c clock=%u on_ticks=%u") {
                            addId(id);addU(clock.toUInt());addU(dutyToTicks(_dutyCycle))
                        })
                    logger.info { "Acked PWM cmd" }
                }
            }
        }
    }

    override fun setNow(dutyCycle: Double, cycleTime: MachineDuration?) {
        val lastClock = queue.lastClock
        val curClock = runtime?.reactor?.now ?: 0.0
        var cycleChange = setCycleTime(curClock, cycleTime)
        if (cycleChange || dutyCycle != _dutyCycle) {
            _dutyCycle = dutyCycle
            runtime?.let { runtime ->
                logger.info { "Set value=${dutyCycle}" }
                queue.send(
                    minClock = lastClock,
                    reqClock = runtime.timeToClock(curClock),
                    data = queue.build("set_digital_out_pwm oid=%c on_ticks=%u") {
                        addId(id);addU(dutyToTicks(_dutyCycle))
                    })
            }
        }
    }

    private fun setCycleTime(time: MachineTime, cycleTime: MachineDuration?): Boolean {
        if (cycleTime == null || _cycleTime == cycleTime)  return false
        val lastClock = queue.lastClock
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
        return true
    }

}