package com.secretchat.app.data

import android.content.Context
import android.net.Uri
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProfileRepository(
    private val client: SupabaseClient,
    context: Context,
) {
    private val appContext = context.applicationContext

    suspend fun profile(userId: String): Profile? =
        client.postgrest["profiles"].select {
            filter { eq("id", userId) }
        }.decodeSingleOrNull()

    /**
     * Uploads a new profile picture to `avatars/<userId>/avatar.jpg` and saves
     * its public URL on the profile. Returns the new URL.
     */
    suspend fun updateAvatar(userId: String, uri: Uri): String {
        val jpeg = withContext(Dispatchers.Default) { ImageCompressor.compressAvatar(appContext, uri) }
        val path = "$userId/avatar.jpg"
        val bucket = client.storage.from(AVATAR_BUCKET)
        bucket.upload(path, jpeg) {
            upsert = true
        }
        // The version query makes every device fetch the new picture instead of a cached one.
        val url = bucket.publicUrl(path) + "?v=" + System.currentTimeMillis()
        client.postgrest["profiles"].update(
            buildJsonObject { put("avatar_url", url) },
        ) {
            filter { eq("id", userId) }
        }
        return url
    }

    private companion object {
        const val AVATAR_BUCKET = "avatars"
    }
}
