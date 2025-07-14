package mcu.connection

import chelper.serialqueue_alloc_commandqueue
import chelper.serialqueue_free_commandqueue
import chelper.steppersync_free
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toCValues
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import McuClock
import chelper.steppersync_alloc
import mcu.GcWrapper
import mcu.McuCommand
import mcu.McuObjectCommand
import mcu.McuResponse
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.seconds

/** Queue for sending commands to MCU. */
@OptIn(ExperimentalForeignApi::class, ExperimentalStdlibApi::class)
class CommandQueue(var connection: McuConnection?, logName: String) {
    @OptIn(ExperimentalForeignApi::class)
    val queue = GcWrapper(serialqueue_alloc_commandqueue()) { serialqueue_free_commandqueue(it) }
    var lastClock: McuClock = 0u
    private val logger = KotlinLogging.logger("CommandQueue $logName ")

    init {
        // TODO: Configure RECEIVE_WINDOW, etc.
    }

    /** Schedule to send a message to the MCU.
     * No earlier than minClock and at the order of reqClock. */
    fun send(message: McuCommand, minClock: McuClock = 0u, reqClock: McuClock = 0u, ackId: ULong=0u) {
        val connection = this.connection ?:
        throw RuntimeException("Trying to send before setup is finished")
        send(connection.commands.build(message), minClock, reqClock, ackId)
    }

    fun send(data: UByteArray, minClock: McuClock = 0u, reqClock: McuClock = 0u, ackId: ULong=0u) {
        val connection = this.connection ?:
            throw RuntimeException("Trying to send before setup is finished")
        logger.trace {  "Sending ${data.toHexString()}, ackId=#$ackId" }
        chelper.serialqueue_send(connection.serial.ptr, queue.ptr,
            data.toCValues(), data.size, minClock, reqClock,  notify_id = ackId)
        lastClock = reqClock
    }

    suspend fun sendWaitAck(message: McuCommand, minClock: McuClock =0u, reqClock: McuClock = 0u) {
        val connection = this.connection ?:
            throw RuntimeException("Trying to send before setup is finished")
        val (ackDeferred, ackId) = connection.registerMessageAckedCallback()
        send(message, minClock, reqClock, ackId=ackId)
        ackDeferred.await()
    }

    suspend fun <ResponseType: McuResponse> sendWithResponse(command: McuCommand, response: KClass<ResponseType>, retry: Double = 0.01, minClock: McuClock = 0u, reqClock: McuClock = 0u): ResponseType {
        val connection = this.connection ?:
            throw RuntimeException("Trying to send before setup is finished")
        var wait = retry.seconds
        val id = if (command is McuObjectCommand) command.id else 0u
        val received = connection.setResponseHandlerOnce(response, id)
        for (retry in (1..5)) {
            sendWaitAck(command, minClock = minClock, reqClock = reqClock)
            try {
                return withTimeout(wait) {
                    received.await()
                }
            } catch (e: TimeoutCancellationException) {
                delay(wait)
                wait *= 2
            }
        }
        throw RuntimeException("Timeout out waiting for response")
    }

    suspend inline fun <reified ResponseType: McuResponse> sendWithResponse(command: McuCommand, retry: Double = 0.01, minClock: McuClock = 0u, reqClock: McuClock = 0u) =
        sendWithResponse(command, ResponseType::class, retry, minClock, reqClock)
}

@OptIn(ExperimentalForeignApi::class)
class StepperSync(connection: McuConnection, stepQueues: List<StepQueueImpl>, moveCount: Int) {
    val stepperSync = GcWrapper(
        steppersync_alloc(connection.serial.ptr,
        stepQueues.map { it.stepcompress.ptr }.toCValues(),
        stepQueues.size,
        moveCount)
    ) {
        steppersync_free(it)
    }

    fun flushMoves(clock: McuClock, clearHistoryClock: McuClock) {
        val ret = chelper.steppersync_flush(stepperSync.ptr, clock, clearHistoryClock)
        if (ret != 0) {
            throw IllegalStateException("steppersync_flush failed ret=$ret")
        }
    }

    fun setTime(convTime: Double, frequency: Double) {
        chelper.steppersync_set_time(stepperSync.ptr, convTime, frequency)
    }
}
