package machine.impl

import kotlinx.coroutines.delay
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

typealias ReactorTime = TimeSource.Monotonic.ValueTimeMark

// TODO: All methods here are thread safe
class Reactor {
    data class Event(val time: ReactorTime, val block: (event: Event) -> ReactorTime?)
    val TIME_SHUTDOWN = curTime - 1.days

    private val pendingEvents = ArrayList<Event>()

    fun schedule(time: ReactorTime, block: (event:Event) -> ReactorTime?): Event {
        val event = Event(time, block)
        val index = pendingEvents.indexOfFirst { it.time > time }
        if (index == -1) {
            pendingEvents.add(event)
        } else {
            pendingEvents.add(index, event)
        }
        return event
    }

    val curTime
        get() = TimeSource.Monotonic.markNow()

    fun cancel(event: Event) {
        pendingEvents.remove(event)
    }

    fun shutdown() {
        schedule(TIME_SHUTDOWN) { null }
    }

    suspend fun runEventLoop() {
        println("Reactor started")
        runloop@ while (true) {
            var nextEvent: Event? = null
            while(pendingEvents.isNotEmpty()) {
                nextEvent = pendingEvents.first()
                when {
                    nextEvent.time == TIME_SHUTDOWN -> {
                        break@runloop
                    }
                    nextEvent.time > curTime -> {
                        break
                    }
                    pendingEvents.remove(nextEvent) -> {
                        val next = nextEvent.block(nextEvent)
                        if (next != null) {
                            schedule(next, nextEvent.block)
                        }
                    }
                    else -> {}
                }
                nextEvent = null
            }
            var delayTime = 1.seconds
            if (nextEvent != null) {
                val nextDelay = -nextEvent.time.elapsedNow()
                if (nextDelay < delayTime) {
                    delayTime = nextDelay
                }
            }
            delay(delayTime)
        }
        println("Reactor shut down")
    }
}
