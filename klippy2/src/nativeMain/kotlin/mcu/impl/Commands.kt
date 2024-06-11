package mcu.impl

import mcu.McuClock

// import platform.zlib.uncompress

typealias ObjectId = UByte
typealias McuClock32 = UInt

sealed interface McuResponse{ val id: ObjectId}
data class ResponseIdentify(val offset: UInt, val data: ByteArray, override val id: ObjectId = 0u) : McuResponse
data class ResponseTrsyncState(override val id: ObjectId, val canTrigger: UByte,val  triggerReason:UByte, val clock: McuClock32): McuResponse

class CommandParser(compressedIdentify: ByteArray? = null){
    var messageToId = mapOf(
        "identify_response offset=%u data=%.*s" to 0u,
        "identify offset=%u count=%c" to 1u,
    )
    val paramToValues: Map<String, Map<String, UInt>> = mapOf()

    init {
        if (compressedIdentify != null) {
            //TODO
        }
    }

    val idToMessage = buildMap { for (e in messageToId.entries) put(e.value, e.key) }

    fun decode(data: ParseBuffer): McuResponse {
        val name = idToMessage.getValue(data.parseU())
        return when (name) {
            "identify_response offset=%u data=%.*s" -> ResponseIdentify(
                data.parseU(),
                data.parseBytes()
            )

            else -> throw RuntimeException("Unknown message ${name}")
        }
    }
}

class CommandBuilder(private val parser: CommandParser) {
    fun identify(offset: UInt, count: UByte) = buildList {
        addU(parser.messageToId.getValue("identify offset=%u count=%c"))
        addU(offset);addC(count)
    }.toByteArray()

    fun getConfig()= buildList {
        addU(parser.messageToId.getValue("get_config"))
    }

    fun allocateOids(count: UByte)= buildList {
        addU(parser.messageToId.getValue("allocate_oids count=%c"))
        addC(count)
    }

    fun configDigitalOut(
        oid: ObjectId,
        pin: UInt,
        value: UByte,
        defaultValue: UByte,
        maxDuration: UInt
    ) = buildList {
        addU(parser.messageToId.getValue("config_digital_out oid=%c pin=%u value=%c default_value=%c max_duration=%u"))
        addC(oid);addU(pin);addC(value);addC(defaultValue);addU(maxDuration)
    }

    fun finalizeConfig(crc: UInt) = buildList {
        addU(parser.messageToId.getValue("finalize_config crc=%u"))
        addU(crc)
    }

    // "config_trsync oid=%c"
    // "trsync_start oid=%c report_clock=%u report_ticks=%u expire_reason=%c"
    // "stepper_stop_on_trigger oid=%c trsync_oid=%c"

    fun trsyncTrigger(id: ObjectId, reason: UByte) = buildList {
        addU(parser.messageToId.getValue("trsync_trigger oid=%c reason=%c"))
        addC(id);addC(reason);
    }.toByteArray()

    fun trsyncSetTimeout(id: ObjectId, clock: McuClock32) = buildList {
        addU(parser.messageToId.getValue("trsync_set_timeout oid=%c clock=%u"))
        addC(id);addU(clock)
    }

    fun setDigitalOut(pin: UInt, value: UByte) = buildList {
        addU(parser.messageToId.getValue("set_digital_out pin=%u value=%c"))
        addU(pin);addC(value)
    }.toByteArray()
}

data class ParseBuffer(val array: ByteArray, var pos: Int) {
    inline fun parseU() = parseVLQ(false).toUInt()
    inline fun parseI() = parseVLQ(false).toInt()
    inline fun parseC() = parseVLQ(false).toUByte()

    fun parseVLQ(signed: Boolean): Long {
        val c = array[pos].toLong()
        pos += 1
        var v = (c and 0x7f)
        if ((c and 0x60) == 0x60L) v = v or -0x20
        while (c and 0x80 != 0L) {
            v = (v shl 7) or (array[pos].toLong() and 0x7f)
            pos += 1
        }
        if (!signed) v = v and 0xffffffff
        return v
    }

    fun parseBytes(): ByteArray {
        val len = parseI()
        val result = array.sliceArray(pos..(pos + len))
        pos += len
        return result
    }
    fun parseStr() = parseBytes().decodeToString()
}

fun MutableList<Byte>.addVLQ(v: Long) {
    if (v >= 0xc000000 || v < -0x4000000) add((((v shr 28) and 0x7f) or 0x80).toByte())
    if (v >= 0x180000 || v < -0x80000) add((((v shr 21) and 0x7f) or 0x80).toByte())
    if (v >= 0x3000 || v < -0x1000) add((((v shr 14) and 0x7f) or 0x80).toByte())
    if (v >= 0x60 || v < -0x20) add((((v shr 7) and 0x7f) or 0x80).toByte())
    add((v and 0x7f).toByte())
}

inline fun MutableList<Byte>.addC(v: UByte) {
    addVLQ(v.toLong())
}

inline fun MutableList<Byte>.addU(v: UInt) {
    addVLQ(v.toLong())
}

inline fun MutableList<Byte>.addI(v: Int) {
    addVLQ(v.toLong())
}

inline fun MutableList<Byte>.addStr(v: String) {
    addI(v.length)
    addAll(v.encodeToByteArray().toList())
}
