package machine.impl

class UnknownGcodeException(cmd: String) : RuntimeException(cmd)
class MissingRequiredParameterException(cmd: String) : RuntimeException(cmd)
class FailedToParseParamsException(cmd: String) : RuntimeException(cmd)

class Gcode {
    data class CommandHandler(val name: String, val impl: (params: GcodeParams) -> Unit)

    private val stringGcmds = listOf("M117", "M118")
    val commands = HashMap<String, CommandHandler>()

    fun registerCommand(name: String, code: (params: GcodeParams) -> Unit) {
        commands[name] = CommandHandler(name, code)
    }
    fun run(cmd: String) {
        var command = cmd
        val comment = command.indexOfFirst { it == ';' }
        if (comment != -1) {
            command = command.substring(0, comment)
        }
        command = command.trim()
        val parts = command.split(" ")
        val name = parts[0].uppercase()
        val args = parts.subList(1, parts.size)
        val handler = commands[name] ?: throw UnknownGcodeException(cmd)
        val map = when {
            name in stringGcmds -> mapOf()
            command.contains("=") -> parseWithAssign(cmd, args)
            else -> parseBasic(cmd, args)
        }
        handler.impl(GcodeParams(command, map))
    }

    // Parses X10 Y20.5
    private fun parseBasic(cmd: String,parts: List<String>) = buildMap {
        parts.filter { it.isNotBlank() }.forEach {
            put(it.substring(0, 1).uppercase(), it.substring(1))
        }
    }
    // Parses X=10 Y=20.5
    private fun parseWithAssign(cmd: String, parts: List<String>) = buildMap {
        parts.filter { it.isNotBlank() }.forEach {
            val pair = it.split('=')
            if (pair.size != 2) {
                throw FailedToParseParamsException(cmd)
            }
            put(pair[0].uppercase(), pair[1].trim())
        }
    }
}

class GcodeParams(val raw: String, val params: Map<String, String>) {
    fun get(name: String, default: String? = null) =
        params[name] ?: default ?: throw MissingRequiredParameterException(name)
    fun getInt(name: String, default: Int? = null) =
        params[name]?.toInt() ?: default ?: throw MissingRequiredParameterException(name)
    fun getFloat(name: String, default: Float? = null) =
        params[name]?.toFloat() ?: default ?: throw MissingRequiredParameterException(name)
}
