package machine.impl

import MachineDuration
import MachineTime
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import machine.Command
import machine.CommandQueue
import machine.QueueManager
import machine.TIME_BUSY
import machine.TIME_EAGER_START
import machine.TIME_WAIT
import machine.WaitForTimeCommand
import machine.addQueuedMcuCommand
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds


/** Time it takes for a queue to start */
private const val QUEUE_START_TIME = 0.2
private const val QUEUE_CHECK_TIMEOUT = 1.0

class QueueManagerImpl(val reactor: Reactor): QueueManager {
    val queues = ArrayList<QueueImpl>()
    val partCommands = HashMap<Any, PartQueue>()

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

    fun partQueue(part: Any) = partCommands.getOrPut(part) { PartQueue(part) }
}

class PartQueue(val part: Any) {
    val commands = ArrayList<Command>()
    var minDuration = 0.0
    var lookaheadTime = 0.0
    internal fun addCommand(c: Command) {
        if (commands.isEmpty()) lookaheadTime = c.lookaheadTime
        commands.add(c)
        c.partQueue = this
        minDuration += c.measureMin()
    }

    fun peek() = commands.getOrNull(0)
    fun pop(cmd: Command, nextCommandTime: MachineTime) {
        commands.remove(cmd)
        cmd.partQueue = null
        minDuration -= cmd.measureMin()
        lookaheadTime = peek()?.lookaheadTime ?: 0.0
        // Potentially trigger generation for the next queue for this part.
        peek()?.run {
            if (minTime != TIME_WAIT) {
                minTime = max(minTime, nextCommandTime)
            }
            if (queue != cmd.queue) {
                queue?.tryGenerate()
            }
        }
    }
    fun isEmpty() = commands.isEmpty()
    fun canGenerate(c: Command): Boolean {
        if (c != peek()) return false

        return true
    }
}

class QueueImpl(override val manager: QueueManagerImpl): CommandQueue {
    val generationWindow: MachineDuration = 10.0
    var _nextCommandTime: MachineTime = TIME_EAGER_START
    val commands = ArrayList<Command>()
    private val logger = KotlinLogging.logger("QueueImpl")
    private var closed = false
    override fun isClosed() = closed
    var pollingJob: Job? = null

    override fun add(cmd: Command) {
        require(!closed) { "Adding command to close queue" }
        manager.partQueue(cmd.origin).addCommand(cmd)
        cmd.queue = this
        commands.add(cmd)
        tryGenerate()
    }

    private fun canGenerate() =
        _nextCommandTime != TIME_WAIT &&
        !commands.isEmpty() &&
        commands.first().minTime != TIME_WAIT &&
        commands.first().partQueue?.canGenerate(commands.first()) ?: false &&
        !needToWaitForNextCommand()

    private fun needToWaitForNextCommand()  =
        !commands.isEmpty() &&
        manager.reactor.now + generationWindow < _nextCommandTime

    private fun nextCommandTime(): MachineTime {
        val cmdTime = _nextCommandTime
        val nowTime = manager.reactor.now
        // Make sure there is enough time for command queuing.
        if (cmdTime == TIME_EAGER_START || cmdTime < nowTime + QUEUE_START_TIME) {
            return nowTime + QUEUE_START_TIME
        }
        return cmdTime
    }

    override fun tryGenerate() {
        while (canGenerate()) {
            val cmd = commands.first()
            val partQueue = cmd.partQueue ?: throw RuntimeException("Command with no partQueue")
            var cmdTime = nextCommandTime()
            cmdTime = max(cmdTime, cmd.minTime)
            logger.info {  "Attempting command $cmd at $cmdTime" }
            var endTime = commands.first().run(manager.reactor, cmdTime, partQueue.commands)
            logger.info { "Command $cmd at endTime = $endTime" }
            when (endTime) {
                TIME_WAIT -> break
                TIME_BUSY -> break
                else -> {}
            }
            _nextCommandTime = endTime
            // Remove the command
            partQueue.pop(cmd, _nextCommandTime)
            commands.remove(cmd)
            cmd.queue = null
        }
        logger.info { "TryGenerate - can not generate now, len=${commands.size} nextTime=$_nextCommandTime, cMinTime=${commands.firstOrNull()?.minTime}" }
        // Schedule next generation
        maybeSchedulePolling()
    }

    private fun maybeSchedulePolling() {
        if (needToWaitForNextCommand() && !commands.isEmpty() && pollingJob == null) {
            pollingJob = manager.reactor.launch {
                while (needToWaitForNextCommand())
                {
                    delay(QUEUE_CHECK_TIMEOUT.seconds)
                    tryGenerate()
                }
                pollingJob = null
            }
        }
    }

    override suspend fun flush(): MachineTime {
        while (commands.isNotEmpty()) {
            tryGenerate()
            delay(100)
        }
        return nextCommandTime()
    }

    override fun fork(): CommandQueue {
        val fork = QueueImpl(manager)
        fork._nextCommandTime = TIME_WAIT
        addQueuedMcuCommand(fork) { time ->
            fork._nextCommandTime = time
            fork.tryGenerate()
        }
        manager.queues.add(this)
        return fork
    }

    override fun join(other: CommandQueue) {
        // Block this queue until joined
        val join = WaitForTimeCommand(other)
        add(join)
        other.addQueuedMcuCommand(this, join::setTime)
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
