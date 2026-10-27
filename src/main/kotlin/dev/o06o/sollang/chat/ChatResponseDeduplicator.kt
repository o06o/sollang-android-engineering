package dev.o06o.sollang.chat

data class ChatResponse(
    val body: String,
    val registeredAt: String?,
)

/**
 * The server contract has no request correlation id. A timestamp plus body is therefore used only
 * when the timestamp exists; messages without one are delivered rather than risking data loss.
 */
class ChatResponseDeduplicator(
    private val maxEntries: Int = 128,
) {
    private val seen = LinkedHashSet<String>()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    fun shouldDeliver(response: ChatResponse): Boolean {
        val registeredAt = response.registeredAt ?: return true
        val fingerprint = "$registeredAt\u0000${response.body}"
        if (!seen.add(fingerprint)) return false

        if (seen.size > maxEntries) {
            seen.remove(seen.first())
        }
        return true
    }
}

