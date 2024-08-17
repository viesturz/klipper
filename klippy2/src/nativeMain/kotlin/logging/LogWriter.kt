package logging

import io.github.oshai.kotlinlogging.FormattingAppender
import io.github.oshai.kotlinlogging.KLoggingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

class LogWriter(pathStr: String, scope: CoroutineScope): FormattingAppender() {
    val path = pathStr.toPath()
    val channel = Channel<String>(capacity = Channel.BUFFERED)

    private val context = newSingleThreadContext("LogWriter")
    val writingJob = scope.launch(context) {
        FileSystem.SYSTEM.sink(path, mustCreate = false).buffer().use { sink ->
            for (m in channel) {
                sink.writeUtf8(m)
                sink.writeUtf8("\n")
                sink.flush()
            }
        }
    }

    override fun logFormattedMessage(loggingEvent: KLoggingEvent, formattedMessage: Any?) {
        channel.trySend(formattedMessage.toString())
    }
}