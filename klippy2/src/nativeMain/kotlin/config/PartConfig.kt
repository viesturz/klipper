package config

sealed class PartConfig(
    val name: String
)

class Fan(
    name: String,
    val pin: PwmPin,
    val offBelow: Float = 0f,
    val kickStartTime: Float = 0.1f,
    val shutdownSpeed: Float = 0f
) : PartConfig(name) {
    init {
        require(offBelow in 0f..1f)
        require(kickStartTime in 0f..10f)
        require(shutdownSpeed in 0f..1f)
    }
}