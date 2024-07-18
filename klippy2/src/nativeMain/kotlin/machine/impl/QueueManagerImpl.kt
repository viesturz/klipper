package machine.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import machine.Command
import machine.CommandQueue
import machine.QueueManager
import machine.TIME_EAGER_START
import machine.TIME_WAIT
import machine.addBasicCommand
import kotlin.math.max


/** Time it takes for a queue to start */
private val QUEUE_START_TIME = 0.2

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
    var nextCommandTime: MachineTime = TIME_EAGER_START
    val commands = ArrayList<Command>()
    private val logger = KotlinLogging.logger("QueueImpl")
    private var closed = false
    override fun isClosed() = closed

    override fun addCommand(cmd: Command) {
        require(!closed) { "Adding command to close queue" }
        manager.partQueue(cmd.origin).addCommand(cmd)
        cmd.queue = this
        commands.add(cmd)
        tryGenerate()
    }

    private fun canGenerate() =
        nextCommandTime != TIME_WAIT &&
        !commands.isEmpty() &&
        commands.first().minTime != TIME_WAIT &&
        commands.first().partQueue?.canGenerate(commands.first()) ?: false &&
        nextCommandTime < manager.reactor.now + generationWindow

    override fun tryGenerate() {
        while (canGenerate()) {
            val cmd = commands.first()
            val partQueue = cmd.partQueue ?: throw RuntimeException("Comand with no partQueue")
            val actualDuration = commands.first().measureActual(partQueue.commands) ?: break
            if (nextCommandTime == TIME_EAGER_START) {
                nextCommandTime = manager.reactor.now + QUEUE_START_TIME
            }
            val cmdTime = max(nextCommandTime, cmd.minTime)
            logger.atInfo { "Generating command $cmd at $nextCommandTime" }
            cmd.generate(cmdTime, actualDuration, partQueue.commands)
            nextCommandTime = cmdTime + actualDuration
            // Remove the command
            partQueue.pop(cmd, nextCommandTime)
            commands.remove(cmd)
            cmd.queue = null
        }
    }

    override fun fork(): CommandQueue {
        val fork = QueueImpl(manager)
        fork.nextCommandTime = TIME_WAIT
        addBasicCommand(fork) { time ->
            fork.nextCommandTime = time
            fork.tryGenerate()
        }
        manager.queues.add(this)
        return fork
    }

    override fun join(other: CommandQueue) {
        // Block this queue until joined
        val join = WaitForTimeCommand(other)
        addCommand(join)
        other.addBasicCommand(this, join::setTime)
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
        startTime: MachineTime,
        duration: MachineDuration,
        followupCommands: List<Command>
    ) {}
}
