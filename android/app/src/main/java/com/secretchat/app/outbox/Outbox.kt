package com.secretchat.app.outbox

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.Closeable
import java.util.UUID

@Serializable
data class PendingMessage(
    @SerialName("client_id") val clientId: String,
    @SerialName("conversation_id") val conversationId: String,
    val body: String,
    @SerialName("created_at_ms") val createdAtMs: Long,
    val attempts: Int = 0,
    @SerialName("next_attempt_at_ms") val nextAttemptAtMs: Long = 0,
)

interface Outbox : Closeable {
    fun enqueue(conversationId: String, body: String): String
    fun due(nowMs: Long = System.currentTimeMillis()): List<PendingMessage>
    fun pending(): List<PendingMessage>
    fun markSent(clientId: String): Boolean
    fun markFailed(clientId: String, nowMs: Long = System.currentTimeMillis())
    fun pendingCount(): Int
    override fun close() = Unit
}

/**
 * Used when the native library has not been built yet, so the app still runs
 * in a plain debug build. Like the Rust core it keeps messages only for the
 * lifetime of the process.
 */
class InMemoryOutbox : Outbox {
    private val messages = linkedMapOf<String, PendingMessage>()

    override fun enqueue(conversationId: String, body: String): String {
        require(conversationId.isNotBlank()) { "Conversation id is empty" }
        require(body.isNotBlank()) { "Message is empty" }
        val clientId = UUID.randomUUID().toString()
        messages[clientId] = PendingMessage(
            clientId = clientId,
            conversationId = conversationId,
            body = body.trim(),
            createdAtMs = System.currentTimeMillis(),
        )
        return clientId
    }

    override fun due(nowMs: Long): List<PendingMessage> =
        pending().filter { it.nextAttemptAtMs <= nowMs }

    override fun pending(): List<PendingMessage> = messages.values.sortedBy { it.createdAtMs }

    override fun markSent(clientId: String): Boolean = messages.remove(clientId) != null

    override fun markFailed(clientId: String, nowMs: Long) {
        val message = messages[clientId] ?: return
        val attempts = message.attempts + 1
        val delay = minOf(1_000L shl (attempts - 1).coerceIn(0, 6), 60_000L)
        messages[clientId] = message.copy(
            attempts = attempts,
            nextAttemptAtMs = nowMs + delay,
        )
    }

    override fun pendingCount(): Int = messages.size
}

object OutboxFactory {
    fun create(context: Context): Outbox =
        runCatching { RustOutbox(context) }.getOrElse { InMemoryOutbox() }
}
