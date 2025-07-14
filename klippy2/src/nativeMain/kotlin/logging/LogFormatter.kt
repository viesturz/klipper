package logging

import io.github.oshai.kotlinlogging.Formatter
import io.github.oshai.kotlinlogging.KLoggingEvent
import kotlinx.cinterop.ExperimentalForeignApi
import utils.format

class LogFormatter: Formatter {
    @OptIn(ExperimentalForeignApi::class)
    override fun formatMessage(loggingEvent: KLoggingEvent): String {
        val timestamp = chelper.get_monotonic()
        val msg = "${timestamp.format(0,5)} ${loggingEvent.level}: [${loggingEvent.loggerName}] ${loggingEvent.message}"
        val cause = loggingEvent.cause
        if (cause == null) return msg
        return "$msg\n${cause.stackTraceToString()}"
    }
}