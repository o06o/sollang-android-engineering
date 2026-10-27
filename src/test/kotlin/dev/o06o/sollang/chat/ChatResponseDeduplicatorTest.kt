package dev.o06o.sollang.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatResponseDeduplicatorTest {
    @Test
    fun `same timestamp and body is delivered once`() {
        val deduplicator = ChatResponseDeduplicator()
        val response = ChatResponse(body = "answer", registeredAt = "2026-10-27T01:02:03")

        assertTrue(deduplicator.shouldDeliver(response))
        assertFalse(deduplicator.shouldDeliver(response))
    }

    @Test
    fun `same body with a different timestamp is a new response`() {
        val deduplicator = ChatResponseDeduplicator()

        assertTrue(deduplicator.shouldDeliver(ChatResponse("answer", "2026-10-27T01:02:03")))
        assertTrue(deduplicator.shouldDeliver(ChatResponse("answer", "2026-10-27T01:02:04")))
    }

    @Test
    fun `missing timestamp is delivered because safe deduplication is impossible`() {
        val deduplicator = ChatResponseDeduplicator()
        val response = ChatResponse(body = "answer", registeredAt = null)

        assertTrue(deduplicator.shouldDeliver(response))
        assertTrue(deduplicator.shouldDeliver(response))
    }

    @Test
    fun `old fingerprints are evicted from the bounded history`() {
        val deduplicator = ChatResponseDeduplicator(maxEntries = 2)
        val first = ChatResponse("one", "1")

        assertTrue(deduplicator.shouldDeliver(first))
        assertTrue(deduplicator.shouldDeliver(ChatResponse("two", "2")))
        assertTrue(deduplicator.shouldDeliver(ChatResponse("three", "3")))
        assertTrue(deduplicator.shouldDeliver(first))
    }
}

