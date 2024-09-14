package machine.impl

import Temperature
import celsius
import io.github.oshai.kotlinlogging.KotlinLogging
import machine.CommandQueue
import machine.GCodeHandler
import machine.GCodeRunner
import machine.MachineRuntime
import machine.getPartByName
import mcu.ConfigurationException

class CommandException(cmd: String) : RuntimeException(cmd)
class InvalidGcodeException(cmd: String) : RuntimeException(cmd)
class MissingRequiredParameterException(cmd: String) : RuntimeException(cmd)
class FailedToParseParamsException(cmd: String) : RuntimeException(cmd)

class GCodeCommand(val raw: String,
                   val name: String,
                   val params: Map<String, String>,
                   val runtime: MachineRuntime,
                   val queue: CommandQueue,
                   val runner: GCodeRunner,
) {
    fun get(name: String, default: String? = null) =
        params[name] ?: default ?: throw MissingRequiredParameterException(name)
    fun getInt(name: String, default: Int? = null) =
        params[name]?.toInt() ?: default ?: throw MissingRequiredParameterException(name)
    fun getFloat(name: String, default: Float? = null) =
        params[name]?.toFloat() ?: default ?: throw MissingRequiredParameterException(name)
    fun getDouble(name: String, default: Double? = null) =
        params[name]?.toDouble() ?: default ?: throw MissingRequiredParameterException(name)
    fun getCelsius(name: String, default: Temperature? = null) =
        params[name]?.toDouble()?.celsius ?: default ?: throw MissingRequiredParameterException(name)
    inline fun <reified PartType> getPartByName(param: String) = runtime.getPartByName<PartType>(get(param)) ?:
        throw InvalidGcodeException("${PartType::class.simpleName} with name ${get(param)} not found")
    fun respond(msg: String) = runner.respond(msg)
}

typealias GCodeOutputSink = (message: String) -> Unit
private val logger = KotlinLogging.logger("Gcode")

class GCode {
    data class CommandHandler(val name: String, val rawText: Boolean, val impl: GCodeHandler)
    internal val commands = HashMap<String, CommandHandler>()
    internal val muxCommands = HashMap<String, MuxCommandHandler>()

    fun registerCommand(name: String, rawText: Boolean = false, code: GCodeHandler) {
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

    fun runner(commandQueue: CommandQueue, machineRuntime: MachineRuntime, outputHandler: GCodeOutputSink): GCodeRunner = GCodeRunnerImpl(commandQueue, this, machineRuntime, outputHandler)
}

class GCodeRunnerImpl(val commandQueue: CommandQueue, val gCode: GCode, val machineRuntime: MachineRuntime, val outputHandler: GCodeOutputSink): GCodeRunner {
    override fun respond(msg: String) = outputHandler(msg)

    override suspend fun gcode(cmd: String) {
        logger.info { cmd }
        var command = cmd
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
            command.contains("=") -> parseWithAssign(cmd, args)
            else -> parseBasic(args)
        }
        val params = GCodeCommand(command, name, map, machineRuntime, commandQueue, this)
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
                throw InvalidGcodeException("Gcode $cmd requires parameter ${muxer.key}")
            } else {
                throw InvalidGcodeException("Unknown gcode $cmd")
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