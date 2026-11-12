package com.secretchat.app.data

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class PendingImage(
    @SerialName("client_id") val clientId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("created_at_ms") val createdAtMs: Long,
    val attempts: Int = 0,
    @SerialName("next_attempt_at_ms") val nextAttemptAtMs: Long = 0,
) {
    /** Storage object name; the same for every retry, so uploads are idempotent. */
    val storagePath: String
        get() = "$conversationId/$clientId.jpg"
}

/**
 * Disk-backed queue for photos that have not been delivered yet. Each entry is
 * a compressed JPEG plus a small JSON file, kept in the app's private storage,
 * so queued photos survive the app being closed.
 */
class PendingImageStore(context: Context) {

    private val dir = File(context.filesDir, "pending-images").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun add(conversationId: String, jpeg: ByteArray): PendingImage {
        val item = PendingImage(
            clientId = UUID.randomUUID().toString(),
            conversationId = conversationId,
            createdAtMs = System.currentTimeMillis(),
            // The first attempt is made right away by the sender; keep the
            // background retry loop from uploading the same photo in parallel.
            nextAttemptAtMs = System.currentTimeMillis() + FIRST_RETRY_DELAY_MS,
        )
        imageFile(item.clientId).writeBytes(jpeg)
        write(item)
        return item
    }

    fun imageFile(clientId: String): File = File(dir, "$clientId.jpg")

    @Synchronized
    fun all(): List<PendingImage> =
        dir.listFiles { file -> file.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { json.decodeFromString<PendingImage>(file.readText()) }.getOrNull()
            }
            .filter { imageFile(it.clientId).exists() }
            .sortedBy { it.createdAtMs }

    fun due(nowMs: Long): List<PendingImage> = all().filter { it.nextAttemptAtMs <= nowMs }

    @Synchronized
    fun remove(clientId: String) {
        File(dir, "$clientId.json").delete()
        imageFile(clientId).delete()
    }

    @Synchronized
    fun markFailed(clientId: String, nowMs: Long) {
        val file = File(dir, "$clientId.json")
        if (!file.exists()) return
        val item = runCatching { json.decodeFromString<PendingImage>(file.readText()) }.getOrNull() ?: return
        val attempts = item.attempts + 1
        val delay = minOf(1_000L shl (attempts - 1).coerceIn(0, 6), 60_000L)
        write(item.copy(attempts = attempts, nextAttemptAtMs = nowMs + delay))
    }

    private fun write(item: PendingImage) {
        File(dir, "${item.clientId}.json").writeText(json.encodeToString(item))
    }

    private companion object {
        const val FIRST_RETRY_DELAY_MS = 30_000L
    }
}
