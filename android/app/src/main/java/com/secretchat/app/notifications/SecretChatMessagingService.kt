package com.secretchat.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.secretchat.app.data.AvatarLoader
import com.secretchat.app.data.SupabaseModule
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** Receives Firebase push messages and turns them into notifications. */
class SecretChatMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "message") return
        val conversationId = data["conversation_id"] ?: return
        val title = data["title"].orEmpty().ifBlank { "New message" }
        val body = data["body"].orEmpty().ifBlank { "New message" }
        // This runs on a background thread, so loading the picture here is fine.
        val avatar = data["sender_avatar_url"]
            ?.takeIf { it.isNotBlank() }
            ?.let { AvatarLoader.loadBlocking(it) }
        MessageNotifications.show(applicationContext, conversationId, title, body, avatar)
    }

    /** Firebase rotated this phone's token: save the new one for the signed-in user. */
    override fun onNewToken(token: String) {
        scope.launch {
            runCatching {
                val client = SupabaseModule.client()
                val userId = client.auth.currentUserOrNull()?.id ?: return@runCatching
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
        }
    }
}
