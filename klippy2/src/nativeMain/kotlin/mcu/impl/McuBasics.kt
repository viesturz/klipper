package mcu.impl

import io.github.oshai.kotlinlogging.KotlinLogging

/** Component handling the basic MCU features. */
class McuBasics(val mcu: McuImpl, val configure: McuConfigure): McuComponent {
    private val logger = KotlinLogging.logger("${mcu.config.name} Basics")


    override fun configure(configure: McuConfigure) {
        configure.responseHandler(responseStatsParser, 0u, this::onStats)
        configure.responseHandler(responseShutdownParser, 0u, this::onShutdown)
    }

    fun onStats(stats: ResponseStats) {
        logger.trace { "Stats: $stats" }
    }
    fun onShutdown(resp: ResponseShutdown) {
        val reason = configure.identify.enumerationIdToValue("static_string_id")[resp.staticStringId.toInt()]  ?: "unknown Firmware error ${resp.staticStringId}"
        logger.error {  "Shutdown Firmware error: $reason" }
        mcu.shutdown("MCU ${mcu.config.name}: $reason")
    }
}

data class ResponseStats(val count:UInt, val sum: UInt, val sumSq: UInt): McuResponse
val responseStatsParser = ResponseParser("stats count=%u sum=%u sumsq=%u") {
    ResponseStats(parseU(), parseU(), parseU())
}
data class ResponseShutdown(val clock32: McuClock32, val staticStringId: UShort): McuResponse
val responseShutdownParser = ResponseParser("shutdown clock=%u static_string_id=%hu") {
    ResponseShutdown(parseU(), parseHU())
}