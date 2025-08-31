package mcu.components

import config.DigitalInPin
import machine.Reactor
import Button
import ButtonListener
import Mcu
import mcu.McuComponent
import mcu.McuConfigure
import mcu.McuObjectCommand
import mcu.McuObjectResponse
import mcu.McuRuntime
import mcu.ObjectId
import mcu.PinName
import mcu.responseHandler
import utils.RegisterMcuMessage

class McuButton(override val mcu: Mcu, val config: DigitalInPin, configure: McuConfigure) : Button,
    McuComponent {
    private val id = configure.makeOid()
    private var listener: ButtonListener? = null
    private var reactor: Reactor? = null
    private var _pressed = false
    init {
        configure.configCommand(CommandConfigButtons(id, 1u))
        configure.configCommand(CommandButtonsAdd(id, 1u, config.pin, config.pullup))
    }

    override suspend fun start(runtime: McuRuntime) {
        this.reactor = runtime.reactor
        runtime.responseHandler<ResponseButtonsState>(id, this::onButtonState)
    }

    private suspend fun onButtonState(state: ResponseButtonsState) {
        println("ButtonsState: $state")
        // TODO: update pressed
        listener?.let { l ->
            l(this@McuButton)
        }
    }

    override val pressed: Boolean
        get() = _pressed

    override fun setListener(handler: ButtonListener?) {
        listener = handler
    }
}

@RegisterMcuMessage(signature = "config_buttons oid=%c button_count=%c")
data class CommandConfigButtons(override val id: ObjectId, val buttonCount:UByte): McuObjectCommand
@RegisterMcuMessage(signature = "buttons_add oid=%c pos=%c pin=%u pull_up=%c")
data class CommandButtonsAdd(override val id: ObjectId, val pos:UByte, val pin: PinName, val pullUp: Boolean): McuObjectCommand
@RegisterMcuMessage(signature = "buttons_state oid=%c ack_count=%c state=%*s")
data class ResponseButtonsState(override val id: ObjectId, val ackCount:UByte, val state: ByteArray):McuObjectResponse
