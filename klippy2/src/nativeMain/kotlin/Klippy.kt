import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import logging.LogFormatter
import logging.LogWriter
import machine.ConfigurationException
import machine.Machine
import machine.InvalidGcodeException
import machine.impl.MachineImpl

suspend fun gcodeFromCommandline(machine: MachineImpl) {
    val queue = machine.queueManager.newQueue()
    val runner = machine.gCode.runner(queue, machine) { msg -> println(msg) }
    while (machine.state.value != Machine.State.SHUTDOWN) {
        print(": ")
        val cmd = readln()
        try {
            runner.gcode(cmd)
        } catch (e: InvalidGcodeException) {
            println("  Invalid gcode: ${e.message}")
        }
        println()
    }
}

@OptIn(DelicateCoroutinesApi::class)
fun main(args: Array<String>) = runBlocking {
    println("Klippy 2!")

    val logWriter = LogWriter("klippy.log", GlobalScope)
    KotlinLoggingConfiguration.logLevel = Level.INFO
    KotlinLoggingConfiguration.formatter = LogFormatter()
    KotlinLoggingConfiguration.appender = logWriter

    try {
        val machine = MachineImpl()
        println("Setting up")
        machine.start()
        // Wait until running
        machine.state.first { it == Machine.State.RUNNING }
        println("Machine running")
//        gcodeFromCommandline(machine)
//
//            gcode.run("SET_FAN_SPEED FAN=fan1 SPEED=0.3")
//            gcode.run("PID_CALIBRATE HEATER='extruder' TARGET=150")
//            gcode.run("SET_FAN_SPEED FAN=fan0 SPEED=0.5")
//            gcode.run("SET_HEATER_TEMPERATURE HEATER='extruder' TARGET=150")
//            gcode.run("TEMPERATURE_WAIT SENSOR='extruder' MINIMUM=150")

        // Wait until shutdown.
        machine.state.first { it == Machine.State.SHUTDOWN }
        println("Shutdown detected, reason ${machine.shutdownReason}")
    } catch (e: Exception) {
        var isUnknown = false
        when (e) {
            is ConfigurationException -> println("Configuration error: ${e.message}")
            else -> isUnknown = true
        }
        runBlocking { logWriter.close() }
        if (isUnknown) throw e
    }
}