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
        configure.configCommand(CommandConfigDigitalOut(id, config.pin, config.startValue > 0, config.shutdownValue > 0,
            configure.durationToClock(config.watchdogDuration)))
        configure.initCommand(CommandSetDigitalOutPwmCycle(id, cycleTicks))
        configure.initCommand(CommandUpdateDigitalOutPwm(id, dutyToTicks(dutyCycle)))
    }

    private fun dutyToTicks(d: Double) = (d * cycleTicks.toDouble() + 0.5f).toUInt()

    override suspend fun start(runtime: McuRuntime) {
        this.runtime = runtime
    }

    override fun set(time: MachineTime, dutyCycle: Double, cycleTime: MachineDuration?) {
        val lastClock = queue.lastClock
        val cycleChange = setCycleTime(time, cycleTime)
        if (cycleChange || dutyCycle != _dutyCycle || config.watchdogDuration > 0) {
            _dutyCycle = dutyCycle
            val clock = max(runtime.timeToClock(time),queue.lastClock)
            queue.send(
                CommandQueueDigitalOut(id, clock.toUInt(), dutyToTicks(_dutyCycle)),
                minClock = lastClock,
                reqClock = clock)
        }
    }

    override fun setNow(dutyCycle: Double, cycleTime: MachineDuration?) {
        val curClock = runtime.reactor.now
        val lastClock = queue.lastClock
        val cycleChange = setCycleTime(curClock, cycleTime)
        if (cycleChange || dutyCycle != _dutyCycle || config.watchdogDuration > 0) {
            _dutyCycle = dutyCycle
            queue.send(
                CommandUpdateDigitalOutPwm(id, dutyToTicks(_dutyCycle)),
                minClock = lastClock,
                reqClock = runtime.timeToClock(curClock))
        }
    }

    private fun setCycleTime(time: MachineTime, cycleTime: MachineDuration?): Boolean {
        if (cycleTime == null || _cycleTime == cycleTime)  return false
        val lastClock = queue.lastClock
        _cycleTime = cycleTime
        cycleTicks = runtime.durationToClock(config.cycleTime)
        queue.send(
            CommandSetDigitalOutPwmCycle(id, cycleTicks),
            minClock = lastClock,
            reqClock = runtime.timeToClock(time))
        return true
    }
}
