package config

sealed class PartConfig(
    val name: String
)

typealias ActionBlock = suspend () -> Unit

class Fan(
    name: String,
    val pin: DigitalOutPin,
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

class Button(
    name: String,
    val pin: DigitalInPin,
    val onClicked: ActionBlock
) : PartConfig(name)