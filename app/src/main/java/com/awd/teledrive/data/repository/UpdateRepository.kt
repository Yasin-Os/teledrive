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
import kotlinx.serialization.json.Json
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
    private val GITHUB_REPO = "Yasin-Os/teledrive"

    private val json = Json { 
        ignoreUnknownKeys = true 
        coerceInputValues = true
    }

    suspend fun checkForUpdates(manual: Boolean = false) = withContext(Dispatchers.IO) {
        if (_updateState.value is UpdateState.Checking || _updateState.value is UpdateState.Downloading) return@withContext
        
        _updateState.value = UpdateState.Checking
        
        try {
            // প্রথমে সরাসরি গিটহাব ওয়েব রিডাইরেক্ট দিয়ে চেক করা হবে (এতে কখনোই HTTP 403 Rate Limit আসে না)
            val directRelease = fetchLatestViaRedirect() ?: fetchLatestViaApi()

            if (directRelease != null) {
                val installedVersion = VersionUtils.getVersionName(context).removePrefix("v").trim()
                val latestVersion = directRelease.tag_name.removePrefix("v").trim()

                if (!manual && dismissedTag == latestVersion) {
                    _updateState.value = UpdateState.Idle
                    return@withContext
                }

                if (latestVersion.isNotEmpty() && latestVersion != installedVersion) {
                    _updateState.value = UpdateState.NewVersionAvailable(directRelease)
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

    private fun fetchLatestViaRedirect(): GitHubRelease? {
        return try {
            val url = URL("https://github.com/$GITHUB_REPO/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            conn.connect()

            val finalUrl = conn.url.toString()
            conn.disconnect()

            if (finalUrl.contains("/releases/tag/")) {
                val latestTag = finalUrl.substringAfterLast("/releases/tag/")
                    .substringBefore("?")
                    .substringBefore("#")
                    .trim()
                val latestVer = latestTag.removePrefix("v").trim()
                val apkFileName = "awd-teledrive-v$latestVer-universal-debug.apk"
                val directDownloadUrl = "https://github.com/$GITHUB_REPO/releases/download/$latestTag/$apkFileName"

                GitHubRelease(
                    tag_name = latestTag,
                    name = "MyCloud $latestTag",
                    body = "New update ($latestTag) is available. Tap 'Update Now' to install directly.",
                    html_url = finalUrl,
                    assets = listOf(
                        GitHubAsset(
                            name = apkFileName,
                            browser_download_url = directDownloadUrl
                        )
                    )
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchLatestViaApi(): GitHubRelease? {
        return try {
            val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "MyCloud-Android-App")

            if (connection.responseCode == 200) {
                val responseBody = connection.inputStream.bufferedReader().readText()
                json.decodeFromString<GitHubRelease>(responseBody)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun downloadAndInstall(release: GitHubRelease) {
        if (_updateState.value is UpdateState.Downloading) return

        scope.launch {
            try {
                val supportedAbis = Build.SUPPORTED_ABIS
                val preferredAbi = supportedAbis.firstOrNull() ?: ""

                val apkAsset = release.assets.firstOrNull {
                    it.name.endsWith(".apk", ignoreCase = true) && preferredAbi.isNotEmpty() && it.name.contains(preferredAbi, ignoreCase = true)
                } ?: release.assets.firstOrNull {
                    it.name.endsWith(".apk", ignoreCase = true) && it.name.contains("universal", ignoreCase = true)
                } ?: release.assets.firstOrNull {
                    it.name.endsWith(".apk", ignoreCase = true)
                }

                val downloadUrlStr = apkAsset?.browser_download_url?.takeIf { it.isNotEmpty() } ?: run {
                    val ver = release.tag_name.removePrefix("v").trim()
                    "https://github.com/$GITHUB_REPO/releases/download/${release.tag_name}/awd-teledrive-v$ver-universal-debug.apk"
                }

                _updateState.value = UpdateState.Downloading(0f, "Starting download...")

                val updateFile = File(context.cacheDir, "mycloud_update.apk")
                if (updateFile.exists()) updateFile.delete()

                var currentUrl = downloadUrlStr
                var redirects = 0
                var connection: HttpURLConnection

                while (true) {
                    val url = URL(currentUrl)
                    connection = url.openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                    connection.connect()

                    val code = connection.responseCode
                    if (code in 300..399 && redirects < 5) {
                        val loc = connection.getHeaderField("Location")
                        connection.disconnect()
                        if (!loc.isNullOrEmpty()) {
                            currentUrl = loc
                            redirects++
                            continue
                        }
                    }
                    break
                }

                if (connection.responseCode !in 200..299) {
                    _updateState.value = UpdateState.Error("Download failed: HTTP ${connection.responseCode}")
                    return@launch
                }

                val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: (apkAsset?.size ?: 0L)
                var downloadedBytes = 0L

                connection.inputStream.use { input ->
                    FileOutputStream(updateFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val progress = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                val dlMb = String.format(Locale.US, "%.1f", downloadedBytes / (1024.0 * 1024.0))
                                val totalMb = String.format(Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0))
                                _updateState.value = UpdateState.Downloading(progress, "$dlMb MB / $totalMb MB")
                            } else {
                                val dlMb = String.format(Locale.US, "%.1f", downloadedBytes / (1024.0 * 1024.0))
                                _updateState.value = UpdateState.Downloading(0.5f, "$dlMb MB downloaded")
                            }
                        }
                    }
                }

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

    private fun installApk(apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(context, "Allow 'Install unknown apps' to update MyCloud", Toast.LENGTH_LONG).show()
                val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permIntent)
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
