package com.secretchat.app.outbox

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * JNI bridge to `rust-core`. The native side persists the queue to
 * `filesDir/outbox.json` so queued messages survive process death.
 */
internal class RustOutbox(context: Context) : Outbox {

    private val json = Json { ignoreUnknownKeys = true }
    private var handle: Long = 0L
    private var closed = false

    init {
        loadLibrary()
        handle = nativeOpen(File(context.filesDir, "outbox.json").absolutePath)
        check(handle != 0L) { "Could not open the Rust outbox" }
    }

    override fun enqueue(conversationId: String, body: String): String =
        nativeEnqueue(handle, conversationId, body)
            ?: error("Could not enqueue the message")

    override fun due(nowMs: Long): List<PendingMessage> =
        json.decodeFromString<List<PendingMessage>>(nativeDue(handle, nowMs) ?: "[]")

    override fun pending(): List<PendingMessage> =
        json.decodeFromString<List<PendingMessage>>(nativePending(handle) ?: "[]")

    override fun markSent(clientId: String): Boolean = nativeMarkSent(handle, clientId)

    override fun markFailed(clientId: String, nowMs: Long) {
        nativeMarkFailed(handle, clientId, nowMs)
    }

    override fun pendingCount(): Int = nativePendingCount(handle)

    override fun close() {
        if (!closed && handle != 0L) {
            nativeClose(handle)
            handle = 0L
            closed = true
        }
    }

    private external fun nativeOpen(path: String): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeEnqueue(handle: Long, conversationId: String, body: String): String?
    private external fun nativeDue(handle: Long, nowMs: Long): String?
    private external fun nativePending(handle: Long): String?
    private external fun nativeMarkSent(handle: Long, clientId: String): Boolean
    private external fun nativeMarkFailed(handle: Long, clientId: String, nowMs: Long)
    private external fun nativePendingCount(handle: Long): Int

    private companion object {
        @Volatile
        private var loaded = false

        fun loadLibrary() {
            if (!loaded) {
                System.loadLibrary("secretchat_core")
                loaded = true
            }
        }
    }
}
