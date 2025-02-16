package parts

import Resistance
import config.AnalogInPin
import config.ValueSensor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import MachineBuilder
import MachineRuntime
import PartLifecycle
import utils.format

/** A potentiometer sensor. Returns a value in range 0 = minResistance, 1 = maxResistance. */
fun MachineBuilder.PotSensor(
    name: String = defaultName("PotSensor"),
    pin: AnalogInPin,
    minResistance: Resistance,
    maxResistance: Resistance,
): ValueSensor<Double> {
    require(minResistance < maxResistance) { "Min Resistance $minResistance needs to be less than Max $maxResistance" }
    val impl = PotSensorImpl(name,pin, minResistance, maxResistance, this)
    addPart(impl)
    return impl
}

private class PotSensorImpl(
    override val name: String,
    pinConfig: AnalogInPin,
    val minResistance: Resistance,
    val maxResistance: Resistance,
    setup: MachineBuilder
): PartLifecycle, ValueSensor<Resistance> {
    val logger = KotlinLogging.logger("PotSensor $name")

    val adc = setup.setupMcu(pinConfig.mcu).addAnalogPin(pinConfig)
    override val minValue: Double = 0.0
    override val maxValue: Double = 1.0
    var _value = ValueSensor.Measurement(0.0, minValue)
    val _flow = MutableSharedFlow<ValueSensor.Measurement<Double>>()
    override val value: ValueSensor.Measurement<Double>
        get() = _value
    override val flow: SharedFlow<ValueSensor.Measurement<Double>>
        get() = _flow

    override suspend fun onStart(runtime: MachineRuntime) {
        adc.setListener { m ->
            val value = ((m.resistance - minResistance) / (maxResistance - minResistance)).coerceIn(minValue, maxValue)
            logger.debug { "raw = ${m.value.format(0, 3)} resistance = ${m.resistance.format(0)} value = ${value.format(0,3)}" }
            _value = ValueSensor.Measurement(m.time, value)
            _flow.emit(_value)
        }
    }

    override fun status() = mapOf("value" to value)
}
