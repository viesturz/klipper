package mcu.connection

import chelper.serialqueue_alloc_commandqueue
import chelper.serialqueue_free_commandqueue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toCValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import mcu.McuClock
import mcu.impl.CommandBuilder
import mcu.impl.GcWrapper
import mcu.impl.McuResponse
import mcu.impl.ObjectId
import mcu.impl.ResponseParser
import kotlin.time.Duration.Companion.seconds

/** Queue for sending commands to MCU. */
@OptIn(ExperimentalForeignApi::class)
class CommandQueue(var connection: McuConnection?, logName: String) {
    @OptIn(ExperimentalForeignApi::class)
    private val queue = GcWrapper(serialqueue_alloc_commandqueue()) { serialqueue_free_commandqueue(it) }
    var lastClock: McuClock = 0u
    private val logger = KotlinLogging.logger("CommandQueue $logName ")

    init {
        // TODO: Configure RECEIVE_WINDOW, etc.
    }

    /** Schedule to send a message to the MCU.
     * No earlier than minClock and at the order of reqClock. */
    @OptIn(ExperimentalStdlibApi::class)
    fun send(data: UByteArray, minClock: McuClock = 0u, reqClock: McuClock = 0u, ackId: ULong=0u) {
        val connection = this.connection ?:
            throw RuntimeException("Trying to send before setup is finished")
        logger.trace { "Sending ${data.toHexString()}, ackId=#$ackId" }
        chelper.serialqueue_send(connection.serial.ptr, queue.ptr,
            data.toCValues(), data.size, minClock, reqClock,  notify_id = ackId)
        lastClock = reqClock
    }

    suspend fun sendWaitAck(data: UByteArray) {
        val connection = this.connection ?:
            throw RuntimeException("Trying to send before setup is finished")
        var acked = CompletableDeferred<Unit>()
        val ackId = connection.registerMessageAckedCallback { acked.complete(Unit) }
        send(data, ackId=ackId)
        acked.await()
    }

    @OptIn(ExperimentalStdlibApi::class)
    suspend fun <ResponseType: McuResponse> sendWithResponse(data: UByteArray, parser: ResponseParser<ResponseType>, id: ObjectId = 0u): ResponseType {
        val connection = this.connection ?:
            throw RuntimeException("Trying to send before setup is finished")
        var wait = 0.01.seconds
        var received = connection.setResponseHandlerOnce(parser, id)
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

    /** Shorthand functions */
    suspend fun <ResponseType: McuResponse> sendWithResponse(signature: String, parser: ResponseParser<ResponseType>) = sendWithResponse(build(signature){}, parser, id = 0u)
    fun send(signature: String, minClock: McuClock = 0u, reqClock: McuClock = 0u, ackId: ULong=0u, function: CommandBuilder.()->Unit = {}) = send(build(signature, function), minClock = minClock, reqClock = reqClock, ackId = ackId)

    inline fun build(signature: String, block: CommandBuilder.()->Unit): UByteArray {
        val connection = this.connection ?:
            throw RuntimeException("Trying to send before setup is finished")
        return connection.commands.build(signature, block)
    }

}