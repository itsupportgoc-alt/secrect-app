package com.secretchat.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Downloads and caches profile pictures (public URLs). */
object AvatarLoader {

    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cached(url: String): Bitmap? = cache.get(url)

    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) { loadBlocking(url) }
    }

    /** For background code that is already off the main thread (notifications). */
    fun loadBlocking(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            try {
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()?.also { cache.put(url, it) }
    }

    fun put(url: String, bitmap: Bitmap) {
        cache.put(url, bitmap)
    }
}
