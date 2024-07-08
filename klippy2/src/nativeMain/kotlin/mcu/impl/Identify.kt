package mcu.impl

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import okio.buffer

@Serializable
data class Identify @OptIn(ExperimentalSerializationApi::class) constructor(
    val version: String,
    @JsonNames("build_versions")
    val buildVersions: String,
    val commands: Map<String, UInt>,
    val responses: Map<String, UInt>,
    val config: Map<String, JsonElement>,
    val enumerations: Map<String, Map<String, JsonElement>>,
) {

    companion object {
        fun parse(compressedIdentify: ByteArray?): Identify {
            if ( compressedIdentify == null) return DEFAULT_IDENTIFY
            val input = okio.Buffer()
            val unzipped = okio.InflaterSource(input, okio.Inflater(nowrap = false))
            input.write(compressedIdentify).close()
            val result = unzipped.buffer().readByteArray().decodeToString()
            return Json.decodeFromString<Identify>(result)
        }

        val DEFAULT_IDENTIFY = Identify(
            version = "",
            buildVersions = "",
            commands = mapOf("identify offset=%u count=%c" to 1u),
            responses = mapOf("identify_response offset=%u data=%.*s" to 0u),
            config = mapOf(),
            enumerations = mapOf(),
        )
    }
}
