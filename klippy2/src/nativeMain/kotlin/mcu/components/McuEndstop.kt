package mcu.components

import Endstop
import MachineTime
import Mcu
import config.DigitalInPin
import mcu.McuClock32
import mcu.McuComponent
import mcu.McuConfigure
import mcu.McuObjectResponse
import mcu.ObjectId
import mcu.ResponseParser

class McuEndstop(override val mcu: Mcu, val config: DigitalInPin, initialize: McuConfigure): Endstop, McuComponent {
    override val id = initialize.makeOid()
    private val queue = initialize.makeCommandQueue("McuEndstop ${config.pin}", 1)
    override var lastState: Boolean = false

    override fun configure(configure: McuConfigure) {
        configure.configCommand("config_endstop oid=%d pin=%s pull_up=%d") {
            addId(id)
            addPin(config.pin)
            addC(config.pullup)
        }
        // Reset homing polling
        configure.restartCommand("endstop_home oid=%c clock=%u sample_ticks=%u sample_count=%c rest_ticks=%u pin_value=%c trsync_oid=%c trigger_reason=%c") {
            addId(id);addC(0);addC(0);addC(0);addC(0);addC(0);addC(0);addC(0)
        }
    }

    fun reset() {
        queue.send("endstop_home oid=%c clock=%u sample_ticks=%u sample_count=%c rest_ticks=%u pin_value=%c trsync_oid=%c trigger_reason=%c") {
            addId(id);addC(0);addC(0);addC(0);addC(0);addC(0);addC(0);addC(0)
        }
    }

    override suspend fun queryState(): Boolean {
        val state = queue.sendWithResponse("endstop_query_state oid=%c", id = id, responesEndstopStateParser)
        lastState = state.pinValue
        if (config.invert) lastState = !lastState
        return lastState
    }
}

data class ResponseEndstopState(override val id: ObjectId, val time: MachineTime, val isHoming: Boolean, val nextWakeClock: McuClock32, val pinValue: Boolean) :
    McuObjectResponse
val responesEndstopStateParser = ResponseParser("endstop_state oid=%c homing=%c next_clock=%u pin_value=%c") {
    ResponseEndstopState(parseId(), receiveTime, parseBoolean(), parseClock(), parseBoolean())
}
