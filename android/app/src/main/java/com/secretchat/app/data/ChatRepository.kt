package com.secretchat.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
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
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

enum class SendOutcome { SENT, QUEUED }

class ChatRepository(
    private val client: SupabaseClient,
    context: Context,
) {
    private val appContext = context.applicationContext
    private val outbox: Outbox = OutboxFactory.create(appContext)
    private val pendingImages = PendingImageStore(appContext)

    /** Decoded photos, keyed by storage path, capped at about 32 MB. */
    private val imageCache = object : LruCache<String, Bitmap>(IMAGE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

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

    suspend fun pendingImages(conversationId: String? = null): List<PendingImage> =
        withContext(Dispatchers.IO) {
            pendingImages.all().filter { conversationId == null || it.conversationId == conversationId }
        }

    fun pendingImageFile(clientId: String): File = pendingImages.imageFile(clientId)

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

    /**
     * Compresses the photo, saves it to the on-disk photo queue, then uploads it
     * and posts the message. On failure the photo stays queued and is retried
     * with the same id, so a retry never creates a second message or file.
     * Throws only when the picked file can't be read as an image.
     */
    suspend fun sendImage(conversationId: String, uri: Uri): SendOutcome {
        val jpeg = withContext(Dispatchers.Default) { ImageCompressor.compress(appContext, uri) }
        val item = withContext(Dispatchers.IO) { pendingImages.add(conversationId, jpeg) }
        return try {
            deliverImage(item, jpeg)
            withContext(Dispatchers.IO) { pendingImages.remove(item.clientId) }
            SendOutcome.SENT
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            withContext(Dispatchers.IO) { pendingImages.markFailed(item.clientId, System.currentTimeMillis()) }
            SendOutcome.QUEUED
        }
    }

    /** Delivers every queued message and photo whose backoff has elapsed. Returns send count. */
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
        for (image in pendingImages.due(System.currentTimeMillis())) {
            try {
                val jpeg = pendingImages.imageFile(image.clientId).readBytes()
                deliverImage(image, jpeg)
                pendingImages.remove(image.clientId)
                sent++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                pendingImages.markFailed(image.clientId, System.currentTimeMillis())
            }
        }
        sent
    }

    fun cachedImage(path: String): Bitmap? = imageCache.get(path)

    /** Downloads (with the user's session) and decodes a chat photo. Null if it can't be loaded. */
    suspend fun loadImage(path: String): Bitmap? {
        imageCache.get(path)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = client.storage.from(IMAGE_BUCKET).downloadAuthenticated(path)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()?.also { imageCache.put(path, it) }
        }
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

    private suspend fun deliverImage(item: PendingImage, jpeg: ByteArray) {
        val path = item.storagePath
        client.storage.from(IMAGE_BUCKET).upload(path, jpeg) {
            upsert = true
        }
        deliver(item.conversationId, "", item.clientId, imagePath = path)
        // The sender already has the photo, so show it without downloading it again.
        runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
            .getOrNull()
            ?.let { imageCache.put(path, it) }
    }

    private suspend fun deliver(
        conversationId: String,
        body: String,
        clientId: String,
        imagePath: String? = null,
    ) {
        client.postgrest.rpc(
            function = "send_message",
            parameters = buildJsonObject {
                put("p_conversation_id", conversationId)
                put("p_body", body)
                put("p_client_id", clientId)
                if (imagePath != null) put("p_image_path", imagePath)
            },
        ).decodeAs<Message>()
    }

    private suspend fun fetchMessages(conversationId: String): List<Message> =
        client.postgrest["messages"].select {
            filter { eq("conversation_id", conversationId) }
            order("created_at", Order.ASCENDING)
            limit(200)
        }.decodeList()

    private companion object {
        const val IMAGE_BUCKET = "chat-images"
        const val IMAGE_CACHE_BYTES = 32 * 1024 * 1024
    }
}
