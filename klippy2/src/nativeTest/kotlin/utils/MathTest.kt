package utils

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals


class MathTest {

    @Test
    fun lengthAlongDirection_alongXAxis() {
        val v = listOf(3.0, 4.0)
        val dir = listOf(1.0, 0.0) // unit x
        val result = v.lengthAlongDirection( dir)
        assertEquals(3.0, result, 1e-9)
    }

    @Test
    fun lengthAlongDirection_alongYAxis() {
        val v = listOf(3.0, 4.0)
        val dir = listOf(0.0, 1.0) // unit y
        val result = v.lengthAlongDirection(dir)
        assertEquals(4.0, result, 1e-9)
    }

    @Test
    fun lengthAlongDirection_testDiagonalUnitDirection2D() {
        val v = listOf(3.0, 3.0)
        val dir = listOf(1.0, 1.0).direction() // unit (1,1)
        val expected = 3.0 * sqrt(2.0) // projection magnitude
        val result = v.lengthAlongDirection(dir)
        assertEquals(expected, result, 1e-9)
    }
}