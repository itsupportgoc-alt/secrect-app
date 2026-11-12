package com.secretchat.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.secretchat.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer release found on GitHub. */
data class AppUpdate(
    val version: String,
    val notes: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/**
 * Checks the GitHub "latest release" of the app and installs a newer APK.
 *
 * Publishing an update = create a GitHub release whose tag is the new
 * versionName (e.g. "v1.3") with the APK attached.
 */
class UpdateChecker(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tagName: String,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
        val size: Long = 0,
    )

    /** Returns the newer release, or null when up to date (or offline). */
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val text = try {
                if (connection.responseCode != 200) return@runCatching null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            val release = json.decodeFromString(Release.serializer(), text)
            if (release.draft || release.prerelease) return@runCatching null
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return@runCatching null
            if (!isNewer(release.tagName, BuildConfig.VERSION_NAME)) return@runCatching null
            AppUpdate(
                version = release.tagName.removePrefix("v").removePrefix("V"),
                notes = release.body.orEmpty().trim(),
                downloadUrl = apk.downloadUrl,
                sizeBytes = apk.size,
            )
        }.getOrNull()
    }

    /** Downloads the APK into the app cache, reporting progress from 0 to 1. */
    suspend fun download(update: AppUpdate, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "updates").apply {
            deleteRecursively()
            mkdirs()
        }
        val target = File(dir, "secret-chat-${update.version}.apk")
        var url = URL(update.downloadUrl)
        // GitHub answers with a redirect to its file host; follow it by hand.
        repeat(5) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                when (connection.responseCode) {
                    in 300..399 -> {
                        url = URL(url, connection.getHeaderField("Location"))
                    }
                    200 -> {
                        val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes
                        connection.inputStream.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var copied = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    copied += read
                                    if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                        return@withContext target
                    }
                    else -> error("Download failed (HTTP ${connection.responseCode})")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Download failed (too many redirects)")
    }

    /** True when Android will let this app open the installer. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            appContext.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" switch for Secret Chat. */
    fun openInstallPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    /** Hands the downloaded APK to Android's package installer. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/itsupportgoc-alt/secrect-app/releases/latest"

        /** Compares "v1.10.2" style versions numerically. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
                .split('.', '-', ' ')
                .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
