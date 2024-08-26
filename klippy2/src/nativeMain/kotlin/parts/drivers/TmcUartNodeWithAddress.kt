package parts.drivers

import config.TmcAddressUartPins
import machine.MachineBuilder
import mcu.MessageBus
import utils.crc8

private val SYNC: UByte = 0xF5u
private val IFCNT_REG: UByte = 0x02u

class TmcUartNodeWithAddress(val config: TmcAddressUartPins, val bus: MessageBus, initialize: MachineBuilder) {
    private var writeCount: UByte? = null

    suspend fun readRegister(registerAddr: UByte): UInt {
        val readCommand = ubyteArrayOf(SYNC, config.address.toUByte(),registerAddr)
        for (retries in 0..5) {
            val response = sendReplyWithCrc(readCommand, 8) ?: continue
            if (response.isEmpty()) {
                continue
            }
            // The last byte is CRC
            if (response.size != 7) {
                continue
            }
            if (response[0] != SYNC || response[1] != 0xFFu.toUByte() || response[2] != registerAddr) {
                continue
            }
            // 4 bytes of data, high to low.
            return (response[3].toUInt() shl 24) + (response[4].toUInt() shl 16) + (response[5].toUInt() shl 8) + response[6].toUInt()
        }
        bus.mcu.shutdown("Cannot read form TMC UART, register $registerAddr")
        throw IllegalStateException("Cannot read form TMC UART, register $registerAddr")
    }

    suspend fun writeRegister(registerAddr: UByte, data: UInt) {
        val writeCommand = ubyteArrayOf(
            SYNC,
            config.address.toUByte(),
            registerAddr or 0x80u,
            (data shr 24).toUByte(),
            (data shr 16).toUByte(),
            (data shr 8).toUByte(),
            (data).toUByte())
        for (retries in 0..5) {
            var success = false
            bus.transaction {
                var writesBefore = writeCount
                if (writesBefore == null) {
                    writesBefore = readRegister(IFCNT_REG).toUByte()
                }
                bus.sendReply(writeCommand, 0)
                val writesAfter = readRegister(IFCNT_REG).toUByte()
                if (writesAfter == (writesBefore + 1u).toUByte()) {
                    writeCount = writesAfter
                    success = true
                }
            }
            if (success) return
        }
    }


    suspend fun sendReplyWithCrc(cmd: UByteArray, readBytes: Int): UByteArray? {
        val reply = bus.sendReply(cmd + ubyteArrayOf(cmd.crc8()), readBytes)
        if (reply == null || readBytes == 0) return reply
        val crc = reply.last()
        val payload = reply.copyOfRange(0, reply.size-1)

        val calculatedCrc = payload.crc8()
        if (calculatedCrc != crc) {
            return null
        }
        return payload
    }

}
