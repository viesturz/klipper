package mcu

import io.github.oshai.kotlinlogging.KotlinLogging
import utils.RegisterMcuMessage

/** Component handling the basic MCU features. */
class McuBasics(val mcu: McuImpl, val configure: McuConfigure): McuComponent {
    private val logger = KotlinLogging.logger(mcu.config.name)

    init {
        configure.responseHandler<ResponseStats>( 0u, this::onStats)
        configure.responseHandler<ResponseShutdown>( 0u, this::onShutdown)
    }

    fun onStats(stats: ResponseStats) {
        logger.trace { "Stats: $stats" }
    }
    fun onShutdown(resp: ResponseShutdown) {
        val reason = configure.firmware.enumerationIdToValue("static_string_id")[resp.staticStringId.toInt()]  ?: "unknown Firmware error ${resp.staticStringId}"
        logger.error {  "Shutdown Firmware error: $reason" }
        mcu.shutdown("MCU ${mcu.config.name}: $reason")
    }
}

@RegisterMcuMessage(signature = "stats count=%u sum=%u sumsq=%u")
data class ResponseStats(val count:UInt, val sum: UInt, val sumSq: UInt): McuResponse
@RegisterMcuMessage(signature = "shutdown clock=%u static_string_id=%hu")
data class ResponseShutdown(val clock32: McuClock32, val staticStringId: UShort): McuResponse
