package parts.drivers

import config.UartPins
import machine.MachineBuilder
import machine.impl.PartLifecycle

fun  MachineBuilder.TMC2209(
    name: String,
    pins: UartPins,
    microsteps: Int,
    runCurrent: Double,
    idleCurrent: Double = runCurrent,
    stealthchopTreshold: Int = 999999999,
): StepperDriver = TMC2209Impl(name, pins, microsteps, runCurrent, idleCurrent,stealthchopTreshold, this).also { addPart(it) }

interface StepperDriver {
    val microsteps: Int
    val runCurrent: Double
    val idleCurrent: Double
}

class TMC2209Impl(
    override val name: String,
    pinsConfig: UartPins,
    override val microsteps: Int,
    override val runCurrent: Double,
    override val idleCurrent: Double,
    val stealthchopTreshold: Int,
    machineBuilder: MachineBuilder
) : PartLifecycle, StepperDriver {
    //TODO: stuff

    override fun status(): Map<String, Any> =mapOf()
}
