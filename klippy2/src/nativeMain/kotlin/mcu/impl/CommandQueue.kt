package mcu.impl

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toCValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.seconds

/** Queue for sending commands to MCU. */
@OptIn(ExperimentalForeignApi::class)
class CommandQueue(val connection: McuConnection) {
    @OptIn(ExperimentalForeignApi::class)
    val queue = GcWrapper(chelper.serialqueue_alloc_commandqueue()) { chelper.serialqueue_free_commandqueue(it) }

    @OptIn(ExperimentalStdlibApi::class)
    fun send(data: UByteArray, minClock: McuClock64 = 0u, reqClock: McuClock64 = 0u, ackId: ULong=0u) {
        println("Sending ${data.toHexString()}, ackId=#$ackId")
        chelper.serialqueue_send(connection.serial.ptr, queue.ptr,
            data.toCValues(), data.size, minClock, reqClock,  notify_id = ackId)

    }

    suspend fun sendWaitAck(data: UByteArray) {
        var acked = CompletableDeferred<Unit>()
        val ackId = connection.registerMessageAckedCallback { acked.complete(Unit) }
        send(data, ackId=ackId)
        acked.await()
    }

    @OptIn(ExperimentalStdlibApi::class)
    suspend fun <ResponseType: McuResponse> sendWithResponse(data: UByteArray, type : KClass<ResponseType>, id: ObjectId): ResponseType {
        println("SendWithResponse ${data.toHexString()}")
        var wait = 0.01.seconds
        var received = connection.setResponseHandlerOnce(type, id)
        for (retry in (1..5)) {
            val acked = CompletableDeferred<Unit>()
            val ackId = connection.registerMessageAckedCallback { acked.complete(Unit) }
            send(data, ackId=ackId)
            acked.await()
            if (received.isCompleted) {
                return received.await()
            }
            delay(wait)
            wait *= 2
        }
        throw RuntimeException("Timeout out waiting for response")
    }
}