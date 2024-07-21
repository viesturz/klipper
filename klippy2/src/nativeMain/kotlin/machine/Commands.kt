package machine

import MachineDuration
import MachineTime
import machine.impl.PartQueue
import machine.impl.Reactor

val TIME_EAGER_START = -1.0
val TIME_WAIT = -2.0

/** Individual command to be added to a queue. */
abstract class Command(
    /** Identifies the object executing this command.
     *  Commands from the same origin will be chained together. */
    val origin: Any) {
    var partQueue: PartQueue? = null
    var queue: CommandQueue? = null
    var minTime = TIME_EAGER_START

    /** Will not attempt to generate this command until chained commands up to this far in the future are available. */
    val lookaheadTime: MachineDuration
        get() = 0.0

    /** Measure the min time needed for this command. */
    open fun measureMin(): MachineDuration = 0.0
    /** Measure the actual time needed for this command. Or null if not enough followup commands in the queue. */
    abstract fun measureActual(followupCommands: List<Command>): MachineDuration?
    /** Generate this command. Will be called upon successful measureActual. Finalizes the processing of a command. */
    abstract fun generate(reactor: Reactor, startTime: MachineTime, duration: MachineDuration, followupCommands: List<Command>)
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

    override fun measureMin() = 0.0
    override fun measureActual(followupCommands: List<Command>) = 0.0

    override fun generate(
        reactor: Reactor,
        startTime: MachineTime,
        duration: MachineDuration,
        followupCommands: List<Command>
    ) {
        generate(startTime)
    }
})

/** Add a command that will be executed locally without sending to the MCU.
 *  IE, target temperature change, gcode offset change. */
fun CommandQueue.addLocalCommand(origin: Any, generate: (time: MachineTime) -> Unit)
        = add(object: Command(origin) {
    override fun measureMin() = 0.0
    override fun measureActual(followupCommands: List<Command>) = 0.0
    override fun generate(
        reactor: Reactor,
        startTime: MachineTime,
        duration: MachineDuration,
        followupCommands: List<Command>
    ) {
        reactor.schedule(startTime) {
            generate(startTime)
            return@schedule null
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
    override fun measureActual(followupCommands: List<Command>) = 0.0
    override fun generate(
        reactor: Reactor,
        startTime: MachineTime,
        duration: MachineDuration,
        followupCommands: List<Command>
    ) {}
}

/** A command to wait for a duration. */
class DelayCommand(val duration: MachineDuration) : Command(Unit) {
    override fun measureMin() = duration
    override fun measureActual(followupCommands: List<Command>) = duration
    override fun generate(
        reactor: Reactor,
        startTime: MachineTime,
        duration: MachineDuration,
        followupCommands: List<Command>
    ) {}
}
