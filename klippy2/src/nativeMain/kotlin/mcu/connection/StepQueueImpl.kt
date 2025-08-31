package mcu.connection

import MachineTime
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toCValues
import McuClock
import StepperMotor
import chelper.stepcompress_alloc
import chelper.stepcompress_free
import chelper.steppersync_alloc
import chelper.steppersync_free
import mcu.CommandBuffer
import mcu.CommandBuilder
import mcu.Commands
import mcu.FirmwareConfig
import mcu.GcWrapper
import mcu.McuClock32
import mcu.McuCommand
import mcu.McuObjectCommand
import mcu.ObjectId
import utils.RegisterMcuMessage

/** Queue for sending commands to MCU. */
@OptIn(ExperimentalForeignApi::class)
class StepQueueImpl(firmware: FirmwareConfig, var connection: McuConnection?, val id: ObjectId): StepperMotor.StepQueue {
    @OptIn(ExperimentalForeignApi::class)
    val stepcompress = GcWrapper(stepcompress_alloc(id.toUInt())) { stepcompress_free(it) }
    private val logger = KotlinLogging.logger("StepQueue $id ")

    init {
        val maxErrorSecs = 0.000025
        val maxErrorTicks = firmware.durationToTicks(maxErrorSecs)
        val commands = Commands(firmware)
        val stepCmdTag = commands.tagFor(CommandQueueStep::class)
        val dirCmdTag = commands.tagFor(CommandSetNextStepDir::class)
        chelper.stepcompress_fill(stepcompress.ptr, maxErrorTicks, stepCmdTag, dirCmdTag)
    }

//    suspend inline fun <reified ResponseType: McuResponse> sendWithResponse(command: McuCommand, retry: Double = 0.01) =
//        sendWithResponse(command, ResponseType::class, retry)
//
//    suspend fun <ResponseType: McuResponse> sendWithResponse(command: McuCommand, response: KClass<ResponseType>, retry: Double = 0.01): ResponseType {
//        val connection = this.connection ?: throw RuntimeException("Trying to send before setup is finished")
//        var wait = retry.seconds
//        val id = if (command is McuObjectCommand) command.id else 0u
//        val received = connection.setResponseHandlerOnce(response, id)
//        repeat(5) {
//            appendCommand(command)
//            try {
//                return withTimeout(wait) {
//                    received.await()
//                }
//            } catch (e: TimeoutCancellationException) {
//                delay(wait)
//                wait *= 2
//            }
//        }
//        throw RuntimeException("Timeout out waiting for response")
//    }

    fun appendCommand(command: McuCommand) {
        val connection = this.connection ?:
            throw RuntimeException("Trying to send before setup is finished")
        val buf = IntsCommandBuffer()
        connection.commands.build(buf, command)
        chelper.stepcompress_queue_msg(stepcompress.ptr, buf.ints.toUIntArray().toCValues(), buf.ints.size)
    }

    fun appendMoveCommand(clock: McuClock, command: McuCommand) {
        val connection = this.connection ?:
            throw RuntimeException("Trying to send before setup is finished")
        val buf = IntsCommandBuffer()
        connection.commands.build(buf, command)
        chelper.stepcompress_queue_mq_msg(stepcompress.ptr, clock, buf.ints.toUIntArray().toCValues(), buf.ints.size)
    }

    fun appendStep(dir: Int, moveTime: MachineTime, stepTime: MachineTime) {
        chelper.stepcompress_append(stepcompress.ptr, dir, moveTime, stepTime)
    }

    fun setLastPosition(clock: McuClock, pos: Long) {
        chelper.stepcompress_set_last_position(stepcompress.ptr, clock, pos)
    }

    fun findPastPosition(clock: McuClock): Long {
        return chelper.stepcompress_find_past_position(stepcompress.ptr, clock)
    }

    /** Clears the queued steps and resets. */
    fun reset() {
        chelper.stepcompress_reset(stepcompress.ptr, 0u)
    }

    // Probably don't need this.
    fun setInverted(invert: Boolean) {
        chelper.stepcompress_set_invert_sdir(stepcompress.ptr, if (invert) 1u else 0u)
    }
}

@OptIn(ExperimentalForeignApi::class)
class StepperSync(connection: McuConnection, stepQueue: StepQueueImpl, moveCount: Int) {
    val stepperSync = GcWrapper(steppersync_alloc(connection.serial.ptr, listOf(stepQueue.stepcompress.ptr).toCValues(), 1, moveCount)) {
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

class IntsCommandBuffer: CommandBuffer {
    val ints = ArrayList<UInt>()
    override fun addVLQ(v: Long) {
        ints.add(v.toUInt())
    }

    override fun addBytes(v: UByteArray) {
        throw IllegalStateException("Raw bytes not supported on an Step queue")
    }
}

@RegisterMcuMessage(signature = "queue_step oid=%c interval=%u count=%hu add=%hi")
data class CommandQueueStep(override val id: ObjectId, val interval: McuClock32, val count: UShort, val add: Short): McuObjectCommand
@RegisterMcuMessage(signature = "set_next_step_dir oid=%c dir=%c")
data class CommandSetNextStepDir(override val id: ObjectId, val dir: UByte): McuObjectCommand
