package utils

import kotlin.test.Test
import kotlin.test.assertContentEquals

class Serial8N1Test {
    @Test
    fun testEncode1() {
        val encoded = encode8N1 { add(0xF0u) }
        val expected = arrayOf<UByte>(0xE0u, 0x03u).toUByteArray()
        assertContentEquals(expected, encoded)
    }
    @Test
    fun testEncode2() {
        val encoded = encode8N1 {
            add(0xf0u); add(0x3bu)
        }
        val expected = arrayOf<UByte>(0xE0u, 0xDBu, 0x09u).toUByteArray()
        assertContentEquals(expected, encoded)
    }

    @Test
    fun testDecode1() {
        val decoded = arrayOf<UByte>(0xE0u, 0x03u).toUByteArray().decode8N1()
        val expected = arrayOf<UByte>(0xF0u).toUByteArray()
        assertContentEquals(expected, decoded)
    }

    @Test
    fun testDecode2() {
        val decoded = arrayOf<UByte>(0xE0u, 0xDBu, 0x09u).toUByteArray().decode8N1()
        val expected = arrayOf<UByte>(0xF0u, 0x3Bu).toUByteArray()
        assertContentEquals(expected, decoded)
    }
}