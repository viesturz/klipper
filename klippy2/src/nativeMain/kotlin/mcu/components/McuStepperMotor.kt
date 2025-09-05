package mcu.components

import EndstopSync
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
import kotlin.math.absoluteValue

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

    override suspend fun start(runtime: McuRuntime) {
        this.runtime = runtime
        queryPosition()
    }

    override suspend fun queryPosition(): Long {
        val position = runtime.defaultQueue.sendWithResponse<ResponseStepperGetPosition>(CommandStepperGetPosition(id))
        stepQueue.setLastPosition(runtime.timeToClock(position.time), position.position)
        logger.debug { "Query position ${position.position}" }
        return position.position
    }

    override suspend fun getTriggerPosition(sync: EndstopSync): StepperMotor.TriggerPosition {
        val triggerClock = sync.getTriggerClock(mcu)
        val triggerPosition = stepQueue.findPastPosition(triggerClock)
        val stopPosition = queryPosition()
        require((triggerPosition - stopPosition).absoluteValue < 1000) { "Crazy trigger and stop position skew: $triggerPosition, $stopPosition" }
        return StepperMotor.TriggerPosition(triggerPosition, stopPosition)
    }

    override suspend fun clearQueuedSteps() {
        stepQueue.reset(0U)
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
