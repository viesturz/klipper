package mcu.components

import io.github.oshai.kotlinlogging.KotlinLogging
import MachineTime
import DigitalOutPin
import Mcu
import mcu.McuComponent
import mcu.McuConfigure
import mcu.McuRuntime
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
        configure.configCommand("config_digital_out oid=%c pin=%u value=%c default_value=%c max_duration=%u") {
            addId(id);addEnum("pin", config.pin)
            addC((config.startValue > 0) != config.invert)
            addC((config.shutdownValue > 0) != config.invert)
            addU(configure.durationToClock(config.watchdogDuration))
        }
        configure.initCommand("update_digital_out oid=%c value=%c") {
            addId(id);addC(_enabled != config.invert)
        }
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
            queue.send(
                minClock = lastClock,
                reqClock = clock,
                data = queue.build("queue_digital_out oid=%c clock=%u on_ticks=%u") {
                    addId(id);addU(clock.toUInt());addC(_enabled != config.invert)
                })
        }
    }

    override fun setNow(value: Boolean) {
        val curClock = runtime.reactor.now
        val lastClock = queue.lastClock
        if (value != _enabled || config.watchdogDuration > 0) {
            _enabled = value
            queue.send(
                minClock = lastClock,
                reqClock = runtime.timeToClock(curClock),
                data = queue.build("update_digital_out oid=%c value=%c") {
                    addId(id);addC(_enabled != config.invert)
                })
        }
    }
}