package machine

import MachineDuration
import MachineTime
import kotlinx.coroutines.Job
import machine.impl.PartQueue
import machine.impl.Reactor
import platform.posix.time
import kotlin.math.min

/* Can start at any suitable time. */
val TIME_EAGER_START = -1.0
/** Cannot start yet, check back later.  */
val TIME_WAIT = -2.0
/** Actively running, check back later.  */
val TIME_BUSY = -3.0
/** Need more elements in queue, check back later.  */
val TIME_INSUFFICIENT_QUEUE = -4.0

/** Individual command to be added to a queue. */
abstract class Command(
    /** Identifies the object executing this command.
     *  Commands from the same origin will be chained together. */
    val origin: Any) {
    var partQueue: PartQueue? = null
    var queue: CommandQueue? = null
    /** Minimum time this command can start.
     *  Wth special values for EAGER_START and WAIT.
     */
    var minTime = TIME_EAGER_START

    /** Will not attempt to generate this command until chained commands up to this far in the future are available. */
    val lookaheadTime: MachineDuration
        get() = 0.0

    /** Measure the min time needed for this command. */
    open fun measureMin(): MachineDuration = 0.0
    /** Generate this command. Called when all preceding commands have been run.
     *  Return if done:
     *   - end time of the command, if it as a deterministic duration
     *   - TIME_EAGER_START, if the next command can start as soon as possible
     *   Return if not done, will be called again later:
     *   - TIME_WAIT, the machine time needs to advance before this command can be run.
     *   - TIME_BUSY, the command is actively running, end time not known.
     */
    abstract fun run(reactor: Reactor, startTime: MachineTime, followupCommands: List<Command>): MachineTime
}

interface QueueManager {
    /** Start a new queue, starting as soon as possible. */
    fun newQueue(): CommandQueue
    /** Create a new queue that will be started on Join with another queue.
     *  Aiming to complete together with the join point.
     *  Note that if this queue takes longer than remaining commands in the target queue,
     *  it may stall it until this queue completes. */
    fun newBackdatingQueue(): CommandQueue
    /** Abort all existing queues. Create a new queue, starting when they are all done.  */
    fun abortAllQueues(): CommandQueue
}

/**  */
interface CommandQueue {
    val manager: QueueManager

    /** Add a command into this queue. */
    fun add(cmd: Command)
    /** Create another queue starting from the time of last move in this queue. */
    fun fork(): CommandQueue
    /** Close the other queue and insert a wait until the other queue is done. */
    fun join(other: CommandQueue)
    /** Close this queue, flush any ongoing commands. */
    fun close()
    fun isClosed(): Boolean
    /** Close this queue, discard any unprocessed commands. */
    fun abort()
    /** Tries to generate output, as much as available */
    fun tryGenerate()
}

/** Add a basic command that needs to be queued to the MCU queue.
 * IE, a pin value change.
 * */
fun CommandQueue.addBasicMcuCommand(origin: Any, generate: (time: MachineTime) -> Unit)
    = add(object: Command(origin) {
    override fun run(
        reactor: Reactor,
        startTime: MachineTime,
        followupCommands: List<Command>
    ): MachineTime {
        generate(startTime)
        return startTime
    }
})

/** Add a command that will be queued locally without sending to the MCU.
 *  IE, target temperature change, gcode offset change. */
fun CommandQueue.addLocalCommand(origin: Any, impl: (time: MachineTime) -> Unit)
        = add(object: Command(origin) {
    override fun run(
        reactor: Reactor,
        startTime: MachineTime,
        followupCommands: List<Command>
    ): MachineTime {
        reactor.scheduleOrdered(startTime) {
            impl(startTime)
        }
        return startTime
    }
})

/** A command that takes indeterminate time to run.
 *  IE: Wait for temperature, HOME, etc.
 * */
fun CommandQueue.addLongRunningCommand(origin: Any, function: suspend () -> Unit) = add(object: Command(origin) {
    var job: Job? = null
    override fun run(
        reactor: Reactor,
        startTime: MachineTime,
        followupCommands: List<Command>
    ): MachineTime {
        val curJob = job
        if (curJob == null) {
            job = reactor.launch { function(); tryGenerate() }
            return TIME_BUSY
        } else if (curJob.isCompleted) {
            return TIME_EAGER_START
        } else {
            return TIME_BUSY
        }
    }
})

/** A command to wait until specific machine time. Or until the command is resolved. */
class WaitForTimeCommand(origin: Any, time: MachineTime = TIME_WAIT) : Command(origin) {
    init {
        minTime = time
    }
    /** Sets the time to resume and attempt to resume. */
    fun setTime(time: MachineTime) {
        this.minTime = time
        this.queue?.tryGenerate()
    }
    override fun run(
        reactor: Reactor,
        startTime: MachineTime,
        followupCommands: List<Command>
    ) = if (minTime < 0) minTime else min(startTime, minTime)
}

/** A command to wait for a duration. */
class DwellCommand(val duration: MachineDuration) : Command(Unit) {
    override fun measureMin() = duration
    override fun run(
        reactor: Reactor,
        startTime: MachineTime,
        followupCommands: List<Command>
    ) = startTime + duration
}
