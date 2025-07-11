package mcu.components

import MachineTime
import config.StepperPins
import io.github.oshai.kotlinlogging.KotlinLogging
import StepperDriver
import StepperMotor
import mcu.McuClock32
import mcu.McuComponent
import mcu.McuConfigure
import mcu.McuImpl
import mcu.McuObjectCommand
import mcu.McuObjectResponse
import mcu.McuRuntime
import mcu.ObjectId
import mcu.PinName
import utils.RegisterMcuMessage

class McuStepperMotor(override val mcu: McuImpl, val config: StepperPins, override val driver: StepperDriver, configuration: McuConfigure) : StepperMotor,
    McuComponent {
    val id = configuration.makeOid()
    override val stepQueue = configuration.makeStepQueue(id)
    private lateinit var runtime: McuRuntime
    val logger = KotlinLogging.logger("McuStepperMotor ${config.stepPin.pin}")

    override fun configure(configure: McuConfigure) {
        var pulseTicks = configure.durationToClock(driver.pulseDuration)
        var invert: Byte = if (config.dirPin.invert) 1 else 0
        if (driver.stepBothEdges) {
            pulseTicks = 0u
            invert = -1
        }
        configure.configCommand(CommandConfigStepper(id, config.stepPin.pin,config.dirPin.pin, invert, pulseTicks))
        configure.restartCommand(CommandResetStepClock(id, 0u))
    }

    override fun start(runtime: McuRuntime) {
        this.runtime = runtime
    }

    override fun setPosition(time: MachineTime, pos: Long) {
        stepQueue.setLastPosition(runtime.timeToClock(time), pos)
    }

    override suspend fun getPosition(): Long {
        val position = runtime.defaultQueue.sendWithResponse<ResponseStepperGetPosition>(CommandStepperGetPosition(id))
        setPosition(position.time, position.position)
        return position.position
    }

    override fun step(startTime: MachineTime, direction: Int) {
        stepQueue.appendStep(direction, startTime, 0.0)
        stepQueue.commit()
    }
}

@RegisterMcuMessage(signature = "config_stepper oid=%c step_pin=%c dir_pin=%c invert_step=%c step_pulse_ticks=%u")
data class CommandConfigStepper(override val id: ObjectId, val stepPin: PinName, val dirPin: PinName, val invertStep: Byte, val stepPulseTicks: UInt): McuObjectCommand
@RegisterMcuMessage(signature = "reset_step_clock oid=%c clock=%u")
data class CommandResetStepClock(override val id: ObjectId, val clock: McuClock32): McuObjectCommand
@RegisterMcuMessage(signature = "stepper_get_position oid=%c")
data class CommandStepperGetPosition(override val id: ObjectId): McuObjectCommand
@RegisterMcuMessage(signature = "stepper_position oid=%c pos=%i")
data class ResponseStepperGetPosition(override val id: ObjectId, val position: Long, val time: MachineTime) : McuObjectResponse
@RegisterMcuMessage(signature = "stepper_stop_on_trigger oid=%c trsync_oid=%c")
data class CommandStepperStopOnTrigger(override val id: ObjectId, val trsyncId: ObjectId): McuObjectCommand
