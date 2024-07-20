import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logging.LogFormatter
import machine.DelayCommand
import machine.Machine
import machine.impl.MachineImpl

@OptIn(DelicateCoroutinesApi::class)
fun main(args: Array<String>) = runBlocking {
    println("Klippy 2!")

    KotlinLoggingConfiguration.logLevel = Level.INFO
    KotlinLoggingConfiguration.formatter = LogFormatter()

    val config = config.example.machine
    println("Config = $config")

    while (true) {
        val machine = MachineImpl(config)
        println("Machine setup ${machine.status}")
        GlobalScope.launch(Dispatchers.IO) { machine.run() }
        // Wait until running
        machine.state.first { it == Machine.State.RUNNING }

        val queue = machine.queueManager.newQueue()
        val gcode = machine.gCode.runner(queue)

        repeat(10) {
            gcode.run("SET_FAN_SPEED FAN=fan1 SPEED=0")
            gcode.run("SET_FAN_SPEED FAN=fan0 SPEED=1.0")
            queue.add(DelayCommand(2.0))
            gcode.run("SET_FAN_SPEED FAN=fan1 SPEED=1.0")
            gcode.run("SET_FAN_SPEED FAN=fan0 SPEED=0")
            queue.add(DelayCommand(2.0))
        }
        gcode.run("SET_FAN_SPEED FAN=fan1 SPEED=0.5")
        gcode.run("SET_FAN_SPEED FAN=fan0 SPEED=0.5")

        // Wait until shutdown.
        machine.state.first { it == Machine.State.SHUTDOWN }
        println("Shutdown detected, reason ${machine.shutdownReason}")
        break
    }
}