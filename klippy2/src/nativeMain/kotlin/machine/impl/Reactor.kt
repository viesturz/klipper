package machine.impl

import MachineTime
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withTimeout
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

/** A single threaded scope for running printer events. */
class Reactor {
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val context = newSingleThreadContext("Reactor")
    val scope = CoroutineScope(context)

    fun launch(block: suspend CoroutineScope.() -> Unit)  = scope.launch( block = block )

    val now: MachineTime
        get() = Reactor.now

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        @OptIn(ExperimentalForeignApi::class)
        val now: MachineTime
            get() = chelper.get_monotonic()
    }

}

suspend fun CoroutineScope.waitUntil(time: MachineTime) =
    delay(max(0.0, time - Reactor.now).seconds)
