package parts

import io.github.oshai.kotlinlogging.KotlinLogging
import machine.impl.GcodeParams
import machine.impl.MachineTime

class Fan(override val config: config.Fan, setup: MachineSetup): MachinePart<config.Fan> {
    val pin = setup.acquireMcu(config.pin.mcu).addPwmPin(config.pin)
    var runtime: MachineRuntime? = null
    val logger = KotlinLogging.logger("Fan ${config.name}")
    init {
        setup.registerMuxCommand("SET_FAN_SPEED", "FAN", config.name, this::setSpeedGcode)
    }

    override suspend fun onStart(runtime: MachineRuntime) {
        this.runtime = runtime
    }

    private fun setSpeedGcode(params: GcodeParams) {
        val speed = params.getFloat("SPEED")
        setSpeed(speed)
    }
    fun setSpeed(value: Float) {
        val time = runtime?.reactor?.now ?: return
        require(value in 0f..1f)
        logger.info { "Fan set speed $value" }
        pin.set(time + 0.3, value)
    }

    override fun status(time: MachineTime): Map<String, Any> = mapOf("speed" to pin.dutyCycle)
}
