package mcu.components

import config.DigitalInPin
import machine.Reactor
import mcu.Button
import mcu.ButtonListener
import mcu.Mcu
import mcu.impl.McuComponent
import mcu.impl.McuConfigure
import mcu.impl.McuObjectResponse
import mcu.impl.McuRuntime
import mcu.impl.ObjectId
import mcu.impl.ResponseParser

class McuButton(override val mcu: Mcu, val config: DigitalInPin, configure: McuConfigure) : Button,
    McuComponent {
    private val id = configure.makeOid()
    private var listener: ButtonListener? = null
    private var reactor: Reactor? = null
    private var _pressed = false
    init {
        configure.configCommand("config_buttons oid=%c button_count=%c") {
                addId(id);addC(1u)
            }
        configure.configCommand("buttons_add oid=%c pos=%c pin=%u pull_up=%c") {
            addId(id);addC(1u);addEnum("pin", config.pin);addC(config.pullup == 1)
        }
    }

    override fun start(runtime: McuRuntime) {
        this.reactor = runtime.reactor
        runtime.responseHandler(responseButtonsStateParser, id, this::onButtonState)
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

data class ResponseButtonsState(override val id: ObjectId, val ackCount:UByte, val state: ByteArray):
    McuObjectResponse
val responseButtonsStateParser = ResponseParser("buttons_state oid=%c ack_count=%c state=%*s") {
    ResponseButtonsState(parseC(), parseC(), parseBytes())
}