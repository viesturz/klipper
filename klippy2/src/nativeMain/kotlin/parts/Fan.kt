package parts

import io.github.oshai.kotlinlogging.KotlinLogging
import machine.CommandQueue
import machine.addBasicCommand
import machine.impl.GcodeParams
import MachineTime

class Fan(override val config: config.Fan, setup: MachineSetup): MachinePart<config.Fan> {
    val pin = setup.acquireMcu(config.pin.mcu).addPwmPin(config.pin)
    var runtime: MachineRuntime? = null
    val logger = KotlinLogging.logger("Fan ${config.name}")
    var dutyCycle = 0.0f

    init {
        setup.registerMuxCommand("SET_FAN_SPEED", "FAN", config.name, this::setSpeedGcode)
    }

    override suspend fun onStart(runtime: MachineRuntime) {
        this.runtime = runtime
    }

    private fun setSpeedGcode(queue: CommandQueue, params: GcodeParams) {
        val speed = params.getFloat("SPEED")
        setSpeed(queue, speed)
    }
    fun setSpeed(queue: CommandQueue, value: Float) {
        require(value in 0f..1f)
        logger.info { "Fan set speed $value" }
        dutyCycle = value
        queue.addBasicCommand(this) { time ->
            pin.set(time, value)
        }
    }

    override fun status(time: MachineTime): Map<String, Any> = mapOf("speed" to dutyCycle)
}
