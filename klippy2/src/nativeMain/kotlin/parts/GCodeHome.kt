package parts

import GCodeCommand
import GCodeContext
import GCodeHandler
import MachineBuilder
import PartLifecycle
import parts.kinematics.MotionPlanner

fun MachineBuilder.GCodeHome(
    motion: MotionPlanner,
    homeAxis: String = "XYZ",
    homeScript: GCodeHandler? = null,
) = GCodeHomeImpl(
    "GCodeHome",
    motion,
    homeAxis,
    homeScript,
    this).also { addPart(it) }

interface GCodeHome {
    val homeAxis: String
    suspend fun home(axis: String)
    suspend fun homeAll()
}

class GCodeHomeImpl(override val name: String,
                    val planner: MotionPlanner,
                    override val homeAxis: String,
                    val script: GCodeHandler?,
                    configure: MachineBuilder
): PartLifecycle, GCodeHome {
var inScript = false

    init {
        configure.registerCommand("G28") { cmd -> cmdHome(this, cmd) }
    }

    suspend fun cmdHome(context: GCodeContext,cmd: GCodeCommand) {
        if (script != null && !inScript) {
            inScript = true
            script(context, cmd)
            inScript = false
        } else {
            home(buildString { cmd.params.keys.forEach { append(it.uppercase()) } } )
        }
    }

    override suspend fun homeAll() = home(homeAxis)
    override suspend fun home(axis: String) = planner.home(axis)
}