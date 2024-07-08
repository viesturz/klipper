package mcu.impl

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import platform.posix.close
import platform.posix.log
import kotlin.concurrent.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.reflect.KClass

/** Connection to MCU, primarily handles dispatch of messages received from MCU. */
@Suppress("OPT_IN_USAGE")
@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)
class McuConnection(val fd: Int) {
    private val responseHandlersOneshot = HashMap<Pair<String, ObjectId>, CompletableDeferred<McuResponse>>()
    private val responseHandlers = HashMap<Pair<String, ObjectId>, (message: McuResponse) -> Unit>()
    private val pendingAcks = HashMap<ULong, () -> Unit>()
    private var lastNotifyId = AtomicLong(0)
    var parser = CommandParser()
    val serial = GcWrapper(
        chelper.serialqueue_alloc(
            serial_fd = fd,
            serial_fd_type = 'u'.code.toByte(),
            client_id = 0
        )
    ) { chelper.serialqueue_free(it) }

    init {
        // TODO: printer scope
        GlobalScope.launch(Dispatchers.IO) { pullThread() }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun pullThread() {
        memScoped {
            val message = alloc<chelper.pull_queue_message>()
            while (true) {
                chelper.serialqueue_pull(serial.ptr, message.ptr)
                if (message.len < 0) break
                val data = message.msg.readBytes(message.len)
                println("Received len ${message.len} message ${data.toHexString()}, notify=${message.notify_id}, ")
                val parsed = parser.decode(data)
                if (parsed != null) {
                    val key = Pair(parsed::class.qualifiedName?:parsed::class.hashCode().toString(), parsed.id)
                    val handler = responseHandlers.get(key)
                    //TODO: schedule
                    handler?.let { it(parsed) }
                    val handler2 = responseHandlersOneshot.remove(key)
                    //TODO: schedule
                    handler2?.complete(parsed)
                    }
                // Acks after handlers - to allow handlers populate data before acks.
                val ack = pendingAcks.remove(message.notify_id)
                // TODO: Scheulde
                ack?.let { it() }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    suspend fun identify(chunkSize:UByte = 40u) {
        println("Identify")
        val queue = CommandQueue(this)
        val command = CommandBuilder(parser)
        var offset = 0
        val data = buildList<Byte> {
            while (true) {
                val response = queue.sendWithResponse(
                    command.identify(offset.toUInt(), chunkSize),
                    ResponseIdentify::class,
                    0u
                )
                if (response.offset.toInt() == offset) {
                    addAll(response.data.toList())
                    if (response.data.size < chunkSize.toInt()) {
                        break
                    }
                    offset += response.data.size
                }
            }
        }.toByteArray()
        println("Identify done, read ${data.size} bytes")
        parser = CommandParser(data)
    }

    fun disconnect() {
        chelper.serialqueue_exit(serial.ptr) // This will stop the tread as well
        close(fd)
        // TODO: notify listeners
    }

    fun registerMessageAckedCallback(function: (() -> Unit)): ULong {
        val n = lastNotifyId.incrementAndGet().toULong()
        pendingAcks[n] = function
        return n
    }

    @Suppress("UNCHECKED_CAST")
    fun <ResponseType: McuResponse> setResponseHandler(type :KClass<ResponseType>, id: ObjectId, handler: ((message: ResponseType) -> Unit)? ) {
        val key = Pair(type.qualifiedName?:type.hashCode().toString(), id)
        when {
            handler == null -> responseHandlers.remove(key)
            responseHandlers.containsKey(key) -> throw IllegalArgumentException("Duplicate message handler for $key")
            else -> responseHandlers[key] = handler as ((message: McuResponse) -> Unit)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <ResponseType: McuResponse> setResponseHandlerOnce(type :KClass<ResponseType>, id: ObjectId): CompletableDeferred<ResponseType> {
        val key = Pair(type.qualifiedName?:type.hashCode().toString(), id)
        val result = CompletableDeferred<ResponseType>()
        when {
            responseHandlersOneshot.containsKey(key) -> throw IllegalArgumentException("Duplicate message handler for $key")
            else -> responseHandlersOneshot[key] = result as CompletableDeferred<McuResponse>
        }
        return result
    }
}