package parts.kinematics

import Endstop
import EndstopSync
import StepperMotor

class HomingMove {
    var sync: EndstopSync? = null

    fun addStepper(stepper: StepperMotor) {
        
    }

    fun addEndstop(endstop: Endstop) {}

    fun start(): Unit {

    }

    suspend fun wait(): EndstopSync.State {

    }
}