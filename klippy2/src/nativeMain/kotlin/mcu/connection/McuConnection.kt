package mcu.connection

import chelper.serialqueue_alloc
import chelper.serialqueue_free
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import machine.Reactor
import McuClock
import mcu.Commands
import mcu.GcWrapper
import mcu.FirmwareConfig
import mcu.McuObjectResponse
import mcu.McuResponse
import mcu.ObjectId
import mcu.ResponseParser
import platform.posix.close
import kotlin.concurrent.AtomicLong

private val logger = KotlinLogging.logger("McuConnection")

/** Connection to MCU, primarily handles dispatch of messages received from MCU. */
@OptIn(ExperimentalForeignApi::class)
class McuConnection(val fd: Int, val reactor: Reactor) {
    private val responseHandlersOneshot = HashMap<Pair<String, ObjectId>, CompletableDeferred<McuResponse>>()
    private val responseHandlers = HashMap<Pair<String, ObjectId>, suspend (message: McuResponse) -> Unit>()
    private val pendingAcks = HashMap<ULong, CompletableDeferred<Unit>>()
    private var lastNotifyId = AtomicLong(0)
    var commands = Commands(FirmwareConfig.DEFAULT_IDENTIFY)
    val serial = GcWrapper(
        serialqueue_alloc(
            serial_fd = fd,
            serial_fd_type = 'u'.code.toByte(),
            client_id = 0
        )
    ) { serialqueue_free(it) }

    init {
        reactor.scope.launch(Dispatchers.IO) { pullThread() }
    }

    fun pullThread() {
        memScoped {
            val message = alloc<chelper.pull_queue_message>()
            while (true) {
                chelper.serialqueue_pull(serial.ptr, message.ptr)
                if (message.len < 0) break
                val data = message.msg.readBytes(message.len)
                val parseResult = commands.parse(data, message.sent_time, message.receive_time)
                if (parseResult != null) {
                    val (signature, parsed) = parseResult
                    logger.trace { "Received $parsed" }
                    val id = if (parsed is McuObjectResponse) parsed.id else 0u
                    val key = Pair(signature, id)
                    val handler = responseHandlers[key]
                    handler?.let { reactor.launch { it(parsed) } }
                    val handler2 = responseHandlersOneshot.remove(key)
                    handler2?.complete(parsed)
                } else {
                    logger.trace { "Received notify ack for ${message.notify_id}"}
                }
                // Acks after handlers - to allow handlers populate data before acks.
                val ack = pendingAcks.remove(message.notify_id)
                ack?.complete(Unit)
            }
        }
    }

    suspend fun identify(chunkSize:UByte = 40u): Commands {
        logger.debug { "Identify" }
        val queue = CommandQueue(this, "Identify")
        var offset = 0
        val data = buildList<Byte> {
            while (true) {
                val response = queue.sendWithResponse(
                    commands.build("identify offset=%u count=%c") {
                        addU(offset.toUInt()); addC(chunkSize)
                    },
                    responseIdentifyParser)
                if (response.offset.toInt() == offset) {
                    addAll(response.data.toList())
                    if (response.data.size < chunkSize.toInt()) {
                        break
                    }
                    offset += response.data.size
                }
            }
        }.toByteArray()
        logger.debug { "Identify done, read ${data.size} bytes" }
        commands = Commands(FirmwareConfig.parse(data))
        return commands
    }

    fun setClockEstimate(frequency: Double, convTime: Double, convClock: McuClock, lastClock: McuClock) {
        chelper.serialqueue_set_clock_est(this.serial.ptr, frequency, convTime, convClock, lastClock)
    }

    fun dumpCmds() {
        memScoped {
            val sentBuf = allocArray<chelper.pull_queue_message>(1024)
            val recBuf = allocArray<chelper.pull_queue_message>(1024)
            val sentCount = chelper.serialqueue_extract_old(serial.ptr, 1, sentBuf, 1024 )
            val recCount = chelper.serialqueue_extract_old(serial.ptr, 0, recBuf, 1024 )

            val all = buildList {
                for (i in 0..<sentCount) {
                    val m = sentBuf[i]
                    add(m.sent_time to "Sent: ${m.sent_time}, ${commands.dumpCommand(m.msg.readBytes(m.len))}")
                }
                for (i in 0..<recCount) {
                    val m = recBuf[i]
                    add(m.receive_time to "Received: ${m.sent_time} -> ${m.receive_time}, ${commands.dumpResponse(m.msg.readBytes(m.len))}")
                }
            }

            logger.info { "Dumping send/receive history" }
            all.sortedBy { p -> p.first }.forEach { logger.info { it.second } }
        }
    }

    fun disconnect() {
        chelper.serialqueue_exit(serial.ptr) // This will stop the tread as well
        close(fd)
    }

    fun registerMessageAckedCallback(): Pair<Deferred<Unit>, ULong> {
        val n = lastNotifyId.incrementAndGet().toULong()
        val deferred = CompletableDeferred<Unit>()
        pendingAcks[n] = deferred
        return deferred to n
    }

    @Suppress("UNCHECKED_CAST")
    fun <ResponseType: McuResponse> setResponseHandler(parser: ResponseParser<ResponseType>, id: ObjectId, handler: (suspend (message: ResponseType) -> Unit)? ) {
        val key = Pair(parser.signature, id)
        when {
            handler == null -> responseHandlers.remove(key)
            responseHandlers.containsKey(key) -> throw IllegalArgumentException("Duplicate message handler for $key")
            else -> {
                commands.registerParser(parser)
                responseHandlers[key] = handler as (suspend (message: McuResponse) -> Unit)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <ResponseType: McuResponse> setResponseHandlerOnce(parser: ResponseParser<ResponseType>, id: ObjectId): CompletableDeferred<ResponseType> {
        val key = Pair(parser.signature, id)
        val result = CompletableDeferred<ResponseType>()
        when {
            responseHandlersOneshot.containsKey(key) -> throw IllegalArgumentException("Duplicate message handler for $key")
            else -> {
                commands.registerParser(parser)
                responseHandlersOneshot[key] = result as CompletableDeferred<McuResponse>
            }
        }
        return result
    }

    suspend fun restartMcu() {
        // TODO
    }
}

data class ResponseIdentify(val offset: UInt, val data: ByteArray) : McuResponse
val responseIdentifyParser = ResponseParser("identify_response offset=%u data=%.*s",
    {ResponseIdentify(parseU(),parseBytes())})
