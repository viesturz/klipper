package parts.drivers

interface StepperDriver {
    val microsteps: Int
    val runCurrent: Double
    val idleCurrent: Double
}
