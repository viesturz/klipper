package config

import kotlin.math.ln
import Resistance
import Temperature
import celsius
import kelvins
import ohms
import platform.posix.pow
import platform.posix.sqrt
import kotlin.math.exp

// NTC 100K MGB18-104F39050L32
// Definition from description of Marlin "thermistor 75"
val NTC100K = ParametricThermistor.make(25.celsius to 100_000.ohms, beta = 4100.0)


interface TemperatureCalibration {
    fun resistanceToTemp(r: Resistance): Temperature
    fun tempToResistance(t: Temperature): Resistance
}

data class ResistanceCalibration(val r: Resistance, val t: Temperature)
infix fun Temperature.to(r: Resistance) = ResistanceCalibration(r = r, t = this)

data class ParametricThermistor(val c1: Double, val c2: Double, val c3: Double) :
    TemperatureCalibration {
    override fun resistanceToTemp(r: Resistance): Temperature {
        val ln_r = ln(r)
        val inv_t = c1 + c2 * ln_r + c3 * pow(ln_r, 3.0)
        return (1.0 / inv_t).kelvins
    }

    override fun tempToResistance(t: Temperature): Resistance {
        // Calculate adc reading from a temperature
        if (t.kelvins <= 0) return 1.0
        val inv_t = 1.0 / t.kelvins
        val ln_r = if (c3 != 0.0) {
            //  Solve for ln_r using Cardano's formula
            val y = (c1 - inv_t) / (2.0 * c3)
            val x = sqrt(pow((c2 / (3.0 * c3)), 3.0) + y * y)
            pow(x - y, 1.0 / 3.0) - pow(x + y, 1.0 / 3.0)
        } else {
            (inv_t - c1) / c2
        }
        return exp(ln_r)
    }

    companion object {
        fun make(point1: ResistanceCalibration, beta: Double): ParametricThermistor {
            // Calculate equivalent Steinhart-Hart coefficients from beta
            val inv_t1 = 1.0 / point1.t.kelvins
            val c2 = 1.0 / beta
            val result = ParametricThermistor(
                c1 = inv_t1 - c2 * ln(point1.r),
                c2 = c2,
                c3 = 0.0,
            )
            return result
        }

        fun make(
            point1: ResistanceCalibration,
            point2: ResistanceCalibration,
            point3: ResistanceCalibration
        ): ParametricThermistor {
            // Calculate Steinhart-Hart coefficients from temp measurements.
            // Arrange samples as 3 linear equations and solve for c1, c2, and c3.
            val inv_t1 = 1.0 / point1.t.kelvins
            val inv_t2 = 1.0 / point2.t.kelvins
            val inv_t3 = 1.0 / point3.t.kelvins
            val ln_r1 = ln(point1.r);
            val ln3_r1 = pow(ln_r1, 3.0)
            val ln_r2 = ln(point2.r);
            val ln3_r2 = pow(ln_r2, 3.0)
            val ln_r3 = ln(point3.r);
            val ln3_r3 = pow(ln_r3, 3.0)
            val inv_t12 = inv_t1 - inv_t2;
            val inv_t13 = inv_t1 - inv_t3
            val ln_r12 = ln_r1 - ln_r2;
            val ln_r13 = ln_r1 - ln_r3
            val ln3_r12 = ln3_r1 - ln3_r2;
            val ln3_r13 = ln3_r1 - ln3_r3
            val c3 = ((inv_t12 - inv_t13 * ln_r12 / ln_r13)
                    / (ln3_r12 - ln3_r13 * ln_r12 / ln_r13))
            if (c3 <= 0.0) {
                val beta = ln_r13 / inv_t13
                return make(point1, beta)
            }
            val c2 = (inv_t12 - c3 * ln3_r12) / ln_r12
            val c1 = inv_t1 - c2 * ln_r1 - c3 * ln3_r1
            return ParametricThermistor(c1, c2, c3)
        }
    }
}

fun makeResistanceCalibration(func: (temp: Temperature) -> Resistance) = buildList {
    for (temp in (0..500 step 10)) {
        add(temp.celsius to func(temp.celsius))
    }
}.toTypedArray()

object TemperaturePT1000 : TemperatureCalibration {
    override fun resistanceToTemp(r: Resistance): Temperature {
        TODO("Not yet implemented")
    }

    override fun tempToResistance(t: Temperature): Resistance {
        TODO("Not yet implemented")
    }
}

private fun calcPT(base: Float, t: Temperature): Resistance {
    // Calc PT100/PT1000 resistances using Callendar-Van Dusen formula
    val A = 3.9083e-3;
    val B = -5.775e-7
    val k = t.kelvins
    return base * (1.0 + A * k + B * k * k)
}