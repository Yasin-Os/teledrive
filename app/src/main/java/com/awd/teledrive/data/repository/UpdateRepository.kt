package com.awd.teledrive.data.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.awd.teledrive.MainActivity
import com.awd.teledrive.core.utils.VersionUtils
import com.awd.teledrive.data.model.GitHubAsset
import com.awd.teledrive.data.model.GitHubRelease
import com.awd.teledrive.data.service.TransferService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class NewVersionAvailable(val release: GitHubRelease) : UpdateState()
    data class Downloading(
        val progress: Float,
        val progressText: String,
        val speedText: String = "",
        val release: GitHubRelease? = null
    ) : UpdateState()
    data class ReadyToInstall(val apkFile: File, val release: GitHubRelease) : UpdateState()
    object UpToDate : UpdateState()
    data class Error(val message: String) : UpdateState()
}

@Singleton
class UpdateRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val GITHUB_REPO = "Yasin-Os/teledrive"
        private const val UPDATE_CHANNEL_ID = "mycloud_app_update_channel"
        private const val UPDATE_NOTIFICATION_ID = 2026
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState = _updateState.asStateFlow()

    private val _availableRelease = MutableStateFlow<GitHubRelease?>(null)
    val availableRelease = _availableRelease.asStateFlow()

    private val _isDownloadMinimized = MutableStateFlow(false)
    val isDownloadMinimized = _isDownloadMinimized.asStateFlow()

    private var dismissedTag: String? = null
    private var lastAutoCheckTime: Long = 0L
    private var activeDownloadJob: Job? = null
    private val isChecking = AtomicBoolean(false)

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    init {
        createUpdateNotificationChannel()
    }

    fun setDownloadMinimized(minimized: Boolean) {
        _isDownloadMinimized.value = minimized
    }

    suspend fun checkForUpdates(manual: Boolean = false) = withContext(Dispatchers.IO) {
        val currentState = _updateState.value
        if (currentState is UpdateState.Downloading) {
            if (manual) _isDownloadMinimized.value = false
            return@withContext
        }
        if (currentState is UpdateState.ReadyToInstall) {
            if (manual) {
                _isDownloadMinimized.value = false
                withContext(Dispatchers.Main) { installApk(currentState.apkFile) }
            }
            return@withContext
        }

        if (!isChecking.compareAndSet(false, true)) return@withContext

        try {
            val now = System.currentTimeMillis()
            if (!manual && (now - lastAutoCheckTime < 8_000L)) return@withContext
            lastAutoCheckTime = now

            if (manual) {
                _isDownloadMinimized.value = false
                _updateState.value = UpdateState.Checking
            }

            val latestRelease = fetchLatestParallel()

            if (latestRelease != null) {
                val installedVersion = VersionUtils.getVersionName(context).removePrefix("v").trim()
                val latestVersion = latestRelease.tag_name.removePrefix("v").trim()

                if (isNewerVersion(installedVersion, latestVersion)) {
                    _availableRelease.value = latestRelease

                    val cachedApk = getUpdateApkFile(latestVersion)
                    if (isValidApkFile(cachedApk)) {
                        _isDownloadMinimized.value = false
                        _updateState.value = UpdateState.ReadyToInstall(cachedApk, latestRelease)
                        if (manual) {
                            withContext(Dispatchers.Main) { installApk(cachedApk) }
                        }
                        return@withContext
                    }

                    if (!manual && dismissedTag == latestVersion) {
                        return@withContext
                    }

                    // কোনো পপআপ ডায়ালগ না দেখিয়ে সরাসরি ব্যাকগ্রাউন্ডে অটো-ডাউনলোড শুরু করবে
                    _isDownloadMinimized.value = false
                    downloadAndInstall(latestRelease, runInBackground = true)
                } else {
                    _availableRelease.value = null
                    cleanupOldUpdateApks()
                    if (manual) {
                        _updateState.value = UpdateState.UpToDate
                    } else if (_updateState.value !is UpdateState.Downloading) {
                        _updateState.value = UpdateState.Idle
                    }
                }
            } else {
                if (manual) {
                    _updateState.value = UpdateState.Error("Could not reach update server")
                }
            }
        } catch (e: Exception) {
            Log.e("UpdateRepository", "Update check failed", e)
            if (manual) {
                _updateState.value = UpdateState.Error(e.message ?: "Unknown error")
            }
        } finally {
            isChecking.set(false)
        }
    }

    private suspend fun fetchLatestParallel(): GitHubRelease? = coroutineScope {
        val cb = System.currentTimeMillis()
        val apiDeferred = async { fetchLatestViaApi(cb) }
        val redirectDeferred = async { fetchLatestViaRedirect(cb) }
        val atomDeferred = async { fetchLatestViaAtomFeed(cb) }

        val candidates = listOfNotNull(
            apiDeferred.await(),
            redirectDeferred.await(),
            atomDeferred.await()
        )

        if (candidates.isEmpty()) return@coroutineScope null

        candidates.maxWithOrNull { a, b ->
            val vA = a.tag_name.removePrefix("v").trim()
            val vB = b.tag_name.removePrefix("v").trim()
            when {
                isNewerVersion(vA, vB) -> -1
                isNewerVersion(vB, vA) -> 1
                a.assets.any { it.size > 0 } && b.assets.none { it.size > 0 } -> 1
                else -> 0
            }
        }
    }

    private fun isNewerVersion(installed: String, latest: String): Boolean {
        if (latest.isEmpty() || installed == latest) return false
        val installedParts = installed.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(installedParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val inst = installedParts.getOrElse(i) { 0 }
            val lat = latestParts.getOrElse(i) { 0 }
            if (lat > inst) return true
            if (lat < inst) return false
        }
        return false
    }

    private fun fetchLatestViaApi(cacheBuster: Long): GitHubRelease? {
        return try {
            val urls = listOf(
                "https://api.github.com/repos/$GITHUB_REPO/releases/latest?cb=$cacheBuster",
                "https://api.github.com/repos/$GITHUB_REPO/releases?per_page=1&cb=$cacheBuster"
            )
            for (endpoint in urls) {
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                    useCaches = false
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                    setRequestProperty("User-Agent", "MyCloud-Updater/$cacheBuster")
                }
                if (connection.responseCode == 200) {
                    val bodyStr = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()
                    val obj = if (bodyStr.trimStart().startsWith("[")) {
                        val arr = JSONArray(bodyStr)
                        if (arr.length() > 0) arr.getJSONObject(0) else null
                    } else {
                        JSONObject(bodyStr)
                    }
                    if (obj != null) {
                        parseReleaseJson(obj)?.let { return it }
                    }
                } else {
                    connection.disconnect()
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseReleaseJson(obj: JSONObject): GitHubRelease? {
        val tagName = obj.optString("tag_name", "").trim()
        if (tagName.isEmpty()) return null

        val name = obj.optString("name", "MyCloud $tagName").ifEmpty { "MyCloud $tagName" }
        val body = obj.optString("body", "").let {
            if (it.isBlank() || it == "null") "Update $tagName" else it
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
        return GitHubRelease(
            tag_name = tagName,
            name = name,
            body = body,
            html_url = htmlUrl,
            assets = assetsList
        )
    }

    private fun fetchLatestViaRedirect(cacheBuster: Long): GitHubRelease? {
        return try {
            var currentUrl = "https://github.com/$GITHUB_REPO/releases/latest?cb=$cacheBuster"
            var finalUrl = currentUrl
            var redirects = 0

            while (redirects < 6) {
                val url = URL(currentUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
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
            null
        }
    }

    private fun fetchLatestViaAtomFeed(cacheBuster: Long): GitHubRelease? {
        return try {
            val url = URL("https://github.com/$GITHUB_REPO/releases.atom?cb=$cacheBuster")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 7000
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
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
        notes: String = "Update $tag"
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

    private fun getUpdateDir(): File {
        return File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
    }

    private fun getUpdateApkFile(version: String): File {
        val cleanVer = version.removePrefix("v").trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(getUpdateDir(), "MyCloud_v${cleanVer}.apk")
    }

    private fun isValidApkFile(file: File): Boolean {
        if (!file.exists() || file.length() < 5 * 1024 * 1024L) return false
        return try {
            val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            }
            archiveInfo != null && archiveInfo.packageName == context.packageName
        } catch (e: Exception) {
            true
        }
    }

    private fun cleanupOldUpdateApks() {
        try {
            getUpdateDir().listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {}
    }

    fun downloadAndInstall(release: GitHubRelease, runInBackground: Boolean = true) {
        val currentState = _updateState.value
        if (currentState is UpdateState.Downloading) {
            _isDownloadMinimized.value = false
            return
        }

        val ver = release.tag_name.removePrefix("v").trim()
        val finalApkFile = getUpdateApkFile(ver)
        if (isValidApkFile(finalApkFile)) {
            _isDownloadMinimized.value = false
            _updateState.value = UpdateState.ReadyToInstall(finalApkFile, release)
            if (!runInBackground) {
                installApk(finalApkFile)
            }
            return
        }

        _isDownloadMinimized.value = false
        startKeepAliveService()

        activeDownloadJob = scope.launch {
            val partFile = File(getUpdateDir(), "MyCloud_v${ver}.apk.part")
            try {
                _updateState.value = UpdateState.Downloading(0.01f, "0%", "", release)
                updateProgressNotification(release.tag_name, 1, "Starting...", "")

                val candidateUrls = buildCandidateUrls(release)
                var downloadedComplete = false

                for (attempt in 1..3) {
                    val existingBytes = if (partFile.exists()) partFile.length() else 0L
                    var activeConn: HttpURLConnection? = null

                    for (candidate in candidateUrls) {
                        val conn = openFollowingRedirects(candidate, existingBytes)
                        if (conn != null && (conn.responseCode in 200..299)) {
                            activeConn = conn
                            break
                        } else {
                            conn?.disconnect()
                        }
                    }

                    if (activeConn == null) {
                        if (attempt < 3) {
                            delay(1500L * attempt)
                            continue
                        } else break
                    }

                    try {
                        val responseCode = activeConn.responseCode
                        val isResuming = responseCode == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
                        val contentLength = activeConn.contentLengthLong
                        val totalBytes = when {
                            isResuming && contentLength > 0 -> existingBytes + contentLength
                            contentLength > 0 -> contentLength
                            else -> release.assets.firstOrNull { it.size > 0 }?.size ?: 0L
                        }

                        var downloadedBytes = if (isResuming) existingBytes else 0L
                        if (!isResuming && partFile.exists()) {
                            partFile.delete()
                        }

                        var lastUiUpdateTime = 0L
                        var speedWindowStart = System.currentTimeMillis()
                        var speedWindowBytes = downloadedBytes
                        var currentSpeedText = ""

                        BufferedInputStream(activeConn.inputStream, 128 * 1024).use { input ->
                            FileOutputStream(partFile, isResuming).use { output ->
                                val buffer = ByteArray(128 * 1024)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead

                                    val now = System.currentTimeMillis()
                                    val elapsedSpeed = now - speedWindowStart
                                    if (elapsedSpeed >= 600L) {
                                        val deltaBytes = downloadedBytes - speedWindowBytes
                                        val bytesPerSec = (deltaBytes * 1000L) / elapsedSpeed.coerceAtLeast(1L)
                                        currentSpeedText = formatSpeed(bytesPerSec)
                                        speedWindowStart = now
                                        speedWindowBytes = downloadedBytes
                                    }

                                    if (now - lastUiUpdateTime >= 100L || (totalBytes > 0 && downloadedBytes >= totalBytes)) {
                                        lastUiUpdateTime = now
                                        val progress = if (totalBytes > 0) {
                                            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0.01f, 0.99f)
                                        } else 0.5f

                                        val dlMb = String.format(Locale.US, "%.1f", downloadedBytes / (1024.0 * 1024.0))
                                        val progressText = if (totalBytes > 0) {
                                            val totalMb = String.format(Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0))
                                            "$dlMb / $totalMb MB"
                                        } else {
                                            "$dlMb MB"
                                        }

                                        _updateState.value = UpdateState.Downloading(
                                            progress = progress,
                                            progressText = progressText,
                                            speedText = currentSpeedText,
                                            release = release
                                        )
                                        updateProgressNotification(
                                            tagName = release.tag_name,
                                            percent = (progress * 100).toInt(),
                                            progressText = progressText,
                                            speedText = currentSpeedText
                                        )
                                    }
                                }
                                output.flush()
                            }
                        }
                        activeConn.disconnect()

                        if (partFile.exists() && partFile.length() > 5 * 1024 * 1024L &&
                            (totalBytes <= 0L || partFile.length() >= totalBytes)
                        ) {
                            if (finalApkFile.exists()) finalApkFile.delete()
                            val renamed = partFile.renameTo(finalApkFile)
                            if (!renamed) {
                                partFile.copyTo(finalApkFile, overwrite = true)
                                partFile.delete()
                            }
                            if (isValidApkFile(finalApkFile)) {
                                downloadedComplete = true
                                break
                            } else {
                                finalApkFile.delete()
                            }
                        }
                    } catch (streamEx: Exception) {
                        activeConn.disconnect()
                        if (attempt < 3) delay(1200L * attempt)
                    }
                }

                if (downloadedComplete && finalApkFile.exists()) {
                    _isDownloadMinimized.value = false
                    _updateState.value = UpdateState.ReadyToInstall(finalApkFile, release)
                    showReadyToInstallNotification(finalApkFile, release.tag_name)
                } else {
                    cancelNotification()
                    _updateState.value = UpdateState.Idle
                }
            } catch (e: Exception) {
                Log.e("UpdateRepository", "Error downloading APK", e)
                cancelNotification()
                _updateState.value = UpdateState.Idle
            }
        }
    }

    fun cancelUpdateDownload() {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        cancelNotification()
        _isDownloadMinimized.value = false
        val rel = _availableRelease.value
        _updateState.value = if (rel != null) UpdateState.NewVersionAvailable(rel) else UpdateState.Idle
    }

    private suspend fun buildCandidateUrls(release: GitHubRelease): List<String> {
        val ver = release.tag_name.removePrefix("v").trim()
        val list = mutableListOf<String>()

        val supportedAbis = Build.SUPPORTED_ABIS
        val preferredAbi = supportedAbis.firstOrNull() ?: ""

        release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            .sortedWith(
                compareByDescending<GitHubAsset> {
                    it.name.contains("universal", ignoreCase = true)
                }.thenByDescending {
                    preferredAbi.isNotEmpty() && it.name.contains(preferredAbi, ignoreCase = true)
                }
            )
            .forEach { asset ->
                if (asset.browser_download_url.isNotEmpty()) {
                    list.add(asset.browser_download_url)
                }
            }

        if (release.assets.all { it.size == 0L }) {
            fetchLatestViaApi(System.currentTimeMillis())?.assets?.forEach { asset ->
                if (asset.name.endsWith(".apk", ignoreCase = true) && asset.browser_download_url.isNotEmpty()) {
                    list.add(0, asset.browser_download_url)
                }
            }
        }

        list.add("https://github.com/$GITHUB_REPO/releases/download/${release.tag_name}/awd-teledrive-v$ver-universal-debug.apk")
        list.add("https://github.com/$GITHUB_REPO/releases/download/${release.tag_name}/app-universal-debug.apk")
        list.add("https://github.com/$GITHUB_REPO/releases/download/${release.tag_name}/app-debug.apk")

        return list.distinct()
    }

    private fun openFollowingRedirects(urlStr: String, resumeFromBytes: Long = 0L): HttpURLConnection? {
        return try {
            var currentUrl = urlStr
            var redirects = 0
            while (redirects < 10) {
                val url = URL(currentUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15000
                    readTimeout = 30000
                    useCaches = false
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (resumeFromBytes > 0L) {
                        setRequestProperty("Range", "bytes=$resumeFromBytes-")
                    }
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

    fun installApk(apkFile: File) {
        try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                val rel = _availableRelease.value
                if (rel != null) downloadAndInstall(rel, runInBackground = true)
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
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("UpdateRepository", "Install intent failed", e)
            Toast.makeText(context, "Install error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startKeepAliveService() {
        try {
            val intent = Intent(context, TransferService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {}
    }

    private fun createUpdateNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateProgressNotification(tagName: String, percent: Int, progressText: String, speedText: String) {
        try {
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 201, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val speedSuffix = if (speedText.isNotEmpty()) " • $speedText" else ""
            val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Updating MyCloud ($tagName)")
                .setContentText("$percent% • $progressText$speedSuffix")
                .setProgress(100, percent.coerceIn(0, 100), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .build()
            notificationManager.notify(UPDATE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {}
    }

    private fun showReadyToInstallNotification(apkFile: File, tagName: String) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 202, installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("MyCloud $tagName Ready")
                .setContentText("Tap to install now")
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            notificationManager.notify(UPDATE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {}
    }

    private fun cancelNotification() {
        try {
            notificationManager.cancel(UPDATE_NOTIFICATION_ID)
        } catch (e: Exception) {}
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return ""
        val mb = bytesPerSec / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB/s", mb)
        } else {
            val kb = (bytesPerSec / 1024.0).coerceAtLeast(1.0)
            String.format(Locale.US, "%.0f KB/s", kb)
        }
    }

    fun resetState() {
        val currentState = _updateState.value
        if (currentState is UpdateState.NewVersionAvailable) {
            dismissedTag = currentState.release.tag_name.removePrefix("v").trim()
        }
        if (currentState !is UpdateState.Downloading && currentState !is UpdateState.ReadyToInstall) {
            _updateState.value = UpdateState.Idle
        }
    }
}
