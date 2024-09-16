package mcu.connection

import MachineTime
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toCValues
import mcu.McuClock
import mcu.impl.CommandBuffer
import mcu.impl.CommandBuilder
import mcu.impl.FirmwareConfig
import mcu.impl.GcWrapper
import mcu.impl.ObjectId

/** Queue for sending commands to MCU. */
@OptIn(ExperimentalForeignApi::class)
class StepQueue(firmware: FirmwareConfig, var connection: McuConnection?, val id: ObjectId) {
    @OptIn(ExperimentalForeignApi::class)
    val stepcompress = GcWrapper(chelper.stepcompress_alloc(id.toUInt())) { chelper.stepcompress_free(it) }
    private val logger = KotlinLogging.logger("StepQueue $id ")

    init {
        val maxErrorSecs = 0.000025
        val maxErrorTicks = firmware.durationToTicks(maxErrorSecs)
        val stepCmdTag = firmware.getCommandTag("queue_step oid=%c interval=%u count=%hu add=%hi")
        val dirCmdTag = firmware.getCommandTag("set_next_step_dir oid=%c dir=%c")
        chelper.stepcompress_fill(stepcompress.ptr, maxErrorTicks, stepCmdTag, dirCmdTag)
    }

    fun appendCommand(signature: String, block: CommandBuilder.()->Unit) {
        val connection = this.connection ?:
            throw RuntimeException("Trying to send before setup is finished")
        val buf = IntsCommandBuffer()
        connection.commands.build(buf, signature, block)
        chelper.stepcompress_queue_msg(stepcompress.ptr, buf.ints.toUIntArray().toCValues(), buf.ints.size)
    }

    fun appendMoveCommand(clock: McuClock, signature: String, block: CommandBuilder.()->Unit) {
        val connection = this.connection ?:
            throw RuntimeException("Trying to send before setup is finished")
        val buf = IntsCommandBuffer()
        connection.commands.build(buf, signature, block)
        chelper.stepcompress_queue_mq_msg(stepcompress.ptr, clock, buf.ints.toUIntArray().toCValues(), buf.ints.size)
    }

    fun appendStep(dir: Int, moveTime: MachineTime, stepTime: MachineTime) {
        chelper.stepcompress_append(stepcompress.ptr, dir, moveTime, stepTime)
    }

    fun setLastPosition(clock: McuClock, pos: Long) {
        chelper.stepcompress_set_last_position(stepcompress.ptr, clock, pos)
    }

    fun commit() {
        chelper.stepcompress_commit(stepcompress.ptr)
    }

    // Probably don't need this.
    fun setInverted(invert: Boolean) {
        chelper.stepcompress_set_invert_sdir(stepcompress.ptr, if (invert) 1u else 0u)
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