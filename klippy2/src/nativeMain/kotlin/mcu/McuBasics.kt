package mcu

import io.github.oshai.kotlinlogging.KotlinLogging
import utils.RegisterMcuMessage

/** Component handling the basic MCU features. */
class McuBasics(val mcu: McuImpl, val configure: McuConfigure): McuComponent {
    private val logger = KotlinLogging.logger(mcu.config.name)

    init {
        configure.responseHandler<ResponseStats>( 0u, this::onStats)
        configure.responseHandler<ResponseShutdown>( 0u, this::onShutdown)
        configure.responseHandler<ResponseIsShutdown>( 0u, this::onIsShutdown)
    }

    fun onStats(stats: ResponseStats) {
        logger.trace { "Stats: $stats" }
    }
    fun onShutdown(resp: ResponseShutdown) {
        val reason = configure.firmware.getStaticString(resp.staticStringId, "unknown Firmware error ${resp.staticStringId}")
        logger.error {  "Shutdown Firmware error: $reason" }
        mcu.shutdown("MCU ${mcu.config.name}: $reason")
    }
    fun onIsShutdown(resp: ResponseIsShutdown) {
        val reason = configure.firmware.getStaticString(resp.staticStringId, "unknown Firmware error ${resp.staticStringId}")
        logger.error {  "Shutdown Firmware error: $reason" }
    }
}

@RegisterMcuMessage(signature = "stats count=%u sum=%u sumsq=%u")
data class ResponseStats(val count:UInt, val sum: UInt, val sumSq: UInt): McuResponse
@RegisterMcuMessage(signature = "shutdown clock=%u static_string_id=%hu")
data class ResponseShutdown(val clock32: McuClock32, val staticStringId: UShort): McuResponse
@RegisterMcuMessage(signature = "is_shutdown static_string_id=%hu")
data class ResponseIsShutdown(val staticStringId: UShort): McuResponse
