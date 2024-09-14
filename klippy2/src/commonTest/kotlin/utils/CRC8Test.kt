package utils

import kotlin.test.Test
import kotlin.test.assertEquals

class CRC8Test {

    @Test
    fun testCrc8A() {
        val input = "A".encodeToByteArray()
        val result = input.crc8()
        assertEquals( 0x87u, result)
    }

    @Test
    fun testCrc8AB() {
        val input = "AB".encodeToByteArray()
        val result = input.crc8()
        assertEquals( 0x55u, result)
    }

    @Test
    fun testCrc8AB_() {
        val input = "AB_".encodeToByteArray()
        val result = input.crc8()
        assertEquals( 0x44u, result)
    }

    @Test
    fun testInvertBits() {
        val input:UByte = 0b1010_0000u
        val result = invertBits(input)
        assertEquals( 0b0000_0101u, result)
    }
}