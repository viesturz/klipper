package mcu.components

import MachineTime
import config.StepperPins
import io.github.oshai.kotlinlogging.KotlinLogging
import StepperDriver
import StepperMotor
import mcu.McuComponent
import mcu.McuConfigure
import mcu.McuImpl
import mcu.McuObjectResponse
import mcu.McuRuntime
import mcu.ObjectId
import mcu.ResponseParser

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
        configure.configCommand("config_stepper oid=%c step_pin=%c dir_pin=%c invert_step=%c step_pulse_ticks=%u") {
            addId(id); addPin(config.stepPin.pin); addPin(config.dirPin.pin); addC(invert); addU(pulseTicks)
        }
        configure.restartCommand("reset_step_clock oid=%c clock=%u") {
            addId(id); addU(0u)
        }
    }

    override fun start(runtime: McuRuntime) {
        this.runtime = runtime
    }

    override fun setPosition(time: MachineTime, pos: Long) {
        stepQueue.setLastPosition(runtime.timeToClock(time), pos)
    }

    override suspend fun getPosition(): Long {
        val position = runtime.defaultQueue.sendWithResponse(runtime.defaultQueue.build("stepper_get_position oid=%c") {addId(id) }, responseStepperGetPositionParser, id=id)
        setPosition(position.time, position.position)
        return position.position
    }

    override fun step(startTime: MachineTime, direction: Int) {
        stepQueue.appendStep(direction, startTime, 0.0)
        stepQueue.commit()
    }
}

data class ResponseStepperGetPosition(override val id: ObjectId, val time: MachineTime, val position: Long) :
    McuObjectResponse
val responseStepperGetPositionParser = ResponseParser("stepper_position oid=%c pos=%i") {
    ResponseStepperGetPosition(parseC(), receiveTime, parseL())
}
