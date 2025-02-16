package parts

import machine.CommandQueue
import MachineBuilder
import machine.GCodeCommand
import PartLifecycle
import machine.waitUntil
import parts.kinematics.MotionPlanner
import parts.kinematics.KinMove

fun MachineBuilder.GCodeMove(
    motion: MotionPlanner,
    /** The axis that are subject to speed. EG, 'xyz' */
    positionalAxis: String = "XYZ",
    extrudeAxis: String = "E",
) = GCodeMoveImpl(
    "GCodeMove",
    motion,
    positionalAxis,
    extrudeAxis,
    this).also { addPart(it) }

interface GCodeMove {
    var speedFactor: Double
    var extrudeFactor: Double
    /** Which axis does determine the toolhead position.
     * If any position axis is moved, the movement speed applies to them.
     * Otherwise the speed applies to the first axis specified.*/
    var positionalAxis: String
    /* If positional axis movement should be relative. */
    var relativePosition: Boolean
    /** Which axis relative extrude applies to */
    var extrudeAxis: String
    /* If extrude axis movement should be relative. */
    var relativeExtrude: Boolean
    /** Memorized speed of previous move, will be applied to next moves if not specified. */
    var speed: Double
    var inches: Boolean
    /* Gcode offset in mm */
    val offset: Map<Char, Double>

    /** Perform a gcode move, same as executing it through a G0 or G1 command. */
    fun move(queue: CommandQueue, axis: String, position: DoubleArray, speed: Double? = null)
}

class GCodeMoveImpl(override val name: String,
                    val planner: MotionPlanner,
                    override var positionalAxis: String,
                    override var extrudeAxis: String,
                    configure: MachineBuilder
): PartLifecycle, GCodeMove {
    override var speedFactor = 1.0
    override var extrudeFactor = 1.0
    override val offset = mutableMapOf<Char, Double>()
    override var relativePosition = true
    override var relativeExtrude = true
    override var speed = 1.0
    override var inches = false

    init {
        configure.registerCommand("G0") { cmd -> gcodeMove(cmd) }
        configure.registerCommand("G1") { cmd -> gcodeMove(cmd) }
        configure.registerCommand("G20") { cmd -> inches = true }
        configure.registerCommand("G21") { cmd -> inches = false }
        configure.registerCommand("G82") { cmd -> setRelativeExtrude(false) }
        configure.registerCommand("G83") { cmd -> setRelativeExtrude(true) }
        configure.registerCommand("G91") { cmd -> relativePosition = false }
        configure.registerCommand("G92") { cmd -> relativePosition = true }
        configure.registerCommand("M114") { cmd -> TODO("get current position") }
        configure.registerCommand("M204") { }
        configure.registerCommand("M220") { cmd -> speedFactor = cmd.getDouble("S", 100.0)*0.01 }
        configure.registerCommand("M221") { cmd -> extrudeFactor = cmd.getDouble("S", 100.0)*0.01 }
        configure.registerCommand("M400") { cmd -> waitForMoves(cmd) }
        configure.registerCommand("SET_GCODE_OFFSET") { cmd -> gcodeSetOffset(cmd) }
        configure.registerCommand("SET_KINEMATIC_POSITION") { cmd -> gcodeSetPosition(cmd) }
        // TODO: SAVE_GCODE_STATE, RESTORE_GCODE_STATE
        // TODO: GET_POSITION
    }

    private fun setRelativeExtrude(value: Boolean) {
        relativeExtrude = value
        if (extrudeFactor != 1.0) {
            TODO("Need to remap current extrude value")
        }
    }

    private fun gcodeSetOffset(cmd: GCodeCommand) {
        for (i in cmd.params) {
            val ax = i.key[0].uppercaseChar()
            var pos = cmd.getDouble(i.key)
            if (inches) pos *= 25.4
            offset[ax] = pos
        }
    }

    private fun gcodeSetPosition(cmd: GCodeCommand) {
        var axisCount = cmd.params.size
        val axees = ArrayList<Char>(axisCount)
        val position = cmd.params.map { entry ->
            axees.add(validateAxis(entry.key))
            cmd.getDouble(entry.key)
        }
        planner.initializePosition(axees.joinToString(""), position)
    }

    fun gcodeMove(cmd: GCodeCommand) {
        var speed: Double? = null
        var axisCount = cmd.params.size
        if (cmd.params.containsKey("F")) {
            speed = cmd.getDouble("F", speed)
            axisCount --
        }
        val axis = CharArray(axisCount)
        val position = DoubleArray(axisCount)
        var index = 0
        for (x in cmd.params) {
            if (x.key == "F") continue
            val pos = cmd.getDouble(x.key)
            val ax = validateAxis(x.key)
            axis[index] = ax
            position[index] = pos
            index++
        }
        move(cmd.queue, axis.toString(), position, speed)
    }

    suspend fun waitForMoves(cmd: GCodeCommand) {
        waitUntil(cmd.queue.flush())
    }

    override fun move(queue: CommandQueue, axis: String, position: DoubleArray, speed: Double?) {
        require(axis.length == position.size){"Axis and position array must be same length"}
        var moveSpeed = speed ?: this.speed
        this.speed = moveSpeed
        moveSpeed *= speedFactor
        if (axis.isEmpty()) return

        val primaryAxis = StringBuilder()
        val primaryPosition = ArrayList<Double>(position.size)
        val moves = ArrayList<KinMove>()

        for (i in position.indices) {
            val a = axis[i]
            val p = applyTransform(a, position[i])
            if (positionalAxis.contains(a)) {
                primaryAxis.append(a)
                primaryPosition.add(p)
            } else {
                moves.add(KinMove(a.toString(), listOf(p), speed = null))
            }
        }
        if (primaryAxis.isNotEmpty()) {
            // Apply cached and current speed to primary axis
            moves.add(KinMove(primaryAxis.toString(), primaryPosition, moveSpeed))
        } else if (speed != null) {
            // Apply speed override to all other moves
            moves.forEach { it.speed = speed }
        }
        planner.move(queue, *moves.toTypedArray())
    }

    private fun validateAxis(key: String): Char {
        if (key.length != 1) {
            throw IllegalArgumentException("Invalid movement axis: ${key}")
        }
        val ax = key[0]
        if (!planner.axis.contains(ax)) {
            throw IllegalArgumentException("Movement axis ${key} not registered")
        }
        return ax
    }

    fun applyTransform(axis: Char, position: Double): Double {
        var p = position
        if (inches) {
            p *= 25.4
        }
        if (extrudeAxis.contains(axis)) {
            p *= extrudeFactor
            if (relativeExtrude) {
                p += planner.axisPosition(axis)
            }
        }
        if (relativePosition && positionalAxis.contains(axis)) {
            p += planner.axisPosition(axis)
        }
        offset[axis]?.let { p += it }
        return p
    }
}