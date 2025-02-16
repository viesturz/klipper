package parts.drivers

import MachineTime
import config.TmcAddressUartPins
import io.github.oshai.kotlinlogging.KotlinLogging
import MessageBus
import utils.crc8

private val NODE_SYNC: UByte = 0x05u
private val MCU_SYNC: UByte = 0xf5u
private val IFCNT_REG: UByte = 0x02u

@OptIn(ExperimentalStdlibApi::class)
class TmcUartNodeWithAddress(val config: TmcAddressUartPins, val bus: MessageBus) {
    private var writeCount: UByte? = null
    private var logger = KotlinLogging.logger("TmcUartNodeWithAddress ${config.uartPins.mcu.name} ${config.address}")

    suspend fun readRegister(registerAddr: UByte): UInt {
        return bus.transaction {
            val r = readLocked(registerAddr)
            r
        }
    }

    private suspend fun readLocked(registerAddr: UByte): UInt {
        val readCommand = ubyteArrayOf(MCU_SYNC, config.address.toUByte(),registerAddr)
        for (retries in 0..5) {
            val response = sendReplyWithCrc(readCommand, 8) ?: continue
            // The last byte is CRC
            if (response.size != 7) {
                logger.warn { "Response size is not 7, but ${response.size}" }
                continue
            }
            if (response[0] != NODE_SYNC || response[1] != 0xFFu.toUByte() || response[2] != registerAddr) {
                logger.warn { "Read response header invalid ${readCommand.toHexString()} -> ${response.toHexString()}" }
                continue
            }
            // 4 bytes of data, high to low.
            logger.trace { "Read successful ${readCommand.toHexString()} -> ${response.toHexString()}" }
            return (response[3].toUInt() shl 24) + (response[4].toUInt() shl 16) + (response[5].toUInt() shl 8) + response[6].toUInt()
        }
        bus.mcu.shutdown("Cannot read from TMC UART, register $registerAddr")
        throw IllegalStateException("Cannot read from TMC UART, register $registerAddr")
    }

    suspend fun writeRegister(registerAddr: UByte, data: UInt, time: MachineTime = 0.0) {
        val writeCommand = ubyteArrayOf(
            MCU_SYNC,
            config.address.toUByte(),
            registerAddr or 0x80u,
            (data shr 24).toUByte(),
            (data shr 16).toUByte(),
            (data shr 8).toUByte(),
            (data).toUByte())
        bus.transaction {
            for (retries in 0..5) {
                var success = false
                var writesBefore = writeCount
                if (writesBefore == null) {
                    writesBefore = readLocked(IFCNT_REG).toUByte()
                    writeCount = writesBefore
                }
                sendReplyWithCrc(writeCommand, 0, time)
                val writesAfter = readLocked(IFCNT_REG).toUByte()
                if (writesAfter == (writesBefore + 1u).toUByte()) {
                    writeCount = writesAfter
                    success = true
                } else {
                    logger.warn { "Write did not update ifcnt, retrying" }
                }
                if (success) {
                    logger.trace { "Write successful ${writeCommand.toHexString()}" }
                    return@transaction
                }
            }
            bus.mcu.shutdown("Failed to write TMC UART, register $registerAddr")
            throw IllegalStateException("Failed to write TMC UART, register $registerAddr")
        }
    }

    suspend fun sendReplyWithCrc(cmd: UByteArray, readBytes: Int, time: MachineTime = 0.0): UByteArray? {
        val reply = bus.sendReply(cmd + ubyteArrayOf(cmd.crc8()), readBytes, time)
        if (reply == null || readBytes == 0) return reply
        val crc = reply.last()
        val payload = reply.copyOfRange(0, reply.size-1)

        val calculatedCrc = payload.crc8()
        if (calculatedCrc != crc) {
            logger.warn { "Reply CRC error: $calculatedCrc != $crc" }
            return null
        }
        return payload
    }
}
