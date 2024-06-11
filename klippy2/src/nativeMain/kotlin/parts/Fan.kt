package parts

import config.PartConfig
import machine.impl.GcodeParams
import machine.impl.ReactorTime

class Fan(val config: config.Fan, val runtime: MachineRuntime): MachinePart {
    override val partConfig: PartConfig
        get() = config
    var speed = 0f

    init {
        println("Fan init")
        runtime.gcode.registerCommand("SET_FAN_SPEED", this::setSpeedGcode)
    }

    fun setSpeedGcode(params: GcodeParams) {
        val speed = params.getFloat("SPEED")
        setSpeed(speed)
    }
    fun setSpeed(value: Float) {
        require(value in 0f..1f)
        println("Fan set speed")
        speed = value
    }

    override fun status(time: ReactorTime): Map<String, Any> = mapOf("speed" to speed)
}