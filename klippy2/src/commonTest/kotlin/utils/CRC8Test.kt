package utils

import kotlin.test.Test
import kotlin.test.assertEquals

class CRC8Test {

    @Test
    fun testCrc8() {
        val input = "AB".encodeToByteArray()
        val result = input.crc8()
        assertEquals(result, 0x87u)
    }
}