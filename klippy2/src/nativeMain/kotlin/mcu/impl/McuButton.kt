package mcu.impl

import machine.impl.Reactor
import mcu.Button
import mcu.ButtonListener
import mcu.Mcu

class McuButton(override val mcu: Mcu, val config: config.DigitalInPin, configure: McuConfigure) : Button, McuComponent {
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
        configure.responseHandler(responseButtonsStateParser, id, this::onButtonState)
    }

    override fun start(runtime: McuRuntime) {
        this.reactor = runtime.reactor
    }

    private fun onButtonState(state: ResponseButtonsState) {
        println("ButtonsState: $state")
        // TODO: update pressed
        listener?.let { l ->
            reactor?.runNow{l(this)}
        }
    }

    override val pressed: Boolean
        get() = _pressed

    override fun setListener(handler: ButtonListener?) {
        listener = handler
    }
}

data class ResponseButtonsState(override val id: ObjectId, val ackCount:UByte, val state: ByteArray): McuObjectResponse
val responseButtonsStateParser = ResponseParser("buttons_state oid=%c ack_count=%c state=%*s") {
    ResponseButtonsState(parseC(), parseC(), parseBytes())
}