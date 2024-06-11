import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import machine.impl.MachineImpl

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) = runBlocking {
    println("Klippy 2!")
    val config = config.example.machine
    println("Config = ${ config }")
    val machine = MachineImpl(config)
    machine.setup()
    println("Machine setup ${ machine.status }")
    launch(newSingleThreadContext("machine")) { machine.run() }
    machine.runGcode("SET_FAN_SPEED SPEED=0.7")
    machine.shutdown("main")
    println("Machine done ${ machine.status }")
}