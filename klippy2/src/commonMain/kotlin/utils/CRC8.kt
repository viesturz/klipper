package utils

// Generate a CRC8 - ATM value for a bytearray with inverted input bit order.
fun ByteArray.crc8() = toUByteArray().crc8()
fun UByteArray.crc8(): UByte = this.fold(initial) { crc, byte -> crc8Lookup[(crc xor invertBitsLookup[byte.toInt()]).toInt()] }

private val initial: UByte = 0x0u
private val polynomial: UByte = 0x07u
private val crc8Lookup = (0..255).map{  crc8(it.toUByte(), polynomial) }

private val invertBitsLookup = (0..255).map{  invertBits(it.toUByte()) }

fun invertBits(input: UByte): UByte {
    var result = 0
    var mask = 0b1000_0000
    for (i in 0..7) {
        result = result shr 1
        if (input.toInt() and mask != 0) {
            result += 0b1000_0000
        }
        mask = mask shr 1
    }
    return result.toUByte()
}

private fun crc8(input: UByte, polynomial: UByte) = (0..7).fold(input) { result, _ ->
        val isMostSignificantBitOne = result.toInt() and 0x80 != 0
        val shiftedResult = (result.toInt() shl 1).toUByte()
        when (isMostSignificantBitOne) {
            true -> shiftedResult xor polynomial
            false -> shiftedResult
        }
    }
