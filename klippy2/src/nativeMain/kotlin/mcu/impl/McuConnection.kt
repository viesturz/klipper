package mcu.impl

import kotlin.reflect.KClass

class McuConnection(val io: McuIO) {
    val responseHandlers = HashMap<Pair<KClass<McuResponse>, ObjectId>, (message: McuResponse) -> Unit>()
    var parser = CommandParser()

    fun send(command: ByteArray) {

    }

    fun sendBatch(commands: List<ByteArray>) {

    }

    fun sendWithAck(command: ByteArray) {

    }

    suspend fun sendWithReply(command: ByteArray): ByteArray {
        return ByteArray(0)
    }

    fun <ResponseType: McuResponse> setResponseHandler(type :KClass<McuResponse>, id: ObjectId, handler: ((message: ResponseType) -> Unit)? ) {
        val key = Pair(type, id)
        when {
            handler == null -> responseHandlers.remove(key)
            responseHandlers.containsKey(key) -> throw IllegalArgumentException("Duplicate message handler for $key")
            else -> responseHandlers[key] = handler as ((message: McuResponse) -> Unit)
        }
    }

    private fun handleResponse(data: ParseBuffer) {
        val response = parser.decode(data)
        val key = Pair(response::class, response.id)
        val handler = responseHandlers[key] ?: return
        handler(response)
    }
}