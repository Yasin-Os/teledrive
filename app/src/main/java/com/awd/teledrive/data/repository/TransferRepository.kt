package com.awd.teledrive.data.repository

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.awd.teledrive.data.local.DriveDao
import com.awd.teledrive.data.local.TransferDao
import com.awd.teledrive.data.local.TransferEntity
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

    // ফাস্ট ইন-মেমোরি ট্র্যাকার (যাতে ডাটাবেসের কারণে কখনোই আপলোড/ডাউনলোড আটকে না থাকে বা ল্যাগ না করে)
    private val activeMap = ConcurrentHashMap<String, TransferInfo>()
    private val fileIdToKey = ConcurrentHashMap<Int, String>()
    private val pathToKey = ConcurrentHashMap<String, String>()
    private val idMigrationMap = ConcurrentHashMap<String, String>()
    private val completionDeferreds = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val pausedIds = ConcurrentHashMap.newKeySet<String>()
    private val lastEmitTime = ConcurrentHashMap<String, Long>()

    private val _transfers = MutableStateFlow<Map<String, TransferInfo>>(emptyMap())
    val transfers: StateFlow<Map<String, TransferInfo>> = _transfers.asStateFlow()

    private fun publishState() {
        _transfers.value = HashMap(activeMap)
    }

    init {
        scope.launch {
            transferDao.clearCompleted()

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
                val progress = if (done) 1.0f else (if (totalSize > 0L) (currentSize.toFloat() / totalSize.toFloat()).coerceIn(0f, 0.99f) else 0.0f)
                val isEffectivelyCompleted = done || (totalSize > 0L && currentSize >= totalSize)

                if (isEffectivelyCompleted) {
                    if (isDownload && localPath.isNotEmpty()) {
                        driveDao.updateLocalPath(fileId, localPath)
                        if (uniqueId.isNotEmpty()) {
                            driveDao.updateLocalPathByUniqueId(uniqueId, localPath)
                        }
                    }
                    completeAndCleanupTransfer(key, uniqueId, fileId)
                    return@collect
                }

                // UI স্মুথ রাখতে প্রতি ১২০ মিলিসেকেন্ডে প্রোগ্রেস আপডেট হবে
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

    fun completeAndCleanupTransfer(key: String, uniqueId: String = "", fileId: Int = 0) {
        val info = activeMap.remove(key)
        if (uniqueId.isNotEmpty()) activeMap.remove(uniqueId)
        if (fileId != 0) fileIdToKey.remove(fileId)
        info?.localPath?.let { pathToKey.remove(it) }
        lastEmitTime.remove(key)

        completionDeferreds.remove(key)?.complete(Unit)
        if (uniqueId.isNotEmpty()) completionDeferreds.remove(uniqueId)?.complete(Unit)
        if (fileId != 0) completionDeferreds.remove(fileId.toString())?.complete(Unit)

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
        val initialStatus = if (isDownload) Status.DOWNLOADING else Status.UPLOADING
        val info = TransferInfo(
            fileId = fileId,
            remoteUniqueId = key,
            fileName = fileName,
            progress = 0.01f,
            isDownload = isDownload,
            status = initialStatus,
            totalSize = totalSize,
            downloadedSize = 0L,
            localPath = localPath
        )

        activeMap[key] = info
        if (fileId != 0) fileIdToKey[fileId] = key
        if (!localPath.isNullOrEmpty()) pathToKey[localPath] = key
        publishState()

        // কোনো কিউ লক ছাড়াই সাথে সাথে রকেট স্পিডে আপলোড বা ডাউনলোড শুরু হবে
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.e("TransferRepo", "Transfer error: ${e.message}")
                completeAndCleanupTransfer(key, remoteUniqueId, fileId)
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
            completionDeferreds.remove(oldId)?.let { completionDeferreds[targetKey] = it }
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
            status = status ?: (if (isDownload) Status.DOWNLOADING else Status.UPLOADING),
            totalSize = totalSize,
            downloadedSize = 0L,
            localPath = localPath
        )
        activeMap[key] = info
        if (fileId != 0) fileIdToKey[fileId] = key
        if (!localPath.isNullOrEmpty()) pathToKey[localPath] = key
        publishState()
    }

    fun updateTransferManual(remoteUniqueId: String, progress: Float, status: String, throttled: Boolean = false) {
        val targetId = idMigrationMap[remoteUniqueId] ?: remoteUniqueId
        val isDone = progress >= 1.0f || status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED

        if (isDone) {
            completeAndCleanupTransfer(targetId, remoteUniqueId)
            return
        }

        if (throttled) {
            val now = System.currentTimeMillis()
            val last = lastEmitTime[targetId] ?: 0L
            if (now - last < 200L) return
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
        completeAndCleanupTransfer(targetId, remoteUniqueId, info?.fileId ?: 0)
    }

    fun cancelTransfer(uniqueId: String) {
        val targetId = idMigrationMap[uniqueId] ?: uniqueId
        pausedIds.remove(targetId)
        val info = activeMap[targetId]
        if (info != null) {
            if (info.fileId != 0) {
                if (info.isDownload) {
                    telegramClient.send(TdApi.CancelDownloadFile(info.fileId, false))
                    telegramClient.send(TdApi.DeleteFile(info.fileId))
                } else {
                    telegramClient.send(TdApi.CancelPreliminaryUploadFile(info.fileId))
                }
            }
            info.localPath?.let { path ->
                val f = File(path)
                if (f.exists() && path.startsWith(context.cacheDir.absolutePath)) f.delete()
            }
        }
        completeAndCleanupTransfer(targetId, uniqueId, info?.fileId ?: 0)
    }

    fun clearCompleted() {
        activeMap.entries.removeIf { (_, v) ->
            v.status == Status.COMPLETED || v.status == Status.CANCELLED || v.status == Status.FAILED
        }
        publishState()
        scope.launch {
            try {
                transferDao.clearCompleted()
            } catch (e: Exception) {}
        }
    }

    suspend fun waitForCompletion(remoteUniqueId: String) {
        val finalId = idMigrationMap[remoteUniqueId] ?: remoteUniqueId
        if (!activeMap.containsKey(finalId) && !activeMap.containsKey(remoteUniqueId)) return
        val deferred = completionDeferreds.getOrPut(finalId) { CompletableDeferred() }
        // কখনোই যাতে ইনফিনিট লুপে আটকে না থাকে সেজন্য সেফটি ওয়েট
        withTimeoutOrNull(30 * 60 * 1000L) {
            deferred.await()
        }
        completionDeferreds.remove(finalId)
    }

    fun saveToPublicDownloads(internalPath: String, fileName: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val sourceFile = File(internalPath)
                if (!sourceFile.exists()) {
                    launch(Dispatchers.Main) {
                        Toast.makeText(context, "File not downloaded yet", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val userDownloadUri = secureSettings.getDownloadUri()
                if (!userDownloadUri.isNullOrEmpty()) {
                    saveToUserSelectedFolder(sourceFile, fileName, userDownloadUri)
                    launch(Dispatchers.Main) {
                        Toast.makeText(context, "Saved to Phone: $fileName", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val extension = fileName.substringAfterLast('.', "").lowercase()
                val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { output ->
                            FileInputStream(sourceFile).use { input ->
                                input.copyTo(output, bufferSize = 128 * 1024)
                            }
                        }
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val destFile = File(downloadsDir, fileName)
                    FileOutputStream(destFile).use { output ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(output, bufferSize = 128 * 1024)
                        }
                    }
                }
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "Saved to Phone Downloads: $fileName", Toast.LENGTH_SHORT).show()
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
                        input.copyTo(output, bufferSize = 128 * 1024)
                    }
                }
            }
        } catch (e: Exception) {}
    }
}
