package utils

// Generate a CRC8 - ATM value for a bytearray
fun ByteArray.crc8(): UByte = this.fold(initial) { crc, byte -> crc8Lookup[(crc xor byte.toUByte()).toInt()] }

private val initial: UByte = 0x0u
private val polynomial: UByte = 0x07u
private val crc8Lookup = (0..255).map{  crc8(it.toUByte(), polynomial) }

private fun crc8(input: UByte, polynomial: UByte) = (0..7).fold(input) { result, _ ->
        val isMostSignificantBitOne = result.toInt() and 0x80 != 0
        val shiftedResult = (result.toInt() shl 1).toUByte()

        when (isMostSignificantBitOne) {
            true -> shiftedResult xor polynomial
            false -> shiftedResult
        }
    }
