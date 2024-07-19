package machine.impl

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/** Machine time in seconds. Counted from primary MCU boot time. */
typealias MachineTime = Double
typealias MachineDuration = Double

// TODO: All methods here are thread safe
class Reactor {
    data class Event(var time: MachineTime, val block: suspend (event: Event) -> MachineTime?)
    private val TIME_SHUTDOWN = -1.0

    private val pendingEvents = ArrayList<Event>()

    fun runNow(block: suspend () -> Unit) = schedule(now) {
        block()
        null
    }

    fun schedule(time: MachineTime, block: suspend (event:Event) -> MachineTime?): Event {
        val event = Event(time, block)
        val index = pendingEvents.indexOfFirst { it.time > time }
        if (index == -1) {
            pendingEvents.add(event)
        } else {
            pendingEvents.add(index, event)
        }
        return event
    }

    fun reschedule(event: Event, time: MachineTime) {
        pendingEvents.remove(event)
        event.time = time
        val index = pendingEvents.indexOfFirst { it.time > time }
        if (index == -1) {
            pendingEvents.add(event)
        } else {
            pendingEvents.add(index, event)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    val now: MachineTime
        get() = chelper.get_monotonic()

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
                    nextEvent.time > now -> {
                        break
                    }
                    pendingEvents.remove(nextEvent) -> {
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
            delay(delayTime.seconds)
        }
        println("Reactor shut down")
    }
}
