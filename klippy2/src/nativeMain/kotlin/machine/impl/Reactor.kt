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

// TODO: All methods here are thread safe
class Reactor {
    data class Event(var time: MachineTime, val block: suspend (event: Event) -> MachineTime?)

    private val pendingEvents = ArrayList<Event>()
    private var poke = CompletableDeferred<Boolean>()
    private val logger = KotlinLogging.logger("Reactor")
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val context = newSingleThreadContext("Reactor")
    val scope = CoroutineScope(context)

    fun launch(block: suspend CoroutineScope.() -> Unit)  = scope.launch( block = block )

    /** Deprecated, just use scope.launch{} */
    fun runNow(block: suspend () -> Unit) {
        logger.trace { "RunNow" }
        scope.launch {
            logger.trace {  "RunNow running" }
            block()
        }
    }

    fun schedule(time: MachineTime, block: suspend (event:Event) -> MachineTime?): Event {
        logger.trace { "Scheduling event to $time" }
        val event = Event(time, block)
        queueEvent(event)
        return event
    }

    fun reschedule(event: Event, time: MachineTime) {
        pendingEvents.remove(event)
        event.time = time
        queueEvent(event)
    }

    private fun queueEvent(event: Event) {
        val index = pendingEvents.indexOfFirst { it.time > event.time }
        if (index == -1) {
            pendingEvents.add(event)
        } else {
            pendingEvents.add(index, event)
        }
        if (index == 0) {
            poke.complete(true)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    val now: MachineTime
        get() = chelper.get_monotonic()

    fun cancel(event: Event) {
        pendingEvents.remove(event)
    }

    fun start() {
        scope.launch {
            try {
                logger.info { "Reactor started" }
                schedulingLoop()
            } catch (e: CancellationException) {
                logger.warn { "Reactor cancelled" }
            }
        }
    }

    fun shutdown() {
        logger.info { "Stopping reactor" }
        scope.cancel()
    }

    /** Event pumping loop for scheduled events. */
    private suspend fun schedulingLoop() {
        runloop@ while (true) {
            var nextEvent: Event? = null
            while(pendingEvents.isNotEmpty()) {
                nextEvent = pendingEvents.first()
                when {
                    nextEvent.time > now -> {
                        break
                    }
                    pendingEvents.remove(nextEvent) -> {
                        val t= nextEvent.time
                        logger.trace { "Running event at $t" }
                        val nextTime = nextEvent.block(nextEvent)
                        if (nextTime != null) {
                            reschedule(nextEvent, nextTime)
                        }
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

suspend fun CoroutineScope.waitUntil(time: MachineTime) =
    delay(max(0.0, time - Reactor.now).seconds)


