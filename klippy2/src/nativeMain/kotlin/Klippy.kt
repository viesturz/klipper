import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import logging.LogFormatter
import machine.Machine
import machine.impl.MachineImpl

fun main(args: Array<String>) = runBlocking {
    println("Klippy 2!")

    KotlinLoggingConfiguration.logLevel = Level.INFO
    KotlinLoggingConfiguration.formatter = LogFormatter()

    while (true) {
        val machine = MachineImpl()
        println("Machine setup ${machine.status}")
        machine.start()
        // Wait until running
        machine.state.first { it == Machine.State.RUNNING }

        machine.reactor.launch {
            val queue = machine.queueManager.newQueue()
            val gcode = machine.gCode.runner(queue)

            gcode.run("SET_FAN_SPEED FAN=fan1 SPEED=0.5")
            gcode.run("SET_FAN_SPEED FAN=fan0 SPEED=0.5")
            gcode.run("SET_HEATER_TEMPERATURE HEATER='extruder' TARGET=100")
            gcode.run("TEMPERATURE_WAIT SENSOR='extruder' MINIMUM=100")
            gcode.run("SHUTDOWN")
        }

        // Wait until shutdown.
        machine.state.first { it == Machine.State.SHUTDOWN }
        println("Shutdown detected, reason ${machine.shutdownReason}")
        break
    }
}