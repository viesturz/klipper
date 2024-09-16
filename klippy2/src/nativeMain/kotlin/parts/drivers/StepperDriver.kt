package parts.drivers

import MachineTime

interface StepperDriver {
    val microsteps: Int
    val stepBothEdges: Boolean
    val pulseDuration: Double

    val enabled: Boolean
    /** Configure motor's steps per mm to reference speed dependant driver thresholds.
     * To be called during configuration phase. */
    fun configureStepsPerMM(stepsPerMM: Double)
    suspend fun enable(time: MachineTime ,enabled: Boolean)
}
