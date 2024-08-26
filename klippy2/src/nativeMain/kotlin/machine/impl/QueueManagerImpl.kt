package machine.impl

import MachineDuration
import MachineTime
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import machine.CommandQueue
import machine.Planner
import machine.QueueManager
import kotlin.time.Duration.Companion.seconds

/** Time it takes for a queue to start */
private const val QUEUE_START_TIME = 0.2
private const val QUEUE_CHECK_TIMEOUT = 1.0
private const val TIME_EAGER_START = -1.0
private const val TIME_WAIT = -2.0

class QueueManagerImpl(val reactor: Reactor): QueueManager {
    val queues = ArrayList<QueueImpl>()
    val plannerQueues = HashMap<Any, PlannerQueue<*>>()

    override fun newQueue(): CommandQueue {
        val q = QueueImpl(this)
        queues.add(q)
        return q
    }

    override fun newBackdatingQueue(): CommandQueue {
        TODO("Not yet implemented")
    }

    override fun abortAllQueues(): CommandQueue {
        TODO("Not yet implemented")
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> plannerQueueFor(planner: Planner<T>): PlannerQueue<T> {
        return plannerQueues.getOrPut(planner) { PlannerQueue(planner)} as PlannerQueue<T>
    }
}

data class Configuration(val block: (time: MachineTime) -> Unit)

sealed class Command {
    var configs = ArrayList<Configuration>()
}
class ConfigurationCommand: Command()
class PlannedCommand<Data>(val queue: CommandQueue, val planner: PlannerQueue<Data>, val data: Data): Command()
class WaitForCommand(val block: (time: MachineTime) -> Deferred<MachineTime>): Command()

class PlannerQueue<Data>(val planner: Planner<Data>) {
    val generationWindow: MachineDuration = 10.0
    val commands = ArrayList<PlannedCommand<Data>>()
    val data = ArrayList<Data>()
    internal fun addCommand(c: PlannedCommand<Data>) {
        commands.add(c)
        data.add(c.data)
    }

    fun peek() = commands.getOrNull(0)
    fun pop(cmd: PlannedCommand<*>, nextCommandTime: MachineTime) {
        commands.remove(cmd)
        data.removeFirst()
        // Potentially trigger generation for the next queue for this part.
        peek()?.run {
            if (queue != cmd.queue) {
                queue.tryGenerate()
            }
        }
    }

    fun tryGenerate(cmd: PlannedCommand<*>, time: MachineTime, force: Boolean): MachineTime? {
        if (cmd != peek()) return null
        if (cmd.queue.reactor.now + generationWindow < time) return null
        // TODO: check for gaps in the sequence
        val rest = data.subList(1, data.size)
        return planner.tryPlan(time, cmd.data, rest, force)
    }
}

class QueueImpl(override val manager: QueueManagerImpl): CommandQueue {
    var _nextCommandTime: MachineTime = TIME_EAGER_START
    val commands = ArrayList<Command>()
    private val logger = KotlinLogging.logger("QueueImpl")
    private var waiting: Deferred<MachineTime>? = null
    private var closed = false
    override fun isClosed() = closed
    override val reactor: Reactor
        get() = manager.reactor

    override fun <T> addPlanned(planner: Planner<T>, data: T) {
        require(!closed) { "Adding command to closed queue" }
        val plannerQueue = manager.plannerQueueFor(planner)
        val cmd = PlannedCommand(this, plannerQueue, data)
        plannerQueue.addCommand(cmd)
        commands.add(cmd)
        tryGenerate()
    }

    override fun add(block: (time: MachineTime) -> Unit) {
        require(!closed) { "Adding command to closed queue" }
        if (commands.isEmpty()) {
            // Add a no-op command to attach the config change to.
            commands.add(ConfigurationCommand())
        }
        commands.last().configs.add(Configuration(block))
        tryGenerate()
    }

    override fun wait(deferred: Deferred<MachineTime>) {
        require(!closed) { "Adding command to closed queue" }
        commands.add(WaitForCommand{ deferred })
        tryGenerate()
    }

    private fun canGenerate(): Boolean {
        val isWaiting = waiting?.isActive ?: false
        return _nextCommandTime != TIME_WAIT &&
                !commands.isEmpty() &&
                !isWaiting
    }

    private fun nextCommandTime(): MachineTime {
        val cmdTime = _nextCommandTime
        val nowTime = manager.reactor.now
        // Make sure there is enough time for command queuing.
        if (cmdTime == TIME_EAGER_START || cmdTime < nowTime + QUEUE_START_TIME) {
            return nowTime + QUEUE_START_TIME
        }
        return cmdTime
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun tryGenerate() {
        var waitFor: Deferred<MachineTime>? = null
        while (canGenerate()) {
            waiting?.run {
                if (isCompleted) waiting = null
            }
            val cmd = commands.first()
            val cmdTime = nextCommandTime()
            logger.info { "Attempting command $cmd at $cmdTime" }
            val endTime: MachineTime? = when(cmd) {
                is ConfigurationCommand -> cmdTime
                is WaitForCommand -> {
                    val deferred = cmd.block(cmdTime)
                    if (deferred.isCompleted) {
                        deferred.getCompleted()
                    } else {
                        waitFor = deferred
                        null
                    }
                }
                is PlannedCommand<*> -> cmd.planner.tryGenerate(cmd, cmdTime, force = false)
            }
            logger.info { "Command $cmd at endTime = $endTime" }
            if (endTime == null) {
                break
            }
            finalizeCommand(cmd, endTime)
        }
        logger.info { "TryGenerate - can not generate now, len=${commands.size} nextTime=$_nextCommandTime" }
        // Schedule next generation
        if (waitFor != null && waitFor != waiting) {
            waiting = waitFor
            reactor.launch { waitFor.await(); tryGenerate() }
        }
    }

    suspend fun forceGenerate() {
        waiting?.cancel()
        waiting = null
        while (commands.isNotEmpty()) {
            val cmd = commands.first()
            val cmdTime = nextCommandTime()
            logger.info { "Attempting command $cmd at $cmdTime" }
            val endTime = when (cmd) {
                is ConfigurationCommand -> cmdTime
                is WaitForCommand -> cmd.block(cmdTime).await()
                is PlannedCommand<*> -> cmd.planner.tryGenerate(cmd, cmdTime, force = true)!!
            }
            logger.info { "Command $cmd at endTime = $endTime" }
            finalizeCommand(cmd, endTime)
        }
    }

    private fun finalizeCommand(cmd: Command, endTime: MachineTime) {
        cmd.configs.forEach { it.block(endTime) }
        _nextCommandTime = endTime
        // Remove the command
        if (cmd is PlannedCommand<*>) {
            cmd.planner.pop(cmd, endTime)
        }
        commands.remove(cmd)
    }

    override suspend fun flush(): MachineTime {
        forceGenerate()
        return nextCommandTime()
    }

    override fun fork(): CommandQueue {
        val fork = QueueImpl(manager)
        val deferred = CompletableDeferred<MachineTime>()
        add { time -> deferred.complete(time) }
        fork.wait(deferred)
        manager.queues.add(this)
        return fork
    }

    override fun join(other: CommandQueue) {
        val deferred = CompletableDeferred<MachineTime>()
        wait(deferred)
        other.add { time -> deferred.complete(time) }
        other.close()
    }

    override fun close() {
        closed = true
        tryGenerate()
    }

    override fun abort() {
        commands.clear()
        close()
    }
}
