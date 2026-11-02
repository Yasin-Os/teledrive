package com.awd.teledrive.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.awd.teledrive.core.utils.VersionUtils
import com.awd.teledrive.data.model.GitHubAsset
import com.awd.teledrive.data.model.GitHubRelease
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class NewVersionAvailable(val release: GitHubRelease) : UpdateState()
    data class Downloading(val progress: Float, val progressText: String) : UpdateState()
    object UpToDate : UpdateState()
    data class Error(val message: String) : UpdateState()
}

@Singleton
class UpdateRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState = _updateState.asStateFlow()

    private var dismissedTag: String? = null
    private var lastAutoCheckTime: Long = 0L
    private val GITHUB_REPO = "Yasin-Os/teledrive"

    suspend fun checkForUpdates(manual: Boolean = false) = withContext(Dispatchers.IO) {
        if (_updateState.value is UpdateState.Checking || _updateState.value is UpdateState.Downloading) return@withContext

        val now = System.currentTimeMillis()
        if (!manual && (now - lastAutoCheckTime < 15_000L)) return@withContext
        lastAutoCheckTime = now

        if (manual) {
            _updateState.value = UpdateState.Checking
        }

        try {
            // ৩টি লেয়ারে চেক করবে: ১. Redirect (সবচেয়ে ফাস্ট ও Rate-Limit মুক্ত), ২. GitHub API, ৩. Atom XML Feed
            val release = fetchLatestViaRedirect()
                ?: fetchLatestViaApi()
                ?: fetchLatestViaAtomFeed()

            if (release != null) {
                val installedVersion = VersionUtils.getVersionName(context).removePrefix("v").trim()
                val latestVersion = release.tag_name.removePrefix("v").trim()

                if (!manual && dismissedTag == latestVersion) {
                    _updateState.value = UpdateState.Idle
                    return@withContext
                }

                if (isNewerVersion(installedVersion, latestVersion)) {
                    _updateState.value = UpdateState.NewVersionAvailable(release)
                } else if (manual) {
                    _updateState.value = UpdateState.UpToDate
                } else {
                    _updateState.value = UpdateState.Idle
                }
            } else {
                _updateState.value = if (manual) UpdateState.Error("Could not reach update server") else UpdateState.Idle
            }
        } catch (e: Exception) {
            Log.e("UpdateRepository", "Failed to check for updates", e)
            _updateState.value = if (manual) UpdateState.Error(e.message ?: "Unknown error") else UpdateState.Idle
        }
    }

    private fun isNewerVersion(installed: String, latest: String): Boolean {
        if (latest.isEmpty()) return false
        if (installed == latest) return false
        val installedParts = installed.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(installedParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val inst = installedParts.getOrElse(i) { 0 }
            val lat = latestParts.getOrElse(i) { 0 }
            if (lat > inst) return true
            if (lat < inst) return false
        }
        return latest != installed
    }

    private fun fetchLatestViaRedirect(): GitHubRelease? {
        return try {
            var currentUrl = "https://github.com/$GITHUB_REPO/releases/latest"
            var finalUrl = currentUrl
            var redirects = 0

            while (redirects < 6) {
                val url = URL(currentUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                    setRequestProperty("Accept", "text/html,application/xhtml+xml")
                }
                val code = conn.responseCode
                finalUrl = conn.url.toString()

                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrEmpty()) break
                    currentUrl = if (loc.startsWith("http")) loc else URL(url, loc).toString()
                    finalUrl = currentUrl
                    redirects++
                } else {
                    conn.disconnect()
                    break
                }
            }

            if (finalUrl.contains("/releases/tag/")) {
                val latestTag = finalUrl.substringAfterLast("/releases/tag/")
                    .substringBefore("?")
                    .substringBefore("#")
                    .trim()
                if (latestTag.isNotEmpty()) {
                    buildReleaseFromTag(latestTag, finalUrl)
                } else null
            } else null
        } catch (e: Exception) {
            Log.e("UpdateRepository", "Redirect check error: ${e.message}")
            null
        }
    }

    private fun fetchLatestViaApi(): GitHubRelease? {
        return try {
            val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "MyCloud-Android-App")
            }

            if (connection.responseCode == 200) {
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val obj = JSONObject(responseBody)
                val tagName = obj.optString("tag_name", "").trim()
                if (tagName.isEmpty()) return null

                val name = obj.optString("name", "MyCloud $tagName").ifEmpty { "MyCloud $tagName" }
                val body = obj.optString("body", "").let {
                    if (it.isBlank() || it == "null") "New update ($tagName) is ready to install!" else it
                }
                val htmlUrl = obj.optString("html_url", "https://github.com/$GITHUB_REPO/releases/tag/$tagName")

                val assetsList = mutableListOf<GitHubAsset>()
                val assetsArr = obj.optJSONArray("assets")
                if (assetsArr != null) {
                    for (i in 0 until assetsArr.length()) {
                        val a = assetsArr.optJSONObject(i) ?: continue
                        val aName = a.optString("name", "")
                        val aUrl = a.optString("browser_download_url", "")
                        val aSize = a.optLong("size", 0L)
                        if (aName.isNotEmpty() && aUrl.isNotEmpty()) {
                            assetsList.add(GitHubAsset(name = aName, browser_download_url = aUrl, size = aSize))
                        }
                    }
                }

                if (assetsList.isEmpty()) {
                    return buildReleaseFromTag(tagName, htmlUrl, name, body)
                }

                GitHubRelease(
                    tag_name = tagName,
                    name = name,
                    body = body,
                    html_url = htmlUrl,
                    assets = assetsList
                )
            } else {
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.e("UpdateRepository", "API check error: ${e.message}")
            null
        }
    }

    private fun fetchLatestViaAtomFeed(): GitHubRelease? {
        return try {
            val url = URL("https://github.com/$GITHUB_REPO/releases.atom")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            }
            if (conn.responseCode == 200) {
                val xml = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val match = Regex("/releases/tag/([^\"'<\\s?]+)").find(xml)
                val tag = match?.groupValues?.getOrNull(1)?.trim()
                if (!tag.isNullOrEmpty()) {
                    buildReleaseFromTag(tag, "https://github.com/$GITHUB_REPO/releases/tag/$tag")
                } else null
            } else {
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildReleaseFromTag(
        tag: String,
        htmlUrl: String,
        title: String = "MyCloud $tag",
        notes: String = "A new version ($tag) of MyCloud is available! Tap 'Update Now' to install directly."
    ): GitHubRelease {
        val ver = tag.removePrefix("v").trim()
        val candidateNames = listOf(
            "awd-teledrive-v$ver-universal-debug.apk",
            "app-universal-debug.apk",
            "MyCloud-v$ver-universal-debug.apk",
            "app-debug.apk"
        )
        val assets = candidateNames.map { fileName ->
            GitHubAsset(
                name = fileName,
                browser_download_url = "https://github.com/$GITHUB_REPO/releases/download/$tag/$fileName"
            )
        }
        return GitHubRelease(
            tag_name = tag,
            name = title,
            body = notes,
            html_url = htmlUrl,
            assets = assets
        )
    }

    fun downloadAndInstall(release: GitHubRelease) {
        if (_updateState.value is UpdateState.Downloading) return

        scope.launch {
            try {
                _updateState.value = UpdateState.Downloading(0.01f, "Connecting to server...")

                val ver = release.tag_name.removePrefix("v").trim()
                val candidateUrls = mutableListOf<String>()

                release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
                    .sortedByDescending { it.name.contains("universal", ignoreCase = true) }
                    .forEach { asset ->
                        if (asset.browser_download_url.isNotEmpty()) {
                            candidateUrls.add(asset.browser_download_url)
                        }
                    }

                candidateUrls.add("https://github.com/$GITHUB_REPO/releases/download/${release.tag_name}/awd-teledrive-v$ver-universal-debug.apk")
                candidateUrls.add("https://github.com/$GITHUB_REPO/releases/download/${release.tag_name}/app-universal-debug.apk")
                candidateUrls.add("https://github.com/$GITHUB_REPO/releases/download/${release.tag_name}/app-debug.apk")

                var activeConnection: HttpURLConnection? = null
                for (candidate in candidateUrls.distinct()) {
                    val conn = openFollowingRedirects(candidate)
                    if (conn != null && conn.responseCode in 200..299) {
                        activeConnection = conn
                        break
                    } else {
                        conn?.disconnect()
                    }
                }

                if (activeConnection == null) {
                    _updateState.value = UpdateState.Error("APK file not ready yet. Please try again in 1 minute.")
                    return@launch
                }

                val updateDir = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
                val updateFile = File(updateDir, "MyCloud_Update.apk")
                if (updateFile.exists()) updateFile.delete()

                val totalBytes = activeConnection.contentLengthLong.takeIf { it > 0 }
                    ?: release.assets.firstOrNull { it.size > 0 }?.size
                    ?: 0L
                var downloadedBytes = 0L

                activeConnection.inputStream.use { input ->
                    FileOutputStream(updateFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val progress = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0.01f, 1f)
                                val dlMb = String.format(Locale.US, "%.1f", downloadedBytes / (1024.0 * 1024.0))
                                val totalMb = String.format(Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0))
                                _updateState.value = UpdateState.Downloading(progress, "$dlMb MB / $totalMb MB")
                            } else {
                                val dlMb = String.format(Locale.US, "%.1f", downloadedBytes / (1024.0 * 1024.0))
                                _updateState.value = UpdateState.Downloading(0.5f, "$dlMb MB downloaded")
                            }
                        }
                        output.flush()
                    }
                }
                activeConnection.disconnect()

                _updateState.value = UpdateState.Idle

                withContext(Dispatchers.Main) {
                    installApk(updateFile)
                }
            } catch (e: Exception) {
                Log.e("UpdateRepository", "Error downloading APK", e)
                _updateState.value = UpdateState.Error(e.message ?: "Download failed")
            }
        }
    }

    private fun openFollowingRedirects(urlStr: String): HttpURLConnection? {
        return try {
            var currentUrl = urlStr
            var redirects = 0
            while (redirects < 10) {
                val url = URL(currentUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrEmpty()) return null
                    currentUrl = if (location.startsWith("http")) location else URL(url, location).toString()
                    redirects++
                } else {
                    return conn
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun installApk(apkFile: File) {
        try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                Toast.makeText(context, "Downloaded APK is invalid", Toast.LENGTH_SHORT).show()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(context, "Allow 'Install unknown apps' to update MyCloud", Toast.LENGTH_LONG).show()
                val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permIntent)
                return
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("UpdateRepository", "Install intent failed", e)
            Toast.makeText(context, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun resetState() {
        val currentState = _updateState.value
        if (currentState is UpdateState.NewVersionAvailable) {
            dismissedTag = currentState.release.tag_name.removePrefix("v").trim()
        }
        _updateState.value = UpdateState.Idle
    }
}
