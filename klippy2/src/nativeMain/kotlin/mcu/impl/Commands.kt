package mcu.impl

import kotlinx.cinterop.ExperimentalForeignApi

// import platform.zlib.uncompress

typealias ObjectId = UByte
typealias McuClock32 = UInt
typealias McuClock64 = ULong

sealed interface McuResponse{ val id: ObjectId}
data class ResponseIdentify(val offset: UInt, val data: ByteArray, override val id: ObjectId = 0u) : McuResponse
data class ResponseTrsyncState(override val id: ObjectId, val canTrigger: UByte,val  triggerReason:UByte, val clock: McuClock32): McuResponse

class CommandParser(compressedIdentify: ByteArray? = null){
    val identify = Identify.parse(compressedIdentify)
    val responses = buildMap { for (e in identify.responses.entries) put(e.value, e.key) }
    val commands = identify.commands

    @OptIn(ExperimentalForeignApi::class)
    fun decode(data: ByteArray): McuResponse? {
        if (data.size <= chelper.MESSAGE_HEADER_SIZE + chelper.MESSAGE_TRAILER_SIZE) return null
        val buffer = ParseBuffer(data, chelper.MESSAGE_HEADER_SIZE)
        val name = responses.get(buffer.parseU()) ?: return null
        val result = when (name) {
            "identify_response offset=%u data=%.*s" -> ResponseIdentify(
                buffer.parseU(),
                buffer.parseBytes(),
            )

            else -> throw RuntimeException("Unknown message ${name}")
        }
        if (data.size != buffer.pos + chelper.MESSAGE_TRAILER_SIZE) {
            throw RuntimeException("Parsed incorrect number of bytes, parsed ${buffer.pos}, end at ${data.size - chelper.MESSAGE_TRAILER_SIZE}")
        }
        return result
    }
}

class CommandBuilder(private val parser: CommandParser) {
    fun identify(offset: UInt, count: UByte) = buildList {
        addU(parser.commands.getValue("identify offset=%u count=%c"))
        addU(offset);addC(count)
    }.toUByteArray()

    fun getConfig()= buildList {
        addU(parser.commands.getValue("get_config"))
    }

    fun allocateOids(count: UByte)= buildList {
        addU(parser.commands.getValue("allocate_oids count=%c"))
        addC(count)
    }

    fun configDigitalOut(
        oid: ObjectId,
        pin: UInt,
        value: UByte,
        defaultValue: UByte,
        maxDuration: UInt
    ) = buildList {
        addU(parser.commands.getValue("config_digital_out oid=%c pin=%u value=%c default_value=%c max_duration=%u"))
        addC(oid);addU(pin);addC(value);addC(defaultValue);addU(maxDuration)
    }

    fun finalizeConfig(crc: UInt) = buildList {
        addU(parser.commands.getValue("finalize_config crc=%u"))
        addU(crc)
    }

    // "config_trsync oid=%c"
    // "trsync_start oid=%c report_clock=%u report_ticks=%u expire_reason=%c"
    // "stepper_stop_on_trigger oid=%c trsync_oid=%c"

    fun trsyncTrigger(id: ObjectId, reason: UByte) = buildList {
        addU(parser.commands.getValue("trsync_trigger oid=%c reason=%c"))
        addC(id);addC(reason);
    }.toUByteArray()

    fun trsyncSetTimeout(id: ObjectId, clock: McuClock32) = buildList {
        addU(parser.commands.getValue("trsync_set_timeout oid=%c clock=%u"))
        addC(id);addU(clock)
    }

    fun setDigitalOut(pin: UInt, value: UByte) = buildList {
        addU(parser.commands.getValue("set_digital_out pin=%u value=%c"))
        addU(pin);addC(value)
    }.toUByteArray()
}

data class ParseBuffer(val array: ByteArray, var pos: Int = 0) {
    fun parseU() = parseVLQ(false).toUInt()
    fun parseI() = parseVLQ(false).toInt()
    fun parseC() = parseVLQ(false).toUByte()

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
    fun parseStr() = parseBytes().decodeToString()
}

fun MutableList<UByte>.addVLQ(v: Long) {
    if (v >= 0xc000000 || v < -0x4000000) add((((v shr 28) and 0x7f) or 0x80).toUByte())
    if (v >= 0x180000 || v < -0x80000) add((((v shr 21) and 0x7f) or 0x80).toUByte())
    if (v >= 0x3000 || v < -0x1000) add((((v shr 14) and 0x7f) or 0x80).toUByte())
    if (v >= 0x60 || v < -0x20) add((((v shr 7) and 0x7f) or 0x80).toUByte())
    add((v and 0x7f).toUByte())
}

fun MutableList<UByte>.addC(v: UByte) {
    addVLQ(v.toLong())
}

fun MutableList<UByte>.addU(v: UInt) {
    addVLQ(v.toLong())
}

fun MutableList<UByte>.addI(v: Int) {
    addVLQ(v.toLong())
}

fun MutableList<UByte>.addStr(v: String) {
    add(v.length.toUByte())
    addAll(v.encodeToByteArray().toList().map { it.toUByte() })
}
