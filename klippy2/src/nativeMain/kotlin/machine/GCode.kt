package machine

import MachineRuntime
import Temperature
import celsius
import getPartByName

interface GCode {
    fun registerCommand(name: String, rawText: Boolean = false, code: GCodeHandler)
    fun registerMuxCommand(command: String, muxParam: String, muxValue: String, handler: GCodeHandler)
    fun runner(commandQueue: CommandQueue, machineRuntime: MachineRuntime, outputHandler: GCodeOutputSink): GCodeRunner
}

typealias GCodeHandler = suspend GCodeRunner.(cmd: GCodeCommand) -> Unit
typealias GCodeOutputSink = (message: String) -> Unit

interface GCodeRunner {
    suspend fun gcode(cmd: String)
    fun respond(msg: String)
}

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
    fun getDouble(name: String, default: Double? = null, above: Double? = null): Double {
        val x = params[name]?.toDouble() ?: default ?: throw MissingRequiredParameterException(name)
        return validate(name, x, above = above)
    }

    fun getCelsius(name: String, default: Temperature? = null) =
        params[name]?.toDouble()?.celsius ?: default ?: throw MissingRequiredParameterException(name)
    inline fun <reified PartType> getPartByName(param: String) = runtime.getPartByName<PartType>(get(param)) ?:
        throw InvalidGcodeException("${PartType::class.simpleName} with name ${get(param)} not found")
    fun respond(msg: String) = runner.respond(msg)
}

private fun <T: Comparable<T>> validate(name: String, value: T, above: T? = null): T = value.also{
    if (above != null && value <= above) {
        throw InvalidParameterException("$name must be greater than $above")
    }
}

