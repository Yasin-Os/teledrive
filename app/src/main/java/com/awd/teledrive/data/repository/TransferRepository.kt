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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

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

    private data class PendingTransfer(
        val remoteUniqueId: String,
        val block: suspend () -> Unit
    )

    private val queue = mutableListOf<PendingTransfer>()
    private var activeTransferId: String? = null
    private val completionDeferreds = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val idMigrationMap = ConcurrentHashMap<String, String>()
    private val lastUpdateMap = ConcurrentHashMap<String, Long>()
    private val lastProgressMap = ConcurrentHashMap<String, Float>()
    private val pausedIds = ConcurrentHashMap.newKeySet<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    val transfers = transferDao.getAllTransfersFlow()
        .map { entities ->
            entities.associateBy({ it.remoteUniqueId }, { entity ->
                TransferInfo(
                    fileId = entity.fileId,
                    remoteUniqueId = entity.remoteUniqueId,
                    fileName = entity.fileName,
                    progress = entity.progress,
                    isDownload = entity.isDownload,
                    status = entity.status,
                    totalSize = entity.totalSize,
                    downloadedSize = entity.downloadedSize,
                    localPath = entity.localPath
                )
            })
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch {
            transferDao.clearCompleted()

            telegramClient.fileUpdates.collect { update ->
                val file = update.file
                val uniqueId = file.remote.uniqueId
                val fileId = file.id
                val localPath = file.local.path

                val isCompleted = file.local.isDownloadingCompleted || file.remote.isUploadingCompleted
                val now = System.currentTimeMillis()
                val throttleKey = uniqueId.ifEmpty { fileId.toString() }

                // ল্যাগ বন্ধ করতে প্রতি ২৫০ মিলিসেকেন্ডে একবার ডাটাবেস আপডেট হবে (তবে ১০০% হলে সাথে সাথে হবে)
                if (!isCompleted) {
                    val lastTime = lastUpdateMap[throttleKey] ?: 0L
                    if (now - lastTime < 250L) return@collect
                    lastUpdateMap[throttleKey] = now
                }

                val entity = transferDao.getTransferByUniqueId(uniqueId)
                    ?: transferDao.getTransferByFileId(fileId)
                    ?: (if (localPath.isNotEmpty()) transferDao.getTransferByLocalPath(localPath) else null)
                    ?: return@collect

                if (pausedIds.contains(entity.remoteUniqueId) || pausedIds.contains(uniqueId)) {
                    return@collect
                }
                
                val isDownload = entity.isDownload
                val done = if (isDownload) file.local.isDownloadingCompleted else file.remote.isUploadingCompleted
                val totalSize = if (file.expectedSize > 0L) file.expectedSize else (if (file.size > 0L) file.size else entity.totalSize)
                val currentSize = if (isDownload) file.local.downloadedSize else file.remote.uploadedSize
                val progress = if (done) 1.0f else (if (totalSize > 0L) (currentSize.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f) else 0.0f)
                val isEffectivelyCompleted = done || (totalSize > 0L && currentSize >= totalSize)
                
                val status = when {
                    isEffectivelyCompleted -> Status.COMPLETED
                    isDownload && file.local.isDownloadingActive -> Status.DOWNLOADING
                    !isDownload && (file.remote.isUploadingActive || file.remote.uploadedSize > 0L) -> Status.UPLOADING
                    entity.status == Status.PAUSED -> Status.PAUSED
                    else -> entity.status
                }
                
                val isTerminal = status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED
                if (isTerminal) {
                    if (uniqueId.isNotEmpty()) completionDeferreds[uniqueId]?.complete(Unit)
                    completionDeferreds[entity.remoteUniqueId]?.complete(Unit)
                    completionDeferreds[fileId.toString()]?.complete(Unit)
                    lastUpdateMap.remove(throttleKey)
                    lastProgressMap.remove(throttleKey)

                    synchronized(queue) {
                        if (activeTransferId == uniqueId || activeTransferId == entity.remoteUniqueId || activeTransferId == fileId.toString()) {
                            activeTransferId = null
                            processQueue()
                        }
                    }

                    if (status == Status.COMPLETED) {
                        if (isDownload && localPath.isNotEmpty()) {
                            driveDao.updateLocalPath(fileId, localPath)
                            if (uniqueId.isNotEmpty()) {
                                driveDao.updateLocalPathByUniqueId(uniqueId, localPath)
                            }
                        } else if (!isDownload) {
                            if (uniqueId.isNotEmpty()) driveDao.updateLocalPathByUniqueId(uniqueId, null)
                            entity.localPath?.let { path ->
                                val f = File(path)
                                if (f.exists() && path.startsWith(context.cacheDir.absolutePath)) {
                                    f.delete()
                                }
                            }
                        }
                    }

                    // আপলোড বা ডাউনলোড ১০০% হওয়ার সাথে সাথেই পপআপ থেকে ক্যাশ মুছে যাবে
                    transferDao.deleteTransfer(entity.remoteUniqueId)
                    if (uniqueId.isNotEmpty()) {
                        transferDao.deleteTransfer(uniqueId)
                    }
                    return@collect
                }
                
                if (uniqueId.isNotEmpty() && entity.remoteUniqueId != uniqueId) {
                    updateRemoteUniqueId(entity.remoteUniqueId, uniqueId, fileId, localPath.takeIf { it.isNotEmpty() })
                } else {
                    transferDao.updateProgress(entity.remoteUniqueId, progress, status, currentSize)
                }
            }
        }
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
                                input.copyTo(output, bufferSize = 64 * 1024)
                            }
                        }
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val destFile = File(downloadsDir, fileName)
                    FileOutputStream(destFile).use { output ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(output, bufferSize = 64 * 1024)
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
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TransferRepo", "Failed saving to user folder: ${e.message}")
        }
    }

    fun updateRemoteUniqueId(oldId: String, newUniqueId: String, newFileId: Int, localPath: String? = null) {
        if (oldId == newUniqueId && localPath == null) return
        synchronized(queue) {
            if (oldId != newUniqueId) idMigrationMap[oldId] = newUniqueId
            if (activeTransferId == oldId) activeTransferId = newUniqueId
            completionDeferreds.remove(oldId)?.let { completionDeferreds[newUniqueId] = it }
        }

        scope.launch {
            try {
                if (oldId != newUniqueId) {
                    val existing = transferDao.getTransferByUniqueId(newUniqueId)
                    if (existing != null) {
                        transferDao.deleteTransfer(oldId)
                    } else {
                        transferDao.updateUniqueId(oldId, newUniqueId)
                    }
                }
                if (newFileId != 0) transferDao.updateFileId(newUniqueId, newFileId)
                if (localPath != null) transferDao.updateLocalPath(newUniqueId, localPath)
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
        scope.launch {
            val key = if (remoteUniqueId.isNotEmpty()) remoteUniqueId else "temp_${System.nanoTime()}_$fileId"
            if (activeTransferId == key || queue.any { it.remoteUniqueId == key }) return@launch
            
            val entity = TransferEntity(
                remoteUniqueId = key,
                fileId = fileId,
                fileName = fileName,
                progress = 0f,
                isDownload = isDownload,
                status = Status.QUEUED,
                totalSize = totalSize,
                downloadedSize = 0L,
                localPath = localPath
            )
            transferDao.insertTransfer(entity)
            synchronized(queue) {
                queue.add(PendingTransfer(key, block))
            }
            processQueue()
        }
    }

    private fun processQueue() {
        synchronized(queue) {
            if (activeTransferId != null || queue.isEmpty()) return
            val next = queue.removeAt(0)
            activeTransferId = next.remoteUniqueId
            
            scope.launch {
                try {
                    val entity = transferDao.getTransferByUniqueId(next.remoteUniqueId)
                    if (entity != null) {
                        val startStatus = if (entity.isDownload) Status.DOWNLOADING else Status.UPLOADING
                        transferDao.updateProgress(next.remoteUniqueId, 0f, startStatus, 0L)
                    }
                    next.block()
                } catch (e: Exception) {
                    transferDao.deleteTransfer(next.remoteUniqueId)
                    synchronized(queue) {
                        if (activeTransferId == next.remoteUniqueId) activeTransferId = null
                    }
                    processQueue()
                }
            }
        }
    }

    fun addTransfer(fileId: Int, remoteUniqueId: String, fileName: String, isDownload: Boolean, totalSize: Long = 0, isCompleted: Boolean = false, status: String? = null, localPath: String? = null) {
        if (isCompleted) return
        scope.launch {
            val key = if (remoteUniqueId.isNotEmpty()) remoteUniqueId else "temp_${System.nanoTime()}_$fileId"
            val entity = TransferEntity(
                remoteUniqueId = key,
                fileId = fileId,
                fileName = fileName,
                progress = 0f,
                isDownload = isDownload,
                status = status ?: (if (isDownload) Status.DOWNLOADING else Status.UPLOADING),
                totalSize = totalSize,
                downloadedSize = 0L,
                localPath = localPath
            )
            transferDao.insertTransfer(entity)
            activeTransferId = key
        }
    }

    fun updateTransferManual(remoteUniqueId: String, progress: Float, status: String, throttled: Boolean = false) {
        val targetId = idMigrationMap[remoteUniqueId] ?: remoteUniqueId
        if (throttled) {
            val now = System.currentTimeMillis()
            val lastTime = lastUpdateMap[targetId] ?: 0L
            val lastProgress = lastProgressMap[targetId] ?: -1f
            if (now - lastTime < 350 && abs(progress - lastProgress) < 0.03f) return
            lastUpdateMap[targetId] = now
            lastProgressMap[targetId] = progress
        }

        scope.launch {
            try {
                val isDone = progress >= 1.0f || status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED
                if (isDone) {
                    completionDeferreds[targetId]?.complete(Unit)
                    completionDeferreds[remoteUniqueId]?.complete(Unit)
                    synchronized(queue) {
                        if (activeTransferId == targetId || activeTransferId == remoteUniqueId) {
                            activeTransferId = null
                            processQueue()
                        }
                    }
                    // সম্পন্ন হলে সাথে সাথে পপআপ থেকে মুছে যাবে
                    transferDao.deleteTransfer(targetId)
                    transferDao.deleteTransfer(remoteUniqueId)
                } else {
                    transferDao.updateProgressOnly(targetId, progress, status)
                }
            } catch (e: Exception) {}
        }
    }

    fun pauseTransfer(uniqueId: String) {
        scope.launch {
            pausedIds.add(uniqueId)
            val entity = transferDao.getTransferByUniqueId(uniqueId) ?: return@launch
            if (entity.fileId != 0) {
                if (entity.isDownload) {
                    telegramClient.send(TdApi.CancelDownloadFile(entity.fileId, false))
                } else {
                    telegramClient.send(TdApi.CancelPreliminaryUploadFile(entity.fileId))
                }
            }
            transferDao.updateProgress(uniqueId, entity.progress, Status.PAUSED, entity.downloadedSize)
            synchronized(queue) {
                if (activeTransferId == uniqueId) {
                    activeTransferId = null
                    processQueue()
                }
            }
        }
    }

    fun resumeTransfer(uniqueId: String) {
        scope.launch {
            pausedIds.remove(uniqueId)
            val entity = transferDao.getTransferByUniqueId(uniqueId) ?: return@launch
            if (entity.isDownload && entity.fileId != 0) {
                transferDao.updateProgress(uniqueId, entity.progress, Status.DOWNLOADING, entity.downloadedSize)
                telegramClient.send(TdApi.DownloadFile(entity.fileId, 32, 0, 0, false))
            } else if (!entity.isDownload && entity.localPath != null) {
                transferDao.updateProgress(uniqueId, entity.progress, Status.UPLOADING, entity.downloadedSize)
            }
        }
    }

    fun removeTransfer(remoteUniqueId: String) {
        scope.launch {
            pausedIds.remove(remoteUniqueId)
            val entity = transferDao.getTransferByUniqueId(remoteUniqueId)
            entity?.localPath?.let { path ->
                val f = File(path)
                if (f.exists() && path.startsWith(context.cacheDir.absolutePath)) f.delete()
            }
            transferDao.deleteTransfer(remoteUniqueId)
        }
    }

    fun cancelTransfer(uniqueId: String) {
        scope.launch {
            pausedIds.remove(uniqueId)
            synchronized(queue) {
                queue.removeAll { it.remoteUniqueId == uniqueId }
            }
            completionDeferreds[uniqueId]?.complete(Unit)

            val entity = transferDao.getTransferByUniqueId(uniqueId)
            if (entity != null) {
                if (entity.fileId != 0) {
                    if (entity.isDownload) {
                        telegramClient.send(TdApi.CancelDownloadFile(entity.fileId, false))
                        telegramClient.send(TdApi.DeleteFile(entity.fileId))
                    } else {
                        telegramClient.send(TdApi.CancelPreliminaryUploadFile(entity.fileId))
                    }
                }
                entity.localPath?.let { path ->
                    val f = File(path)
                    if (f.exists() && path.startsWith(context.cacheDir.absolutePath)) f.delete()
                }
            }

            transferDao.deleteTransfer(uniqueId)
            synchronized(queue) {
                if (activeTransferId == uniqueId) {
                    activeTransferId = null
                    processQueue()
                }
            }
        }
    }

    fun clearCompleted() {
        scope.launch {
            transferDao.clearCompleted()
            try {
                File(context.cacheDir, "upload_parts").deleteRecursively()
                context.cacheDir.listFiles()?.forEach { file ->
                    if (file.isFile && (file.name.startsWith("enc_") || file.name.startsWith("bkp_") || file.name.startsWith("up_") || file.name.startsWith("thumb_"))) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    suspend fun waitForCompletion(remoteUniqueId: String) {
        val finalId = idMigrationMap[remoteUniqueId] ?: remoteUniqueId
        val deferred = completionDeferreds.getOrPut(finalId) { CompletableDeferred() }
        deferred.await()
        completionDeferreds.remove(finalId)
        val originalId = idMigrationMap.filterValues { it == finalId }.keys.firstOrNull()
        if (originalId != null) idMigrationMap.remove(originalId)
    }
}
