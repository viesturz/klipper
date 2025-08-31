package parts

import GCodeCommand
import MachineBuilder
import PartLifecycle
import celsius
import getPartsImplementing
import parts.kinematics.LinearStepper

fun MachineBuilder.GCodePrinter(
    heater: Heater?,
    partFan: Fan?,
    bedHeater: Heater?
) = GCodePrinterImpl("GCodePrinter", heater = heater, partFan = partFan, bedHeater = bedHeater, this).also { addPart(it) }

class GCodePrinterImpl(
    override val name: String,
    val heater: Heater?,
    val partFan: Fan?,
    val bedHeater: Heater?,
    configure: MachineBuilder
): PartLifecycle {
    init {
        configure.registerCommand("M112") { cmd -> emergencyStop(cmd) }
        configure.registerCommand("M18") { cmd -> disableMotors(cmd) }
        configure.registerCommand("M84") { cmd -> disableMotors(cmd) }
        configure.registerCommand("TURN_OFF_HEATERS") { cmd -> turnOffHeaters(cmd) }

        if (heater != null) {
            configure.registerCommand("M105") { cmd -> getExtruderTemperature(cmd) }
            configure.registerCommand("M104") { cmd -> setExtruderTemperature(cmd) }
            configure.registerCommand("M109") { cmd -> setExtruderTemperatureAndWait(cmd) }
        }
        if (bedHeater != null) {
            configure.registerCommand("M140") { cmd -> setBedTemperature(cmd) }
            configure.registerCommand("M190") { cmd -> setBedTemperatureAndWait(cmd) }
        }
        if (partFan != null) {
            configure.registerCommand("M106") { cmd -> setFanSpeed(cmd, cmd.getInt("S") / 255.0) }
            configure.registerCommand("M107") { cmd -> setFanSpeed(cmd, 0.0) }
        }
    }

    private fun turnOffHeaters(cmd: GCodeCommand) {
        for (m in cmd.runtime.getPartsImplementing<Heater>()) {
            m.setTarget(cmd.queue, 0.celsius)
        }
    }

    private fun disableMotors(cmd: GCodeCommand) {
        cmd.queue.addLongRunning {
            val time = cmd.runtime.reactor.now
            for (m in cmd.runtime.getPartsImplementing<LinearStepper>()) {
                m.setPowered(time, value = false)
            }
        }
        // TODO: let the motion planner know.
    }

    private fun emergencyStop(cmd: GCodeCommand) {
        cmd.runtime.shutdown("M112 emergency stop", true)
    }

    private fun getExtruderTemperature(cmd: GCodeCommand) {
        val heaterTemp = heater?.sensor?.value?.value?.celsius ?: 0.0
        val heaterTarget = heater?.target?.celsius ?: 0.0
        cmd.respond("ok $heaterTemp / $heaterTarget")
    }

    private fun setExtruderTemperature(cmd: GCodeCommand) {
        val temp = cmd.getDouble("S").celsius
        heater?.setTarget(cmd.queue, temp)
    }

    private suspend fun setExtruderTemperatureAndWait(cmd: GCodeCommand) {
        setExtruderTemperature(cmd)
        heater?.waitForStable()
    }

    private fun setBedTemperature(cmd: GCodeCommand) {
        val temp = cmd.getDouble("S").celsius
        bedHeater?.setTarget(cmd.queue, temp)
    }

    private suspend fun setBedTemperatureAndWait(cmd: GCodeCommand) {
        setBedTemperature(cmd)
        bedHeater?.waitForStable()
    }

    private fun setFanSpeed(cmd: GCodeCommand, sp: Double) {
        partFan?.queueSpeed(cmd.queue, sp)
    }

}