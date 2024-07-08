import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import mcu.impl.CommandBuilder
import mcu.impl.CommandParser
import mcu.impl.CommandQueue
import mcu.impl.McuConnection
import mcu.impl.ResponseIdentify
import mcu.impl.connectPipe
import mcu.impl.connectSerial

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) = runBlocking {
    println("Klippy 2!")
    val path = "/dev/serial/by-id/usb-Klipper_stm32f103xe_31FFD7053030473538690543-if00"
    println("Connecting")
    val commands = CommandBuilder(CommandParser())
    val serial = McuConnection(connectSerial(path))
    println("Serial started, running identify")
    serial.identify()
    println("Identify done, starting config")
    val queue = CommandQueue(serial)

    println("Queue setup,")
    serial.disconnect()
    println("Closing")
//    val config = config.example.machine
//    println("Config = ${ config }")
//    val machine = MachineImpl(config)
//    machine.setup()
//    println("Machine setup ${ machine.status }")
//    launch(newSingleThreadContext("machine")) { machine.run() }
//    machine.runGcode("SET_FAN_SPEED SPEED=0.7")
//    machine.shutdown("main")
//    println("Machine done ${ machine.status }")
}