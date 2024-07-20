package mcu.impl

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import okio.buffer

/** Configuration retrieved from the MCU firmware. */
@Serializable
data class FirmwareConfig @OptIn(ExperimentalSerializationApi::class) constructor(
    val version: String,
    @kotlinx.serialization.ExperimentalSerializationApi @JsonNames("build_versions")
    val buildVersions: String,
    val commands: Map<String, UInt>,
    val responses: Map<String, UInt>,
    val config: Map<String, JsonPrimitive>,
    val enumerations: Map<String, Map<String, JsonElement>>,
) {
    private val enumerationsValueToId = buildEnumerationsLookup()
    private val enumerationsIdToValue = enumerationsValueToId.mapValues { e ->
        buildMap { e.value.entries.forEach { entry -> put(entry.value, entry.key) } }
    }
    val mcuName: String
        get() = configString("MCU")
    val clockFreq: Long
        get() = configLong("CLOCK_FREQ")
    val adcMax: Float
        get() = configLong("ADC_MAX").toFloat()

    fun configLong(name: String, default: Long? = null): Long =
        config[name]?.long ?: default ?: throw RuntimeException("Missing MCU config $name")
    fun configString(name: String, default: String? = null): String =
        config[name]?.content ?: default ?: throw RuntimeException("Missing MCU config $name")
    fun durationToTicks(duration: Float): McuClock32  = (clockFreq * duration).toUInt()

    fun enumerationIdToValue(name: String): Map<Int, String> = enumerationsIdToValue.getValue(name)
    fun enumerationValueToId(name: String): Map<String, Int> = enumerationsValueToId.getValue(name)

    fun buildEnumerationsLookup(): Map<String, Map<String, Int>> = enumerations.mapValues { e ->
        buildMap {
            e.value.entries.forEach { entry ->
                val json = entry.value
                when (json) {
                    is JsonPrimitive -> put(entry.key, json.int)
                    is JsonArray -> {
                        // Expand range enumerations
                        // PIN5 = [10, 2] -> PIN6 -> 10, PIN7 -> 11
                        var root = entry.key
                        val start_value = (json[0] as JsonPrimitive).int
                        val count = (json[1] as JsonPrimitive).int
                        var indexStart = 0
                        var digitPos = root.indexOfFirst { it.isDigit() }
                        if (digitPos != -1) {
                            indexStart = root.substring(digitPos).toInt()
                            root = root.substring(0..digitPos-1)
                        }
                        for (i in 0..count) {
                            put("$root${indexStart + i}", start_value + i)
                        }
                    }
                    else -> throw RuntimeException("Unexpected enum value $json")
                }
            }
        }
    }

    companion object {
        fun parse(compressedIdentify: ByteArray?): FirmwareConfig {
            if (compressedIdentify == null) return DEFAULT_IDENTIFY
            val input = okio.Buffer()
            val unzipped = okio.InflaterSource(input, okio.Inflater(nowrap = false))
            input.write(compressedIdentify).close()
            val result = unzipped.buffer().readByteArray().decodeToString()
            return Json.decodeFromString<FirmwareConfig>(result)
        }

        val DEFAULT_IDENTIFY = FirmwareConfig(
            version = "",
            buildVersions = "",
            commands = mapOf("identify offset=%u count=%c" to 1u),
            responses = mapOf("identify_response offset=%u data=%.*s" to 0u),
            config = mapOf(),
            enumerations = mapOf(),
        )
    }
}
