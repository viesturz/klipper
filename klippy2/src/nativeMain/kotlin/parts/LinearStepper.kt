package parts

import config.StepperPins
import machine.CommandQueue
import machine.MachineBuilder
import machine.impl.PartLifecycle
import parts.drivers.StepperDriver

fun MachineBuilder.LinearStepper(
    name: String,
    pins: StepperPins,
    stepsPerRotation: Int = 200,
    rotationDistance: Double,
    gearRatio: Double = 1.0,
    driver: StepperDriver,
): LinearStepper = StepperImpl(
    name,
    pins,
    stepDistance = rotationDistance / stepsPerRotation / driver.microsteps * gearRatio,
    driver,
    this,
).also { addPart(it) }

interface LinearStepper {
    /** Commanded position */
    val position: Double
    val driver: StepperDriver
    fun initializePosition(queue: CommandQueue, pos: Double)
    fun move(queue: CommandQueue, pos: Double)
}

private class StepperImpl(
    override val name: String,
    pins: StepperPins,
    val stepDistance: Double,
    override val driver: StepperDriver,
    builder: MachineBuilder,
) : LinearStepper, PartLifecycle {
    var _position = 0.0
    val motor = builder.setupMcu(pins.mcu).addStepperMotor(pins)
    override val position: Double
        get() = _position

    override fun initializePosition(queue: CommandQueue, pos: Double) {
        _position = pos
    }

    override fun move(queue: CommandQueue, pos: Double) {
        TODO("Not yet implemented")
    }

}