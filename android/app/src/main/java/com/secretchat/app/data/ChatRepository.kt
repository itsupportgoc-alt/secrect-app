package com.secretchat.app.data

import android.content.Context
import com.secretchat.app.outbox.Outbox
import com.secretchat.app.outbox.OutboxFactory
import com.secretchat.app.outbox.PendingMessage
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

enum class SendOutcome { SENT, QUEUED }

class ChatRepository(
    private val client: SupabaseClient,
    context: Context,
) {
    private val outbox: Outbox = OutboxFactory.create(context)

    suspend fun conversations(): List<ConversationSummary> =
        client.postgrest.rpc("list_conversations").decodeList()

    suspend fun startDm(username: String): String =
        client.postgrest.rpc(
            function = "start_dm",
            parameters = buildJsonObject { put("p_username", username) },
        ).decodeAs()

    suspend fun pending(conversationId: String? = null): List<PendingMessage> =
        withContext(Dispatchers.IO) {
            outbox.pending().filter { conversationId == null || it.conversationId == conversationId }
        }

    /**
     * Queues the message in the Rust outbox first, then tries to deliver it.
     * A failed delivery stays on disk and is retried with backoff, which makes
     * sends idempotent because the same client id is replayed.
     */
    suspend fun sendMessage(conversationId: String, body: String): SendOutcome {
        val trimmed = body.trim()
        require(trimmed.isNotEmpty()) { "Message is empty" }

        val clientId = withContext(Dispatchers.IO) { outbox.enqueue(conversationId, trimmed) }
        return try {
            deliver(conversationId, trimmed, clientId)
            withContext(Dispatchers.IO) { outbox.markSent(clientId) }
            SendOutcome.SENT
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            withContext(Dispatchers.IO) { outbox.markFailed(clientId) }
            SendOutcome.QUEUED
        }
    }

    /** Delivers every queued message whose backoff has elapsed. Returns send count. */
    suspend fun flushOutbox(): Int = withContext(Dispatchers.IO) {
        var sent = 0
        for (message in outbox.due(System.currentTimeMillis())) {
            try {
                deliver(message.conversationId, message.body, message.clientId)
                outbox.markSent(message.clientId)
                sent++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                outbox.markFailed(message.clientId, System.currentTimeMillis())
            }
        }
        sent
    }

    fun messages(conversationId: String): Flow<List<Message>> = channelFlow {
        val loaded = runCatching { fetchMessages(conversationId) }
            .getOrDefault(emptyList())
            .toMutableList()
        send(loaded.toList())

        val channel = client.channel("messages:$conversationId")
        val inserts = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "messages"
            filter("conversation_id", FilterOperator.EQ, conversationId)
        }
        val subscribed = runCatching { channel.subscribe(blockUntilSubscribed = true) }.isSuccess

        val job = launch {
            inserts.collect { action ->
                val message = runCatching { action.decodeRecord<Message>() }.getOrNull()
                    ?: return@collect
                if (loaded.none { it.id == message.id }) {
                    loaded.add(message)
                    loaded.sortBy { it.createdAt }
                    send(loaded.toList())
                }
            }
        }

        try {
            awaitClose { job.cancel() }
        } finally {
            job.cancel()
            if (subscribed) {
                runCatching { client.realtime.removeChannel(channel) }
            }
        }
    }

    suspend fun registerPushToken(token: String, userId: String) {
        client.postgrest["device_tokens"].upsert(
            buildJsonObject {
                put("token", token)
                put("user_id", userId)
                put("platform", "android")
                put("updated_at", Instant.now().toString())
            },
        ) {
            onConflict = "token"
        }
    }

    fun close() {
        outbox.close()
    }

    private suspend fun deliver(conversationId: String, body: String, clientId: String) {
        client.postgrest.rpc(
            function = "send_message",
            parameters = buildJsonObject {
                put("p_conversation_id", conversationId)
                put("p_body", body)
                put("p_client_id", clientId)
            },
        ).decodeAs<Message>()
    }

    private suspend fun fetchMessages(conversationId: String): List<Message> =
        client.postgrest["messages"].select {
            filter { eq("conversation_id", conversationId) }
            order("created_at", Order.ASCENDING)
            limit(200)
        }.decodeList()
}
