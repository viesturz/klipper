package mcu.components

import Resistance
import Voltage
import io.github.oshai.kotlinlogging.KotlinLogging
import mcu.AnalogInPin
import mcu.ConfigurationException
import mcu.Mcu
import mcu.impl.McuComponent
import mcu.impl.McuConfigure
import mcu.impl.McuObjectResponse
import mcu.impl.McuRuntime
import mcu.impl.ObjectId
import mcu.impl.ResponseParser
import kotlin.math.min

class McuAnalogPin(override val mcu: Mcu, val config: config.AnalogInPin, configure: McuConfigure): McuComponent,
    AnalogInPin {
    private val logger = KotlinLogging.logger("AnalogPin ${config.pin}")
    private var listener: (suspend (p: AnalogInPin.Measurement) -> Unit)? = null
    private lateinit var runtime: McuRuntime
    private val id = configure.makeOid()
    // Offset from next clock to actual measurement time
    private val nextClockOffset: Double = config.reportInterval - config.sampleInterval * config.sampleCount.toDouble()
    private val adcInverse = 1.0f/(configure.identify.adcMax * config.sampleCount.toDouble())
    private var _value = AnalogInPin.Measurement(time = 0.0, value = config.minValue, config = config)
    override val value: AnalogInPin.Measurement
        get() = _value

    override fun configure(configure: McuConfigure) {
        if(nextClockOffset < 0.002) throw ConfigurationException("Analog pin ${config.pin} report interval too close. Measure time ${config.sampleCount.toDouble()*config.sampleInterval}, interval ${config.reportInterval}.")
        logger.info { "Configure ${config}" }
        configure.configCommand(
            "config_analog_in oid=%c pin=%u") {
            addId(id);addEnum("pin", config.pin)
        }

        configure.queryCommand("query_analog_in oid=%c clock=%u sample_ticks=%u sample_count=%c rest_ticks=%u min_value=%hu max_value=%hu range_check_count=%c")
        { clock ->
            addId(id); addU(clock); addU(configure.durationToTicks(config.sampleInterval)); addU(config.sampleCount);
            addU(configure.durationToTicks(config.reportInterval));
            addHU((config.minValue * configure.identify.adcMax * config.sampleCount.toDouble()).toUInt().toUShort());
            addHU((config.maxValue * configure.identify.adcMax * config.sampleCount.toDouble()).toUInt().toUShort());
            addC(config.rangeCheckCount)
        }
        configure.responseHandler(responseAnalogInStateParser, id, this::handleAnalogInState)
    }

    override fun start(runtime: McuRuntime) {
        this.runtime = runtime
    }
    override fun setListener(handler: suspend (m: AnalogInPin.Measurement) -> Unit) {
        this.listener = handler
    }

    fun handleAnalogInState(response: ResponseAnalogInState) {
        val time = runtime.clockToTime(response.nextClock) - nextClockOffset
        _value = AnalogInPin.Measurement(time, response.value.toDouble() * adcInverse, config)
        logger.trace { "adc: ${response.value/config.sampleCount} value: ${_value.value}, voltage=${_value.voltage}, resistance=${_value.resistance}" }
        listener?.let { l ->
            runtime.reactor.runNow { l(_value)} }
    }
}

data class ResponseAnalogInState(override val id: ObjectId, val nextClock :UInt, val value: UShort):
    McuObjectResponse
val responseAnalogInStateParser = ResponseParser("analog_in_state oid=%c next_clock=%u value=%hu") {
    ResponseAnalogInState(parseC(), parseU(), parseHU())
}
