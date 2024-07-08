import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import io.github.oshai.kotlinlogging.isLoggingEnabled
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logging.LogFormatter
import machine.MachineState
import machine.impl.MachineImpl
import kotlin.time.Duration.Companion.seconds

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
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
        machine.state.first { it == MachineState.RUNNING }

        machine.runGcode("SET_FAN_SPEED FAN=fan0 SPEED=1.0")
        delay(2.seconds)
        machine.runGcode("SET_FAN_SPEED FAN=fan1 SPEED=1.0")
        machine.runGcode("SET_FAN_SPEED FAN=fan0 SPEED=0")
        delay(5.seconds)
        machine.runGcode("SET_FAN_SPEED FAN=fan1 SPEED=0.5")
        machine.runGcode("SET_FAN_SPEED FAN=fan0 SPEED=0.5")

        // Wait until shutdown.
        machine.state.first { it == MachineState.SHUTDOWN }
        println("Shutdown detected, reason ${machine.shutdownReason}")
        break
    }
}