package mcu.components

import MachineDuration
import io.github.oshai.kotlinlogging.KotlinLogging
import MachineTime
import config.DigitalOutPin
import Mcu
import PwmPin
import mcu.McuClock32
import mcu.McuComponent
import mcu.McuConfigure
import mcu.McuRuntime
import kotlin.math.max

class McuPwmPin(override val mcu: Mcu, val config: DigitalOutPin, initialize: McuConfigure) : PwmPin,
    McuComponent {
    val id = initialize.makeOid()
    val queue = initialize.makeCommandQueue("McuPwmPin ${config.pin}", 3)
    var _dutyCycle = config.startValue
    var _cycleTime = config.cycleTime
    var cycleTicks: McuClock32 = 0u
    private val logger = KotlinLogging.logger("McuPwmPin ${config.pin}")

    private lateinit var runtime: McuRuntime

    override val dutyCycle: Double
        get() = _dutyCycle
    override val cycleTime: MachineDuration
        get() = _cycleTime

    override fun configure(configure: McuConfigure) {
        logger.trace { "Configure" }
        cycleTicks = configure.durationToClock(config.cycleTime)
        configure.configCommand("config_digital_out oid=%c pin=%u value=%c default_value=%c max_duration=%u") {
            addId(id);addEnum("pin", config.pin)
            addC(config.startValue > 0)
            addC(config.shutdownValue > 0)
            addU(configure.durationToClock(config.watchdogDuration))
        }
        configure.initCommand("set_digital_out_pwm_cycle oid=%c cycle_ticks=%u") {
            addId(id);addU(cycleTicks)
        }
        configure.initCommand("update_digital_out_pwm oid=%c on_ticks=%u") {
            addId(id);addU(dutyToTicks(dutyCycle))
        }
    }

    private fun dutyToTicks(d: Double) = (d * cycleTicks.toDouble() + 0.5f).toUInt()

    override fun start(runtime: McuRuntime) {
        this.runtime = runtime
    }

    override fun set(time: MachineTime, dutyCycle: Double, cycleTime: MachineDuration?) {
        val lastClock = queue.lastClock
        val cycleChange = setCycleTime(time, cycleTime)
        if (cycleChange || dutyCycle != _dutyCycle || config.watchdogDuration > 0) {
            _dutyCycle = dutyCycle
            val clock = max(runtime.timeToClock(time),queue.lastClock)
            queue.send(
                minClock = lastClock,
                reqClock = clock,
                data = queue.build("queue_digital_out oid=%c clock=%u on_ticks=%u") {
                    addId(id);addU(clock.toUInt());addU(dutyToTicks(_dutyCycle))
                })
        }
    }

    override fun setNow(dutyCycle: Double, cycleTime: MachineDuration?) {
        val curClock = runtime.reactor.now
        val lastClock = queue.lastClock
        val cycleChange = setCycleTime(curClock, cycleTime)
        if (cycleChange || dutyCycle != _dutyCycle || config.watchdogDuration > 0) {
            _dutyCycle = dutyCycle
            queue.send(
                minClock = lastClock,
                reqClock = runtime.timeToClock(curClock),
                data = queue.build("update_digital_out_pwm oid=%c on_ticks=%u") {
                    addId(id);addU(dutyToTicks(_dutyCycle))
                })
        }
    }

    private fun setCycleTime(time: MachineTime, cycleTime: MachineDuration?): Boolean {
        if (cycleTime == null || _cycleTime == cycleTime)  return false
        val lastClock = queue.lastClock
        _cycleTime = cycleTime
        cycleTicks = runtime.durationToClock(config.cycleTime)
        queue.send(
            minClock = lastClock,
            reqClock = runtime.timeToClock(time),
            data = queue.build("set_digital_out_pwm_cycle oid=%c cycle_ticks=%u") {
                addId(id);addU(cycleTicks)
            })
        return true
    }

}