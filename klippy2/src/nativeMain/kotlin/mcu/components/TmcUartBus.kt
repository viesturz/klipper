package mcu.components

import MachineTime
import config.UartPins
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import MessageBus
import mcu.McuComponent
import mcu.McuConfigure
import mcu.McuImpl
import mcu.McuObjectCommand
import mcu.McuObjectResponse
import mcu.McuRuntime
import mcu.ObjectId
import mcu.PinName
import utils.RegisterMcuMessage
import utils.decode8N1
import utils.encode8N1
import kotlin.math.ceil
import kotlin.math.roundToInt

val BACKGROUND_PRIORITY_CLOCK = 0x7fffffff00000000u

@OptIn(ExperimentalStdlibApi::class)
class TmcUartBus(override val mcu: McuImpl, val config: UartPins, initialize: McuConfigure) : MessageBus,
    McuComponent {
    val id = initialize.makeOid()
    private val name = "TmcUartBus ${config.mcu.name} ${config.rxPin.pin} ${config.txPin.pin}"
    private val queue = initialize.makeCommandQueue(name, 1)
    private val logger = KotlinLogging.logger(name)
    private val mutex = Mutex()
    private lateinit var runtime: McuRuntime

    override fun configure(configure: McuConfigure) {
        val baudTicks = configure.firmware.durationToTicks(1.0/config.baudRate)
        configure.configCommand(CommandConfigTmcUart(
            id = id,
            rxPin = config.rxPin.pin,
            pullUp = config.pullup,
            txPin = config.txPin.pin,
            bitTime = baudTicks))
    }

    override suspend fun start(runtime: McuRuntime) {
        this.runtime = runtime
    }

    override suspend fun sendReply(data: UByteArray, readBytes: Int, sendTime: MachineTime): UByteArray? {
        val encoded = encode8N1 {add(data)}
        val readEncodedBytes = ceil(readBytes * 10.0 / 8.0).roundToInt()
        val reply = queue.sendWithResponse<ResponseTmcuart>(CommandTmcUartSend(id, encoded, readEncodedBytes.toUByte()),
            retry = 0.1, // Uart is slower to respond, so wait a bit longer
            minClock = if (sendTime == 0.0) 0u else runtime.timeToClock(sendTime),
            reqClock = BACKGROUND_PRIORITY_CLOCK,
            ).data
        if (readBytes > 0 && reply.isEmpty()) return null // Read timeout
        val result = reply.decode8N1()
        logger.trace { "SendReply ${data.toHexString()} enc ${encoded.toHexString()} -> ${result.toHexString()}" }
        return result
    }

    override suspend fun <ResultType>  transaction(function: suspend () -> ResultType): ResultType = mutex.withLock {
            function()
        }
}

@RegisterMcuMessage(signature = "config_tmcuart oid=%c rx_pin=%u pull_up=%c tx_pin=%u bit_time=%u")
data class CommandConfigTmcUart(override val id: ObjectId, val rxPin: PinName, val pullUp: Boolean, val txPin: PinName, val bitTime: UInt): McuObjectCommand
@RegisterMcuMessage(signature = "tmcuart_send oid=%c write=%*s read=%c")
data class CommandTmcUartSend(override val id: ObjectId, val write: UByteArray, val read: UByte): McuObjectCommand
@RegisterMcuMessage(signature = "tmcuart_response oid=%c read=%*s")
data class ResponseTmcuart(override val id: ObjectId, val data: ByteArray) : McuObjectResponse
