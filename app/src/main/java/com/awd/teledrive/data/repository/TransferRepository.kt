package com.awd.teledrive.data.repository

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.awd.teledrive.data.local.DriveDao
import com.awd.teledrive.data.local.TransferDao
import com.awd.teledrive.data.model.TransferInfo
import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferRepository @Inject constructor(
    private val telegramClient: TelegramClient,
    private val secureSettings: SecureSettings,
    private val transferDao: TransferDao,
    private val driveDao: DriveDao,
    @param:ApplicationContext private val context: Context,
) {
    object Status {
        const val QUEUED = "Queued"
        const val DOWNLOADING = "Downloading"
        const val UPLOADING = "Uploading"
        const val PAUSED = "Paused"
        const val COMPLETED = "Completed"
        const val FAILED = "Failed"
        const val CANCELLED = "Cancelled"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ল্যাগ পুরোপুরি বন্ধ করতে Strict 1-by-1 Queue: ১টি ফাইল শেষ না হওয়া পর্যন্ত পরের ফাইলটি অপেক্ষা করবে
    private val uploadSemaphore = Semaphore(1)
    private val downloadSemaphore = Semaphore(2)

    private val activeMap = ConcurrentHashMap<String, TransferInfo>()
    private val fileIdToKey = ConcurrentHashMap<Int, String>()
    private val pathToKey = ConcurrentHashMap<String, String>()
    private val idMigrationMap = ConcurrentHashMap<String, String>()
    private val completionDeferreds = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pausedIds = ConcurrentHashMap.newKeySet<String>()
    private val lastEmitTime = ConcurrentHashMap<String, Long>()

    private val _transfers = MutableStateFlow<Map<String, TransferInfo>>(emptyMap())
    val transfers: StateFlow<Map<String, TransferInfo>> = _transfers.asStateFlow()

    private fun publishState() {
        _transfers.value = HashMap(activeMap)
    }

    init {
        scope.launch {
            try { transferDao.clearCompleted() } catch (e: Exception) {}

            telegramClient.fileUpdates.collect { update ->
                val file = update.file
                val fileId = file.id
                val uniqueId = file.remote.uniqueId
                val localPath = file.local.path

                val key = (if (uniqueId.isNotEmpty() && activeMap.containsKey(uniqueId)) uniqueId else null)
                    ?: fileIdToKey[fileId]
                    ?: (if (localPath.isNotEmpty()) pathToKey[localPath] else null)
                    ?: (if (uniqueId.isNotEmpty()) idMigrationMap.entries.find { it.value == uniqueId }?.key else null)

                if (key == null) {
                    if (file.local.isDownloadingCompleted && localPath.isNotEmpty()) {
                        driveDao.updateLocalPath(fileId, localPath)
                        if (uniqueId.isNotEmpty()) {
                            driveDao.updateLocalPathByUniqueId(uniqueId, localPath)
                            driveDao.updateThumbnailPathByUniqueId(uniqueId, localPath)
                        }
                    }
                    return@collect
                }

                if (pausedIds.contains(key) || pausedIds.contains(uniqueId)) return@collect

                val currentInfo = activeMap[key] ?: return@collect
                val isDownload = currentInfo.isDownload
                val done = if (isDownload) file.local.isDownloadingCompleted else file.remote.isUploadingCompleted
                val totalSize = if (file.expectedSize > 0L) file.expectedSize else (if (file.size > 0L) file.size else currentInfo.totalSize)
                val currentSize = if (isDownload) file.local.downloadedSize else file.remote.uploadedSize
                val progress = if (done) 1.0f else (if (totalSize > 0L) (currentSize.toFloat() / totalSize.toFloat()).coerceIn(0.01f, 0.99f) else 0.01f)
                val isEffectivelyCompleted = done || (totalSize > 0L && currentSize >= totalSize && (!isDownload || localPath.isNotEmpty()))

                if (isEffectivelyCompleted) {
                    if (isDownload && localPath.isNotEmpty()) {
                        // ডাউনলোড করা ফাইল শুধুমাত্র অ্যাপের নিজস্ব অফলাইন স্টোরেজে সংরক্ষিত থাকবে
                        driveDao.updateLocalPath(fileId, localPath)
                        if (uniqueId.isNotEmpty()) {
                            driveDao.updateLocalPathByUniqueId(uniqueId, localPath)
                        }
                    }
                    completeAndCleanupTransfer(key, uniqueId, fileId, success = true)
                    return@collect
                }

                val now = System.currentTimeMillis()
                val last = lastEmitTime[key] ?: 0L
                if (now - last >= 120L) {
                    lastEmitTime[key] = now
                    val activeStatus = if (isDownload) Status.DOWNLOADING else Status.UPLOADING
                    val updatedInfo = currentInfo.copy(
                        fileId = if (fileId != 0) fileId else currentInfo.fileId,
                        progress = progress,
                        status = activeStatus,
                        totalSize = totalSize,
                        downloadedSize = currentSize,
                        localPath = localPath.ifEmpty { currentInfo.localPath }
                    )
                    activeMap[key] = updatedInfo
                    publishState()
                }
            }
        }
    }

    fun completeAndCleanupTransfer(key: String, uniqueId: String = "", fileId: Int = 0, success: Boolean = true) {
        val info = activeMap.remove(key)
        if (uniqueId.isNotEmpty()) activeMap.remove(uniqueId)
        if (fileId != 0) fileIdToKey.remove(fileId)
        info?.localPath?.let { pathToKey.remove(it) }
        lastEmitTime.remove(key)

        completionDeferreds.remove(key)?.complete(success)
        if (uniqueId.isNotEmpty()) completionDeferreds.remove(uniqueId)?.complete(success)
        if (fileId != 0) completionDeferreds.remove(fileId.toString())?.complete(success)

        idMigrationMap.entries.filter { it.value == key || (uniqueId.isNotEmpty() && it.value == uniqueId) }.forEach { (oldK, _) ->
            activeMap.remove(oldK)
            completionDeferreds.remove(oldK)?.complete(success)
            idMigrationMap.remove(oldK)
        }

        publishState()

        scope.launch {
            try {
                transferDao.deleteTransfer(key)
                if (uniqueId.isNotEmpty()) transferDao.deleteTransfer(uniqueId)
            } catch (e: Exception) {}
        }
    }

    fun enqueueTransfer(
        fileId: Int,
        remoteUniqueId: String,
        fileName: String,
        isDownload: Boolean,
        totalSize: Long,
        localPath: String? = null,
        block: suspend () -> Unit
    ) {
        val key = remoteUniqueId.ifEmpty { "tr_${System.nanoTime()}_$fileId" }
        val info = TransferInfo(
            fileId = fileId,
            remoteUniqueId = key,
            fileName = fileName,
            progress = 0.0f,
            isDownload = isDownload,
            status = Status.QUEUED,
            totalSize = totalSize,
            downloadedSize = 0L,
            localPath = localPath
        )

        completionDeferreds.putIfAbsent(key, CompletableDeferred())
        activeMap[key] = info
        if (fileId != 0) fileIdToKey[fileId] = key
        if (!localPath.isNullOrEmpty()) pathToKey[localPath] = key
        publishState()

        val semaphore = if (isDownload) downloadSemaphore else uploadSemaphore
        scope.launch {
            semaphore.withPermit {
                if (!activeMap.containsKey(key) && idMigrationMap[key] == null) {
                    return@withPermit
                }
                val currentKey = idMigrationMap[key] ?: key
                activeMap[currentKey]?.let { current ->
                    activeMap[currentKey] = current.copy(
                        status = if (isDownload) Status.DOWNLOADING else Status.UPLOADING,
                        progress = 0.01f
                    )
                    publishState()
                }
                try {
                    block()
                } catch (e: Exception) {
                    Log.e("TransferRepo", "Transfer error: ${e.message}")
                    completeAndCleanupTransfer(key, remoteUniqueId, fileId, success = false)
                }
            }
        }
    }

    fun updateRemoteUniqueId(oldId: String, newUniqueId: String, newFileId: Int, localPath: String? = null) {
        val existing = activeMap[oldId] ?: return
        val targetKey = newUniqueId.ifEmpty { oldId }

        if (newFileId != 0) fileIdToKey[newFileId] = targetKey
        if (!localPath.isNullOrEmpty()) pathToKey[localPath] = targetKey

        val updated = existing.copy(
            fileId = if (newFileId != 0) newFileId else existing.fileId,
            remoteUniqueId = targetKey,
            localPath = localPath ?: existing.localPath
        )

        if (oldId != targetKey) {
            idMigrationMap[oldId] = targetKey
            activeMap.remove(oldId)
            completionDeferreds[oldId]?.let { completionDeferreds[targetKey] = it }
        }
        activeMap[targetKey] = updated
        publishState()
    }

    fun addTransfer(
        fileId: Int,
        remoteUniqueId: String,
        fileName: String,
        isDownload: Boolean,
        totalSize: Long = 0,
        isCompleted: Boolean = false,
        status: String? = null,
        localPath: String? = null
    ) {
        if (isCompleted) return
        val key = remoteUniqueId.ifEmpty { "tr_${System.nanoTime()}_$fileId" }
        val info = TransferInfo(
            fileId = fileId,
            remoteUniqueId = key,
            fileName = fileName,
            progress = 0f,
            isDownload = isDownload,
            status = status ?: Status.QUEUED,
            totalSize = totalSize,
            downloadedSize = 0L,
            localPath = localPath
        )
        completionDeferreds.putIfAbsent(key, CompletableDeferred())
        activeMap[key] = info
        if (fileId != 0) fileIdToKey[fileId] = key
        if (!localPath.isNullOrEmpty()) pathToKey[localPath] = key
        publishState()
    }

    fun updateTransferManual(remoteUniqueId: String, progress: Float, status: String, throttled: Boolean = false) {
        val targetId = idMigrationMap[remoteUniqueId] ?: remoteUniqueId
        val isDone = progress >= 1.0f || status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED

        if (isDone) {
            val ok = status != Status.FAILED && status != Status.CANCELLED
            completeAndCleanupTransfer(targetId, remoteUniqueId, success = ok)
            return
        }

        if (throttled) {
            val now = System.currentTimeMillis()
            val last = lastEmitTime[targetId] ?: 0L
            if (now - last < 150L) return
            lastEmitTime[targetId] = now
        }

        val current = activeMap[targetId] ?: activeMap[remoteUniqueId] ?: return
        activeMap[targetId] = current.copy(progress = progress, status = status)
        publishState()
    }

    fun pauseTransfer(uniqueId: String) {
        val targetId = idMigrationMap[uniqueId] ?: uniqueId
        pausedIds.add(targetId)
        val info = activeMap[targetId] ?: return
        if (info.fileId != 0) {
            if (info.isDownload) {
                telegramClient.send(TdApi.CancelDownloadFile(info.fileId, false))
            } else {
                telegramClient.send(TdApi.CancelPreliminaryUploadFile(info.fileId))
            }
        }
        activeMap[targetId] = info.copy(status = Status.PAUSED)
        publishState()
    }

    fun resumeTransfer(uniqueId: String) {
        val targetId = idMigrationMap[uniqueId] ?: uniqueId
        pausedIds.remove(targetId)
        val info = activeMap[targetId] ?: return
        if (info.isDownload && info.fileId != 0) {
            activeMap[targetId] = info.copy(status = Status.DOWNLOADING)
            publishState()
            telegramClient.send(TdApi.DownloadFile(info.fileId, 32, 0, 0, false))
        } else if (!info.isDownload) {
            activeMap[targetId] = info.copy(status = Status.UPLOADING)
            publishState()
        }
    }

    fun removeTransfer(remoteUniqueId: String) {
        val targetId = idMigrationMap[remoteUniqueId] ?: remoteUniqueId
        pausedIds.remove(targetId)
        val info = activeMap[targetId]
        info?.localPath?.let { path ->
            val f = File(path)
            if (f.exists() && path.startsWith(context.cacheDir.absolutePath)) f.delete()
        }
        completeAndCleanupTransfer(targetId, remoteUniqueId, info?.fileId ?: 0, success = false)
    }

    fun cancelTransfer(uniqueId: String) {
        val targetId = idMigrationMap[uniqueId] ?: uniqueId
        pausedIds.remove(targetId)
        val info = activeMap[targetId]
        if (info != null) {
            if (info.fileId != 0) {
                if (info.isDownload) {
                    telegramClient.send(TdApi.CancelDownloadFile(info.fileId, false))
                } else {
                    telegramClient.send(TdApi.CancelPreliminaryUploadFile(info.fileId))
                }
            }
            info.localPath?.let { path ->
                val f = File(path)
                if (f.exists() && path.startsWith(context.cacheDir.absolutePath)) f.delete()
            }
        }
        completeAndCleanupTransfer(targetId, uniqueId, info?.fileId ?: 0, success = false)
    }

    fun clearCompleted() {
        activeMap.entries.removeIf { (_, v) ->
            v.status == Status.COMPLETED || v.status == Status.CANCELLED || v.status == Status.FAILED
        }
        publishState()
        scope.launch {
            try { transferDao.clearCompleted() } catch (e: Exception) {}
        }
    }

    suspend fun waitForCompletion(remoteUniqueId: String): Boolean {
        val finalId = idMigrationMap[remoteUniqueId] ?: remoteUniqueId
        val deferred = completionDeferreds[finalId] ?: completionDeferreds[remoteUniqueId]
        if (deferred == null && !activeMap.containsKey(finalId) && !activeMap.containsKey(remoteUniqueId)) {
            return true
        }
        val activeDeferred = deferred ?: completionDeferreds.getOrPut(finalId) { CompletableDeferred() }
        val result = withTimeoutOrNull(30 * 60 * 1000L) {
            activeDeferred.await()
        } ?: false
        completionDeferreds.remove(finalId)
        completionDeferreds.remove(remoteUniqueId)
        return result
    }

    // শুধুমাত্র ইউজার যখন নিজে "Save to Phone Storage" চাপবে তখনই কেবল ফোন মেমোরিতে সেভ হবে
    fun saveToPublicDownloads(internalPath: String, fileName: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val sourceFile = File(internalPath)
                if (!sourceFile.exists()) {
                    launch(Dispatchers.Main) {
                        Toast.makeText(context, "আগে ফাইলটি অফলাইনে ডাউনলোড হতে দিন", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val userDownloadUri = secureSettings.getDownloadUri()
                if (!userDownloadUri.isNullOrEmpty()) {
                    saveToUserSelectedFolder(sourceFile, fileName, userDownloadUri)
                    launch(Dispatchers.Main) {
                        Toast.makeText(context, "ফোন স্টোরেজে সেভ হয়েছে: $fileName", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val extension = fileName.substringAfterLast('.', "").lowercase()
                val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MyCloud")
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { output ->
                            FileInputStream(sourceFile).use { input ->
                                input.copyTo(output, bufferSize = 256 * 1024)
                            }
                        }
                    }
                } else {
                    val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MyCloud")
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val destFile = File(downloadsDir, fileName)
                    FileOutputStream(destFile).use { output ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(output, bufferSize = 256 * 1024)
                        }
                    }
                    MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
                }
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "Saved to Phone (Downloads/MyCloud): $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("TransferRepo", "Error saving file: ${e.message}")
            }
        }
    }

    private fun saveToUserSelectedFolder(sourceFile: File, fileName: String, uriString: String) {
        try {
            val treeUri = android.net.Uri.parse(uriString)
            val docUri = DocumentFile.fromTreeUri(context, treeUri)
            if (docUri == null || !docUri.canWrite()) {
                secureSettings.saveDownloadUri("") 
                return
            }
            val extension = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            val newFile = docUri.createFile(mimeType, fileName)
            if (newFile != null) {
                context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(output, bufferSize = 256 * 1024)
                    }
                }
            }
        } catch (e: Exception) {}
    }
}
```
