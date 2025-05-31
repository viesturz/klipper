package mcu

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import MachineTime
import chelper.MESSAGE_HEADER_SIZE
import machine.ConfigurationException
import kotlin.experimental.and

typealias ObjectId = UByte
typealias MoveQueueId = UShort
typealias McuClock32 = UInt

private val logger = KotlinLogging.logger("Commands")

interface McuResponse
interface McuObjectResponse: McuResponse {
    val id: ObjectId
}
data class ResponseParser<Type: McuResponse>(val signature: String, val block : ParserContext.()->Type)

@OptIn(ExperimentalForeignApi::class, ExperimentalStdlibApi::class)
class Commands(val identify: FirmwareConfig){
    private val responses = buildMap { for (e in identify.responses.entries) put(e.value, e.key) }
    private val commands = buildMap { for (e in identify.commands.entries) put(e.value, e.key) }

    fun parse(data: ByteArray, sentTime: MachineTime, receiveTime: MachineTime): Pair<String, McuResponse>? {
        if (data.size <= chelper.MESSAGE_HEADER_SIZE + chelper.MESSAGE_TRAILER_SIZE) return null
        val buffer = ParserContext(data, MESSAGE_HEADER_SIZE, sentTime, receiveTime)
        val name = responses.get(buffer.parseI()) ?: return null
        val parser = RESPONSES_MAP[name]
        if (parser == null) {
            logger.info { "Message with no handler $name" }
            return null
        }
        val result = buffer.run { parser() }
        if (data.size != buffer.pos + chelper.MESSAGE_TRAILER_SIZE) {
            throw RuntimeException("Parsed incorrect number of bytes, parsed ${buffer.pos}, end at ${data.size - chelper.MESSAGE_TRAILER_SIZE}")
        }
        return Pair(name, result)
    }

    inline fun build(signature: String, block: CommandBuilder.()->Unit): UByteArray {
        val buffer = BytesBuffer()
        build(buffer, signature, block)
        return buffer.bytes.toUByteArray()
    }

    inline fun build(buffer: CommandBuffer, signature: String, block: CommandBuilder.()->Unit) {
        val builder = CommandBuilder(this, buffer)
        builder.addI(identify.commands.getValue(signature))
        builder.block()
    }

    fun registerParser(parser: ResponseParser<*>) {
        RESPONSES_MAP[parser.signature] = parser.block
    }

    fun hasCommand(signature: String) = identify.commands.containsKey(signature)

    fun dumpCommand(data: ByteArray) = dumpMessage(data, commands)
    fun dumpResponse(data: ByteArray) = dumpMessage(data, responses)
    fun dumpMessage(data: ByteArray, commands: Map<Int, String>): String {
        val seq = data[chelper.MESSAGE_POS_SEQ]
        val buffer = ParserContext(data, MESSAGE_HEADER_SIZE, 0.0, 0.0)
        return buildString {
            append("seq: 0x${(seq and 0x0f).toHexString()} ")
            while (buffer.pos < data.size - chelper.MESSAGE_TRAILER_SIZE) {
                val id = buffer.parseI()
                val signature = commands[id] ?: return "${data.toHexString()} Unknown message ID $id"
                val message = formatMessage(signature, buffer)
                append("$message; ")
            }
        }
    }

    fun formatMessage(sig: String, parser: ParserContext) = sig.replace("=%(\\S+)\\b".toRegex()) { match: MatchResult ->
        val value = try {
            when (match.groups[1]!!.value) {
                "s" -> parser.parseStr()
                ".*s", "*s" -> parser.parseBytes().toHexString()
                "c" -> parser.parseC()
                "b" -> parser.parseBoolean()
                "i", "d" -> parser.parseI()
                "u" -> parser.parseU()
                "h" -> parser.parseH()
                "hu" -> parser.parseHU()
                else -> "???"
            }.toString()
        } catch (e: Exception) {
            "???"
        }
        "=$value "
    }
}

interface CommandBuffer{
    fun addVLQ(v: Long)
    fun addBytes(v:UByteArray)
}

class BytesBuffer: CommandBuffer {
    val bytes = ArrayList<UByte>()
    override fun addVLQ(v: Long) {
        if (v >= 0xc000000 || v < -0x4000000) bytes.add((((v shr 28) and 0x7f) or 0x80).toUByte())
        if (v >= 0x180000 || v < -0x80000) bytes.add((((v shr 21) and 0x7f) or 0x80).toUByte())
        if (v >= 0x3000 || v < -0x1000) bytes.add((((v shr 14) and 0x7f) or 0x80).toUByte())
        if (v >= 0x60 || v < -0x20) bytes.add((((v shr 7) and 0x7f) or 0x80).toUByte())
        bytes.add((v and 0x7f).toUByte())
    }
    override fun addBytes(v: UByteArray) {
        bytes.add(v.size.toUByte())
        bytes.addAll(v)
    }

}

class CommandBuilder(private val parser: Commands, private val buffer: CommandBuffer) {

    fun addC(v: Boolean) = buffer.addVLQ(if (v) 1 else 0)
    fun addC(v: UByte) = buffer.addVLQ(v.toLong())
    fun addC(v: Byte) = buffer.addVLQ(v.toLong())
    fun addId(v: ObjectId) = buffer.addVLQ(v.toLong())
    fun addU(v: UInt) = buffer.addVLQ(v.toLong())
    fun addHU(v: UShort) = buffer.addVLQ(v.toLong())
    fun addH(v: Short) = buffer.addVLQ(v.toLong())
    fun addI(v: Int) = buffer.addVLQ(v.toLong())
    fun addBoolean(v: Boolean) = buffer.addVLQ(if (v) 1 else 0)

    fun addStr(v: String) = buffer.addBytes(v.encodeToByteArray().toUByteArray())
    fun addBytes(v: ByteArray) = buffer.addBytes(v.toUByteArray())
    fun addBytes(v: UByteArray) = buffer.addBytes(v)
    fun addEnum(name: String, value: String) {
        val values = parser.identify.enumerationValueToId(name)
        val id = values[value]
        if (id == null) {
            logger.error { "Enum $name value $value not found, available values $values" }
            throw ConfigurationException("$name $value not present for MCU type ${parser.identify.mcuName}")
        }
        addI(id)
    }
    fun addPin(pinName: String) = addEnum("pin", pinName)
}

data class ParserContext(val array: ByteArray, var pos: Int = 0, val sentTime: MachineTime, val receiveTime: MachineTime) {
    fun parseU() = parseVLQ(false).toUInt()
    fun parseI() = parseVLQ(false).toInt()
    fun parseL() = parseVLQ(false)
    fun parseC() = parseVLQ(false).toUByte()
    fun parseHU() = parseVLQ(false).toUShort()
    fun parseH() = parseVLQ(false).toShort()
    fun parseStr() = parseBytes().decodeToString()
    fun parseId() = parseC()
    fun parseClock(): McuClock32 = parseU()
    fun parseBoolean() = parseVLQ(false) > 0

    fun parseVLQ(signed: Boolean): Long {
        var c = array[pos].toLong()
        pos += 1
        var v = (c and 0x7f)
        if ((c and 0x60) == 0x60L) v = v or -0x20
        while (c and 0x80 != 0L) {
            c = array[pos].toLong()
            v = (v shl 7) or (c and 0x7f)
            pos += 1
        }
        if (!signed) v = v and 0xffffffff
        return v
    }
    fun parseBytes(): ByteArray {
        val len = array[pos].toInt()
        pos++
        val result = array.sliceArray(pos..(pos + len-1))
        pos += len
        return result
    }
}

/** A global map of parsers, populated using commands.registerParser. */
private val RESPONSES_MAP = HashMap<String, ParserContext.()-> McuResponse>()
