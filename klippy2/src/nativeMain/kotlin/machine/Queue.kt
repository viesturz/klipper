package machine

import MachineTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import machine.impl.Reactor

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

/** API for command queuing and timing. */
interface CommandQueue {
    val manager: QueueManager
    val reactor: Reactor

    /** Add a basic command into this queue.
     * It has has 0 run time and does not interrupt planned commands. */
    fun add(block: (time: MachineTime) -> Unit)
    /** Adds a planned command. Planned commands take some time to execute and are dependant on
     * adjacent commands. */
    fun <T> addPlanned(planner: Planner<T>, data: T)
    /** Wait until specific machine time. This flushes any planned commands. */
    fun wait(time: MachineTime) = wait(CompletableDeferred(time))
    /** Wait until deferred completes.
     *  This flushes any planned commands if the generation gets blocked by the wait. */
    fun wait(deferred: Deferred<MachineTime>)
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
    /** Generate any queued commands and return end time.
     * Returns next available schedule time if no new commands queued. */
    suspend fun flush(): MachineTime
}

/** Add a command that will be run locally at the planned time without MCU scheduling support.
 *  IE, target temperature change. */
fun CommandQueue.addLocal(block: () -> Unit) = add { time ->  reactor.scheduleOrdered(time, block) }

interface Planner<Data> {
    fun tryPlan(startTime: MachineTime, cmd: Data, followupCommands: List<Data>, force: Boolean): MachineTime?
}