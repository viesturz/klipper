package parts.drivers

interface StepperDriver {
    val microsteps: Int
    val runCurrent: Double
    val idleCurrent: Double
    val stepBothEdges: Boolean
    val pulseDuration: Double
}
