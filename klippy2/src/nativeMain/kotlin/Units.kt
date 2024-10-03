

/** Machine time in seconds. Counted from Host computer boot time. */
typealias MachineTime = Double
typealias MachineDuration = Double
typealias Voltage = Double
typealias Resistance = Double

private const val KTOC = -273.15

/** Temperatures are confusing, let's have a class.  */
value class Temperature(val celsius: Double): Comparable<Temperature> {
    override operator fun compareTo(other: Temperature) = this.celsius.compareTo(other.celsius)
    operator fun plus(v: Double) = Temperature(celsius + v)
    operator fun minus(v: Double) = Temperature(celsius - v)
    operator fun minus(v: Temperature) = celsius-v.celsius

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