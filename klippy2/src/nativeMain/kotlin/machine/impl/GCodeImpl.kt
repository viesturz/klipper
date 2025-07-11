package machine.impl

import GCodeCommand
import GCodeHandler
import GCodeOutputSink
import GCodeContext
import io.github.oshai.kotlinlogging.KotlinLogging
import machine.CommandQueue
import machine.ConfigurationException
import machine.FailedToParseParamsException
import machine.InvalidGcodeException
import MachineRuntime

private val logger = KotlinLogging.logger("Gcode")

class GCodeImpl {
    data class CommandHandler(val name: String, val rawText: Boolean, val impl: GCodeHandler)
    internal val commands = HashMap<String, CommandHandler>()
    internal val muxCommands = HashMap<String, MuxCommandHandler>()

    fun registerCommand(name: String, rawText: Boolean, code: GCodeHandler) {
        if (commands.containsKey(name)) {
            throw ConfigurationException("Command $name already registered")
        }
        commands[name] = CommandHandler(name, rawText, code)
    }
    fun registerMuxCommand(command: String, muxParam: String, muxValue: String, handler: GCodeHandler) {
        var muxer = muxCommands[command]
        if (muxer == null) {
            muxer = MuxCommandHandler(muxParam)
            muxCommands[command] = muxer
        }
        if (muxParam != muxer.key) {
            throw ConfigurationException("Trying to register mux command $command with different param. Existing: ${muxer.key}  new: $muxParam")
        }
        if (muxer.handlers.containsKey(muxValue)) {
            throw ConfigurationException("Mux commands for $command $muxParam=$muxValue already registered")
        }
        muxer.handlers[muxValue] = handler
    }

    fun runner(commandQueue: CommandQueue, machineRuntime: MachineRuntime, outputHandler: GCodeOutputSink) = GCodeRunnerImpl(commandQueue, this, machineRuntime, outputHandler)
}

class GCodeRunnerImpl(val commandQueue: CommandQueue, val gCode: GCodeImpl, val machineRuntime: MachineRuntime, val outputHandler: GCodeOutputSink): GCodeContext {

    override suspend fun gcode(command: String) {
        logger.info { command }
        var command = command
        val name = command.split(" ")[0].uppercase()
        val isRawText = gCode.commands.get(name)?.rawText == true
        val comment = command.indexOfFirst { it == ';' }
        if (comment != -1 && !isRawText) {
            command = command.substring(0, comment)
        }
        command = command.trim()
        if (command.isBlank()) return
        val parts = command.split(" ")
        val args = parts.subList(1, parts.size)
        val map = when {
            isRawText -> mapOf()
            command.contains("=") -> parseWithAssign(command, args)
            else -> parseBasic(args)
        }
        val params = GCodeCommand(command, name, map, machineRuntime, commandQueue, outputHandler)
        val muxer = gCode.muxCommands[name]
        if (muxer != null && params.params.containsKey(muxer.key)) {
            val handler = muxer.handlers[params.params[muxer.key]]
                ?: throw InvalidGcodeException("No handler registered for ${params.name} ${muxer.key}=${params.params[muxer.key]}")
            handler(params)
            return
        }
        val handler = gCode.commands[name]
        if (handler == null) {
            if (muxer != null) {
                throw InvalidGcodeException("Gcode $command requires parameter ${muxer.key}")
            } else {
                throw InvalidGcodeException("Unknown gcode $command")
            }
        }
        handler.impl(this, params)
    }

    // Parses X10 Y20.5
    private fun parseBasic(parts: List<String>) = buildMap {
        parts.filter { it.isNotBlank() }.forEach {
            put(it.substring(0, 1).uppercase(), it.substring(1))
        }
    }
    // Parses X=10 Y=20.5 HEATER='foo bar'
    private fun parseWithAssign(cmd: String, parts: List<String>) = buildMap {
        var key = ""
        var quote = ' '
        var quotedPrefix: String? = null
        parts.forEach {
            if (quotedPrefix != null) {
                val v = "$quotedPrefix $it"
                if (v.endsWith(quote)) {
                    put(key, v.substring(1, v.length - 1))
                    quotedPrefix = null
                } else {
                    quotedPrefix = v
                }
                return@forEach
            }

            val pair = it.split('=')
            if (pair.size != 2) {
                throw FailedToParseParamsException(cmd)
            }
            key = pair[0].uppercase()
            val value: String = pair[1]
            if (value.startsWith('\'') or value.startsWith('"')) {
                quote = value[0]
                if (value.endsWith(quote)) {
                    put(key,  value.substring(1, value.length-1))
                    Unit
                } else {
                    quotedPrefix = value
                }
            } else {
                put(key, value.trim())
                Unit
            }
        }
    }
}

class MuxCommandHandler(val key: String) {
    val handlers = HashMap<String, GCodeHandler>()
}