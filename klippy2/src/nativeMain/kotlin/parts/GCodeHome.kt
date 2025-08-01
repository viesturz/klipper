package parts

import GCodeCommand
import GCodeContext
import GCodeHandler
import MachineBuilder
import PartLifecycle
import parts.motionplanner.MotionPlanner

fun MachineBuilder.GCodeHome(
    motion: MotionPlanner,
    homeAxes: String = "XYZ",
    homeScript: GCodeHandler? = null,
) = GCodeHomeImpl(
    "GCodeHome",
    motion,
    homeAxes,
    homeScript,
    this).also { addPart(it) }

interface GCodeHome {
    val homeAxes: String
    suspend fun home(axes: String)
    suspend fun homeAll()
}

class GCodeHomeImpl(override val name: String,
                    val planner: MotionPlanner,
                    override val homeAxes: String,
                    val script: GCodeHandler?,
                    configure: MachineBuilder
): PartLifecycle, GCodeHome {
    var inScript = false

    init {
        configure.registerCommand("G28") { cmd -> cmdHome(this, cmd) }
    }

    suspend fun cmdHome(context: GCodeContext, cmd: GCodeCommand) {
        if (script != null && !inScript) {
            inScript = true
            script(context, cmd)
            inScript = false
        } else {
            home(buildString { cmd.params.keys.forEach { append(it.uppercase()) } } )
        }
    }

    override suspend fun homeAll() = home(homeAxes)
    override suspend fun home(axes: String) {
        val realAxes = if (axes.isEmpty()) homeAxes else axes.uppercase()
        planner.home(realAxes)
    }
}