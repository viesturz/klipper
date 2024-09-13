package parts

import Resistance
import config.AnalogInPin
import config.ValueSensor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import machine.MachineBuilder
import machine.MachineRuntime
import machine.impl.PartLifecycle
import utils.format

/** A potentiometer sensor. Returns a value in range 0 = minResistance, 1 = maxResistance. */
fun MachineBuilder.PotSensor(
    name: String,
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
    setup: MachineBuilder): PartLifecycle, ValueSensor<Resistance> {
    val logger = KotlinLogging.logger("PotSensor $name")

    val adc = setup.setupMcu(pinConfig.mcu).addAnalogPin(pinConfig)
    override val minValue: Double = 0.0
    override val maxValue: Double = 1.0
    val _value = MutableStateFlow(ValueSensor.Measurement(0.0, minValue))

    override val measurement: StateFlow<ValueSensor.Measurement<Double>>
        get() = _value

    override suspend fun onStart(runtime: MachineRuntime) {
        adc.setListener { m ->
            val value = ((m.resistance - minResistance) / (maxResistance - minResistance)).coerceIn(minValue, maxValue)
            logger.info { "raw = ${m.value.format(0, 3)} resistance = ${m.resistance.format(0)} value = ${value.format(0,3)}" }
            _value.value = ValueSensor.Measurement(m.time, value)
        }
    }

    override fun status() = mapOf("value" to measurement.value.value)
}
