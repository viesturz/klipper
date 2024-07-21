

/** Machine time in seconds. Counted from primary MCU boot time. */
typealias MachineTime = Double
typealias MachineDuration = Double
typealias Voltage = Double
typealias Resistance = Double

private const val KTOC = -273.15

/** Temperatures are confusing, let's have a class.  */
value class Temperature(val celsius: Double) {
    init {
        require(kelvins >=0.0) {"Temperature below absolute zero"}
    }
    val kelvins: Double
        get() = celsius - KTOC
}
val Double.celsius
    get() = Temperature(this)
val Int.celsius
    get() = Temperature(this.toDouble())
val Double.kelvins
    get() = Temperature(this + KTOC)
val Int.kelvins
    get() = Temperature(this.toDouble() + KTOC)

// Just a decoration
val Int.ohms
    get() = this.toDouble()
val Double.ohms
    get() = this