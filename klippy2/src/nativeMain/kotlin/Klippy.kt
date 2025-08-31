import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import logging.LogFormatter
import logging.LogWriter
import machine.ConfigurationException
import machine.InvalidGcodeException
import machine.NeedsRestartException
import machine.impl.MachineImpl
import kotlin.time.Duration.Companion.seconds

suspend fun gcodeFromCommandline(machine: Machine) {
    while (machine.state.value != Machine.State.SHUTDOWN) {
        print(": ")
        val cmd = readln()
        try {
            machine.gcode(cmd) { respones ->  println(respones) }
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

    var running = true
    while (running) {
        try {
            val machine: Machine = MachineImpl()
            println("Setting up")
            machine.start()
            // Wait until running
            machine.state.first { it == Machine.State.RUNNING }
            println("Machine running")
            machine.gcode("SET_FAN_SPEED FAN=partFan SPEED=0.5")
            println("Initial commands done")
//            gcodeFromCommandline(machine)
//            gcode.run("PID_CALIBRATE HEATER='extruder' TARGET=150")
//            gcode.run("SET_FAN_SPEED FAN=fan0 SPEED=0.5")
//            gcode.run("SET_HEATER_TEMPERATURE HEATER='extruder' TARGET=150")
//            gcode.run("TEMPERATURE_WAIT SENSOR='extruder' MINIMUM=150")

            // Wait until shutdown.
            machine.state.first { it == Machine.State.SHUTDOWN }
            println("Shutdown detected, reason ${machine.shutdownReason}")
            running = false
        }
        catch (e: Exception) {
            when (e) {
                is NeedsRestartException -> {
                    println("Needs restart: ${e.message}")
                    delay(2.seconds)
                    running = true
                }
                is ConfigurationException -> {
                    println("Configuration error: ${e.message}")
                    running = false
                }
                else -> {
                    running = false
                }
            }
            if (!running) {
                println("Exiting, $e")
                runBlocking { logWriter.close() }
                throw e
            }
        }
    }
    logWriter.close()
}