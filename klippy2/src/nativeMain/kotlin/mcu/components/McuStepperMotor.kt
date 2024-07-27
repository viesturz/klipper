package mcu.components

import config.StepperPins
import mcu.McuDuration
import mcu.StepperMotor
import mcu.impl.McuComponent
import mcu.impl.McuConfigure
import mcu.impl.McuConfigureImpl
import mcu.impl.McuImpl

class McuStepperMotor(override val mcu: McuImpl, val config: StepperPins, configuration: McuConfigureImpl) : StepperMotor, McuComponent {

    override fun configure(configure: McuConfigure) {

    }

    override fun resetClock() {
        TODO("Not yet implemented")
    }

    override fun queueMove(steps: Int, interval: McuDuration, add: McuDuration) {
        TODO("Not yet implemented")
    }

    override suspend fun getPosition() {
        TODO("Not yet implemented")
    }
}