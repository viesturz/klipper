package logging

import io.github.oshai.kotlinlogging.FormattingAppender
import io.github.oshai.kotlinlogging.KLoggingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import okio.BufferedSink
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.native.concurrent.isFrozen
import kotlin.time.Duration.Companion.seconds

class LogWriter(pathStr: String, scope: CoroutineScope): FormattingAppender() {
    private val path = pathStr.toPath()
    private val channel = Channel<String>(capacity = Channel.BUFFERED)
    private var flushJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val context = newSingleThreadContext("LogWriter")
    val writingJob = scope.launch(context) {
        FileSystem.SYSTEM.sink(path, mustCreate = false).buffer().use { sink ->
            for (m in channel) {
                sink.writeUtf8(m)
                sink.writeUtf8("\n")
                if (flushJob?.isCompleted != false) {
                    flushJob = launch { flush(sink) }
                }
            }
            flushJob?.cancelAndJoin()
        }
    }

    private suspend fun flush(sink: BufferedSink) {
        delay(0.3.seconds)
        sink.flush()
    }

    override fun logFormattedMessage(loggingEvent: KLoggingEvent, formattedMessage: Any?) {
        channel.trySend(formattedMessage.toString())
    }
}