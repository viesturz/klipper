package parts.kinematics

import utils.squared
import kotlin.math.absoluteValue
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JunctionSpeedTest {
    val speeds0 = LinearSpeeds(speed = 10.0, accel = 500.0, squareCornerVelocity = 2.0)
    val speeds1 = LinearSpeeds(speed = 20.0, accel = 500.0, squareCornerVelocity = 4.0)

    @Test
    fun zeroSegments() {
        val speedSq = calculateJunctionSpeedSq(
            listOf(0.0),
            listOf(10.0),
            prevDistance = 0.0,
            nextDistance = 10.0,
            prevSpeeds = speeds0,
            nextSpeeds = speeds1)

        assertEquals(2.0.squared(), speedSq)
    }

    @Test
    fun parallelSegmentD1() {
        val speedSq = calculateJunctionSpeedSq(
            listOf(10.0),
            listOf(10.0),
            prevDistance = 10.0,
            nextDistance = 10.0,
            prevSpeeds = speeds0,
            nextSpeeds = speeds1)

        assertEquals(10.0.squared(), speedSq)
    }

    @Test
    fun angle0deg() {
        val speedSq = calculateJunctionSpeedSq(
            listOf(10.0, 2.0),
            listOf(10.0, 2.0),
            prevDistance = 10.0,
            nextDistance = 10.0,
            prevSpeeds = speeds0,
            nextSpeeds = speeds1)

        assertEquals(10.0.squared(), speedSq)
    }

    @Test
    fun angle30deg() {
        val speedSq = calculateJunctionSpeedSq(
            listOf(10.0, 0.0),
            listOf(5*sqrt(3.0), 5.0),
            prevDistance = 10.0,
            nextDistance = 10.0,
            prevSpeeds = speeds0,
            nextSpeeds = speeds0)

        assertEquals(46.968073930, speedSq, precision = 1e-5)
    }

    @Test
    fun angle60deg() {
        val speedSq = calculateJunctionSpeedSq(
            listOf(10.0, 0.0),
            listOf(5.0, 5*sqrt(3.0)),
            prevDistance = 10.0,
            nextDistance = 10.0,
            prevSpeeds = speeds0,
            nextSpeeds = speeds0)

        assertEquals(10.7100742301915, speedSq, precision = 1e-5)
    }

    @Test
    fun angle90deg() {
        val speedSq = calculateJunctionSpeedSq(
            listOf(10.0, 0.0),
            listOf(0.0, 10.0),
            prevDistance = 10.0,
            nextDistance = 10.0,
            prevSpeeds = speeds0,
            nextSpeeds = speeds0)

        assertEquals(2.0.squared(), speedSq, precision = 1e-5)
    }

    @Test
    fun angle120deg() {
        val speedSq = calculateJunctionSpeedSq(
            listOf(10.0, 0.0),
            listOf(-5.0, 5*sqrt(3.0)),
            prevDistance = 10.0,
            nextDistance = 10.0,
            prevSpeeds = speeds0,
            nextSpeeds = speeds0)

        assertEquals(1.656854249, speedSq, precision = 1e-5)
    }

    @Test
    fun angle150deg() {
        val speedSq = calculateJunctionSpeedSq(
            listOf(10.0, 0.0),
            listOf(-5*sqrt(3.0), 5.0),
            prevDistance = 10.0,
            nextDistance = 10.0,
            prevSpeeds = speeds0,
            nextSpeeds = speeds0)

        assertEquals(0.5785704987346679, speedSq, precision = 1e-5)
    }

    @Test
    fun angle180deg() {
        val speedSq = calculateJunctionSpeedSq(
            listOf(10.0, 0.0),
            listOf(-10.0, 0.0),
            prevDistance = 10.0,
            nextDistance = 10.0,
            prevSpeeds = speeds0,
            nextSpeeds = speeds0)

        assertEquals(0.0, speedSq)
    }

}

fun assertEquals(expected: Double, actual: Double, precision: Double) {
    val delta = (expected - actual).absoluteValue
    assertTrue(delta <= precision, "Expected $expected, got $actual, delta $delta > precision $precision")
}