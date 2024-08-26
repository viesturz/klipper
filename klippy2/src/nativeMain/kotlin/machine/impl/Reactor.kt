package machine.impl

import MachineTime
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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

    private val logger = KotlinLogging.logger("Reactor")
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val context = newSingleThreadContext("Reactor")
    val scope = CoroutineScope(context)

    data class Event(var time: MachineTime, val block: suspend () -> Unit)
    private val orderedEvents = ArrayList<Event>()
    private var poke = CompletableDeferred<Boolean>()
    var orderedJob: Job? = null

    fun launch(block: suspend CoroutineScope.() -> Unit)  = scope.launch( block = block )

    val now: MachineTime
        get() = Reactor.now

    fun shutdown() {
        logger.info { "Shutdown" }
        scope.cancel()
    }

    /** Schedules to run the code at expected time,
     * ensuring ordering is preserved for same time calls. */
    fun scheduleOrdered(time: MachineTime, block: suspend () -> Unit) {
        logger.trace { "Scheduling event to $time" }
        val event = Event(time, block)
        val index = orderedEvents.indexOfFirst { it.time > event.time }
        if (index == -1) {
            orderedEvents.add(event)
        } else {
            orderedEvents.add(index, event)
        }
        if (index == 0) {
            poke.complete(true)
        }
        if (orderedJob == null) {
            orderedJob = launch {
                    try {
                        logger.info { "Reactor started" }
                        schedulingLoop()
                    } catch (e: CancellationException) {
                        logger.warn { "Reactor cancelled" }
                    }
                }
        }
    }

    /** Event pumping loop for scheduled events. */
    private suspend fun schedulingLoop() {
        while (true) {
            var nextEvent: Event? = null
            while(orderedEvents.isNotEmpty()) {
                nextEvent = orderedEvents.first()
                when {
                    nextEvent.time > now -> {
                        break
                    }
                    orderedEvents.remove(nextEvent) -> {
                        val t = nextEvent.time
                        logger.trace { "Running event at $t" }
                        nextEvent.block()
                    }
                    else -> {}
                }
                nextEvent = null
            }
            var delayTime = 1.0
            if (nextEvent != null) {
                val nextDelay = nextEvent.time-now
                if (nextDelay < delayTime) {
                    delayTime = nextDelay
                }
            }
            if (delayTime > 0) {
                poke = CompletableDeferred()
                try {
                    withTimeout(delayTime.seconds) {
                        poke.await()
                    }
                } catch (e: TimeoutCancellationException) {}
            }
        }
    }
    companion object {
        @OptIn(ExperimentalForeignApi::class)
        val now: MachineTime
            get() = chelper.get_monotonic()
    }

}

suspend fun waitUntil(time: MachineTime) =
    delay(max(0.0, time - Reactor.now).seconds)
