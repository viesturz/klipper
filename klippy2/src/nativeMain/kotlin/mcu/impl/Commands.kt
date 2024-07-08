package mcu.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import machine.impl.MachineTime
import mcu.ConfigurationException

typealias ObjectId = UByte
typealias MoveQueueId = UShort
typealias McuClock32 = UInt

private val logger = KotlinLogging.logger("Commands")

interface McuResponse
interface McuObjectResponse: McuResponse{
    val id: ObjectId
}
data class ResponseParser<Type: McuResponse>(val signature: String, val block : ParserContext.()->Type)

class Commands(val identify: FirmwareConfig){
    private val responses = buildMap { for (e in identify.responses.entries) put(e.value, e.key) }

    @OptIn(ExperimentalForeignApi::class)
    fun parse(data: ByteArray, sentTime: MachineTime, receiveTime: MachineTime): Pair<String, McuResponse>? {
        if (data.size <= chelper.MESSAGE_HEADER_SIZE + chelper.MESSAGE_TRAILER_SIZE) return null
        val buffer = ParserContext(data, chelper.MESSAGE_HEADER_SIZE, sentTime, receiveTime)
        val name = responses.get(buffer.parseU()) ?: return null
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
        val builder = CommandBuilder(this)
        builder.addU(identify.commands.getValue(signature))
        builder.block()
        return builder.bytes.toUByteArray()
    }

    fun registerParser(parser: ResponseParser<*>) {
        RESPONSES_MAP[parser.signature] = parser.block
    }

    fun hasCommand(signature: String) = identify.commands.containsKey(signature)
}

class CommandBuilder(private val parser: Commands) {
    val bytes = ArrayList<UByte>()
    fun addC(v: Boolean) = addVLQ(if (v) 1 else 0)
    fun addC(v: UByte) = addVLQ(v.toLong())
    fun addId(v: ObjectId) = addVLQ(v.toLong())
    fun addU(v: UInt) = addVLQ(v.toLong())
    fun addHU(v: UShort) = addVLQ(v.toLong())
    fun addH(v: Short) = addVLQ(v.toLong())
    fun addI(v: Int) = addVLQ(v.toLong())

    fun addStr(v: String) {
        bytes.add(v.length.toUByte())
        bytes.addAll(v.encodeToByteArray().toList().map { it.toUByte() })
    }
    fun addEnum(name: String, value: String) {
        val values = parser.identify.enumerationValueToId(name)
        val id = values[value]
        if (id == null) {
            logger.error { "Enum $name value $value not found, available values $values" }
            throw ConfigurationException("$name $value not present for MCU type ${parser.identify.mcuName}")
        }
        addI(id)
    }
    private fun addVLQ(v: Long) {
        if (v >= 0xc000000 || v < -0x4000000) bytes.add((((v shr 28) and 0x7f) or 0x80).toUByte())
        if (v >= 0x180000 || v < -0x80000) bytes.add((((v shr 21) and 0x7f) or 0x80).toUByte())
        if (v >= 0x3000 || v < -0x1000) bytes.add((((v shr 14) and 0x7f) or 0x80).toUByte())
        if (v >= 0x60 || v < -0x20) bytes.add((((v shr 7) and 0x7f) or 0x80).toUByte())
        bytes.add((v and 0x7f).toUByte())
    }
}

data class ParserContext(val array: ByteArray, var pos: Int = 0, val sentTime: MachineTime, val receiveTime: MachineTime) {
    fun parseU() = parseVLQ(false).toUInt()
    fun parseI() = parseVLQ(false).toInt()
    fun parseC() = parseVLQ(false).toUByte()
    fun parseHU() = parseVLQ(false).toUShort()
    fun parseH() = parseVLQ(false).toShort()
    fun parseStr() = parseBytes().decodeToString()
    fun parseB() = parseVLQ(false) > 0

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
private val RESPONSES_MAP = HashMap<String, ParserContext.()->McuResponse>()
