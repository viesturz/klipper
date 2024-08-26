package mcu.components

import config.UartPins
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mcu.MessageBus
import mcu.impl.McuComponent
import mcu.impl.McuConfigure
import mcu.impl.McuImpl
import mcu.impl.McuObjectResponse
import mcu.impl.ObjectId
import mcu.impl.ResponseParser
import utils.crc8
import utils.decode8N1
import utils.encode8N1
import kotlin.math.ceil
import kotlin.math.roundToInt

class TmcUartBus(override val mcu: McuImpl, val config: UartPins, initialize: McuConfigure) : MessageBus,
    McuComponent {
    val id = initialize.makeOid()
    private val name = "TmcUartBus ${config.mcu.name} ${config.rxPin} ${config.txPin}"
    private val queue = initialize.makeCommandQueue(name)
    private val logger = KotlinLogging.logger(name)
    private val mutex = Mutex()

    override fun configure(configure: McuConfigure) {
        val baudTicks = configure.firmware.durationToTicks(1.0/config.baudRate)
        configure.configCommand("config_tmcuart oid=%c rx_pin=%u pull_up=%c tx_pin=%u bit_time=%u") {
            addId(id);
            addEnum("pin", config.rxPin.pin)
            addC(config.pullup)
            addEnum("pin", config.txPin.pin)
            addU(baudTicks)
        }
    }

    override suspend fun sendReply(data: UByteArray, readBytes: Int): UByteArray? {
        val encoded = encode8N1 {add(data)}
        val readEncodedBytes = ceil(readBytes * 10.0 / 8.0).roundToInt()
        val reply = queue.sendWithResponse(
            queue.build("tmcuart_send oid=%c write=%*s read=%c"){addId(id);addBytes(encoded);addC(readEncodedBytes.toUByte())},
            responseTmcuartParser, id).data
        if (readBytes == 0) return UByteArray(0)
        return reply.decode8N1()
    }

    override suspend fun transaction(function: suspend () -> Unit) {
        mutex.withLock {
            function()
        }
    }
}

data class ResponseTmcuart(override val id: ObjectId, val data: ByteArray) : McuObjectResponse
val responseTmcuartParser = ResponseParser("tmcuart_response oid=%c read=%*s") {
    ResponseTmcuart(parseC(), parseBytes())
}
