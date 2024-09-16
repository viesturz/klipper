package parts.drivers

import MachineTime
import machine.MachineBuilder
import machine.impl.PartLifecycle
import kotlin.math.min
import kotlin.math.sqrt

class TmcCurrent(val senseResistor: Double, val runCurrent: Double, val idleCurrent: Double) {
    suspend fun setCurrent(fields: TmcFields, runCurrent: Double, holdCurrent: Double) {
        var vsense = true
        var iRun = calcCurrentBits(runCurrent, vsense)
        // Higher irun with vsense=1 is preferred and leads to better microstep accuracy.
        if (iRun > 31) {
            vsense = false
            iRun = calcCurrentBits(runCurrent, vsense)
        }
        val iHold = calcCurrentBits(min(holdCurrent, runCurrent), vsense)
        fields.set(TmcField.vsense, vsense)
        fields.set(TmcField.irun, iRun.toInt().coerceIn(0,31))
        fields.set(TmcField.ihold, iHold.toInt().coerceIn(0,31))
    }

    fun calcCurrentBits(current: Double, vsense: Boolean): Double {
        val rsense = senseResistor + 0.020
        val vref = if (vsense) 0.18 else 0.32
        return (32.0 * rsense * current * sqrt(2.0) / vref + .5) - 1.0
    }
}

val MICROSTEP_MAP = mapOf(
    256 to 0,
    128 to 1,
    64 to 2,
    32 to 3,
    16 to 4,
    8 to 5,
    4 to 6,
    2 to 7,
    1 to 8)

class TmcMicrosteps(var microsteps: Int, var interpolate: Boolean) {
    suspend fun set(fields: TmcFields) {
        val mres = MICROSTEP_MAP[microsteps] ?: throw IllegalArgumentException("Invalid microsteps: $microsteps")
        fields.set(TmcField.mres, mres)
        fields.set(TmcField.intpol, interpolate)
    }
}
//
//class TmcStealthchop(val maxSpeed: Double, val stepsPerMm: Int) {
//    suspend fun set(fields: TmcFields) {
//        if (maxSpeed > 0.0) {
//
//        }
//    }
//}
//
//fun velocityToTsteps(velocity: Double, stepsPerMm: Double): Int {
//    if (velocity < 0) return  0xfffff
//
//}

class TmcEnableTracking(val fields: TmcFields, enablePin: config.DigitalOutPin?, setup: MachineBuilder):
    PartLifecycle {
    override val name = "TmcEnableTracking"
    val pin = if (enablePin == null) null else setup.setupMcu(enablePin.mcu).addDigitalPin(enablePin.copy(watchdogDuration = 0.0))
    var toffState = 0

    suspend fun enable(time: MachineTime) {
        if (pin != null) {
            pin.set(time, true)
        } else if (fields.hasField(TmcField.toff) && toffState != 0) {
            fields.set(TmcField.toff, toffState)
            fields.flush(time)
        }
    }

    suspend fun disable(time: MachineTime) {
        if (pin != null) {
            pin.set(time, false)
        } else {
            if (toffState == 0) {
                toffState = fields.readField(TmcField.toff)
            }
            fields.set(TmcField.toff, 0)
            fields.flush(time)
        }
    }
}
