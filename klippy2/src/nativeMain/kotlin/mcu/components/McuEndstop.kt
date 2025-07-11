package mcu.components

import Endstop
import MachineTime
import Mcu
import config.DigitalInPin
import mcu.McuClock32
import mcu.McuComponent
import mcu.McuConfigure
import mcu.McuObjectCommand
import mcu.McuObjectResponse
import mcu.ObjectId
import mcu.PinName
import utils.RegisterMcuMessage

class McuEndstop(override val mcu: Mcu, val config: DigitalInPin, initialize: McuConfigure): Endstop, McuComponent {
    override val id = initialize.makeOid()
    private val queue = initialize.makeCommandQueue("McuEndstop ${config.pin}", 1)
    override var lastState: Boolean = false

    private val commandReset = CommandEndstopHome(id,
        clock = 0U,
        sampleTicks = 0U,
        sampleCount = 0U,
        restTicks = 0U,
        pinValue = false,
        trssyncId = 0U,
        triggerReason = 0u)

    override fun configure(configure: McuConfigure) {
        configure.configCommand(CommandConfigEndstop(id, config.pin, config.pullup))
        // Reset homing polling
        configure.restartCommand(commandReset)
    }

    fun reset() {
        queue.send(commandReset)
    }

    override suspend fun queryState(): Boolean {
        val state = queue.sendWithResponse<ResponseEndstopState>(CommandEndstopQuery(id))
        lastState = state.pinValue
        if (config.invert) lastState = !lastState
        return lastState
    }
}

@RegisterMcuMessage(signature = "config_endstop oid=%c pin=%c pull_up=%c")
data class CommandConfigEndstop(override val id: ObjectId, val pin: PinName, val pullUp: Boolean) : McuObjectCommand
@RegisterMcuMessage(signature = "endstop_home oid=%c clock=%u sample_ticks=%u sample_count=%c rest_ticks=%u pin_value=%c trsync_oid=%c trigger_reason=%c")
data class CommandEndstopHome(override val id: ObjectId, val clock: McuClock32, val sampleTicks: McuClock32, val sampleCount: UByte, val restTicks: McuClock32, val pinValue: Boolean, val trssyncId: ObjectId, val triggerReason: UByte) : McuObjectCommand
@RegisterMcuMessage(signature = "endstop_query_state oid=%c")
data class CommandEndstopQuery(override val id: ObjectId) :    McuObjectCommand
@RegisterMcuMessage(signature = "endstop_state oid=%c homing=%c next_clock=%u pin_value=%c")
data class ResponseEndstopState(override val id: ObjectId, val time: MachineTime, val isHoming: Boolean, val nextWakeClock: McuClock32, val pinValue: Boolean) :
    McuObjectResponse
