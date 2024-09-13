package mcu.components

import MachineTime
import config.StepperPins
import kotlinx.cinterop.ExperimentalForeignApi
import mcu.McuDuration
import mcu.StepperMotor
import mcu.impl.GcWrapper
import mcu.impl.McuComponent
import mcu.impl.McuConfigure
import mcu.impl.McuImpl
import mcu.impl.McuRuntime

@OptIn(ExperimentalForeignApi::class)
class McuStepperMotor(override val mcu: McuImpl, val config: StepperPins, configuration: McuConfigure) : StepperMotor, McuComponent {
    val id = configuration.makeOid()
    override val stepcompress = GcWrapper(chelper.stepcompress_alloc(id.toUInt())) { chelper.stepcompress_free(it) }
    private lateinit var runtime: McuRuntime

    override fun configure(configure: McuConfigure) {
        val pulseTicks = configure.durationToClock(config.pulseDuration)
        configure.configCommand("config_stepper oid=%c step_pin=%c dir_pin=%c invert_step=%c step_pulse_ticks=%u") {
            addId(id); addPin(config.stepPin.pin); addPin(config.dirPin.pin); addC(config.stepPin.invert); addU(pulseTicks)
        }
        configure.restartCommand("reset_step_clock oid=%c clock=%u") {
            addId(id); addU(0u)
        }
    }

    override fun start(runtime: McuRuntime) {
        this.runtime = runtime
    }

    override fun resetClock() {

    }

    override fun setPosition(pos: Int) {
        TODO("Not yet implemented")
    }
    override suspend fun getPosition(): Int {
        return 0
    }

    override fun move(startTime: MachineTime, steps: Int, interval: McuDuration, intervalAdd: McuDuration) {
        // chelper.stepcompress_queue_mq_msg(stepcompress, )
        TODO("Not yet implemented")
    }

}