package parts

import MachineTime
import Temperature
import celsius
import machine.CommandQueue
import machine.MachineBuilder
import machine.MachinePart
import machine.MachineRuntime
import machine.addLongRunningCommand
import machine.impl.GcodeParams
import machine.impl.PartLifecycle

fun MachineBuilder.PidCalibrate(
    name: String): PidCalibrate = PidCalibrateImpl(name, this).also { addPart(it) }

interface PidCalibrate: MachinePart {
    suspend fun calibrate(heater: Heater, target: Temperature, tolerance: Double = 0.02): config.PID
}

private class PidCalibrateImpl(override val name: String, setup: MachineBuilder) : PidCalibrate, PartLifecycle {
    private lateinit var runtime: MachineRuntime

    init {
        setup.registerCommand("PID_CALIBRATE", this::pidCalibrateGcode)
    }
    override suspend fun onStart(runtime: MachineRuntime) {
        this.runtime = runtime
    }

    private fun pidCalibrateGcode(queue: CommandQueue, params: GcodeParams) {
        val target = params.getCelsius("TARGET")
        val heater = params.getPartByName<Heater>("HEATER")
        val tolerance = params.getDouble("TOLERANCE", 0.02)
        queue.addLongRunningCommand(this) {
            val pid = calibrate(heater, target, tolerance)
            heater.setControl(pid)
        }
    }

    override suspend fun calibrate(heater: Heater, target: Temperature, tolerance: Double): config.PID {
        val calibrator = CalibrateControl()
        val prevControl = heater.setControl(calibrator)
        heater.setTarget(target)
        heater.waitForStable()
        heater.setTarget(0.celsius)
        val pid = calibrator.calculateResult()
        heater.setControl(prevControl)
        return pid
    }
}

private class CalibrateControl: TemperatureControl {
    override fun update(
        time: MachineTime,
        currentTemp: Temperature,
        currentPower: Double,
        targetTemp: Temperature
    ): Double {
        TODO("Not yet implemented")
    }

    override fun reset() {
    }
    override fun isStable(): Boolean {
        return false
    }
    fun calculateResult(): config.PID {
        return config.PID(kP = 1.0, kI = 1.0, kD = 1.0)
    }
}
