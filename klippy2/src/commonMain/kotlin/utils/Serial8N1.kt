package utils

import platform.linux.bind_textdomain_codeset

class Serial8N1Encoder {
    val buffer = ArrayList<UByte>()
    var wip = 0
    var bits = 0

    fun add(data: UByte) {
        wip = wip or (data.toInt() shl (1+bits)) or 0x200
        bits += 10
        while (bits >= 8){
            buffer.add(wip.toUByte())
            wip = wip shr 8
            bits -= 8
        }
    }
    fun add(data: UByteArray) = data.forEach { add(it) }

    fun finish(): UByteArray {
        while (bits>0){
            buffer.add(wip.toUByte())
            wip = wip shr 8
            bits -= 8
        }
        return buffer.toUByteArray()
    }
}

fun encode8N1(block: Serial8N1Encoder.()->Unit): UByteArray {
    val encoder = Serial8N1Encoder()
    encoder.block()
    return encoder.finish()
}

fun ByteArray.decode8N1() = this.toUByteArray().decode8N1()
fun UByteArray.decode8N1() = buildList<UByte> {
    var wip = 0
    var bits = 0
    for (byte in this@decode8N1) {
        wip = wip or (byte.toInt() shl bits)
        bits += 8
        if (bits >= 10) {
            add(((wip shr 1) and 0xFF).toUByte())
            wip = wip shr 10
            bits -= 10
        }
    }
}.toUByteArray()