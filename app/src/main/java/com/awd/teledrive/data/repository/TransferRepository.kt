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

    private data class PendingTransfer(
        val remoteUniqueId: String,
        val block: suspend () -> Unit
    )

    private val queue = mutableListOf<PendingTransfer>()
    private var activeTransferId: String? = null
    private val completionDeferreds = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val idMigrationMap = mutableMapOf<String, String>()
    private val lastUpdateMap = mutableMapOf<String, Long>()
    private val lastProgressMap = mutableMapOf<String, Float>()
    private val pausedIds = mutableSetOf<String>()

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
            telegramClient.fileUpdates.collect { update ->
                val file = update.file
                val uniqueId = file.remote.uniqueId
                val fileId = file.id
                val localPath = file.local.path
                
                val entity = (if (localPath.isNotEmpty()) transferDao.getTransferByLocalPath(localPath) else null)
                    ?: transferDao.getTransferByUniqueId(uniqueId) 
                    ?: transferDao.getTransferByFileId(fileId)
                    ?: return@collect

                if (pausedIds.contains(entity.remoteUniqueId) || pausedIds.contains(uniqueId)) {
                    return@collect
                }
                
                val isDownload = entity.isDownload
                val isCompleted = if (isDownload) file.local.isDownloadingCompleted else file.remote.isUploadingCompleted
                val totalSize = if (file.expectedSize > 0L) file.expectedSize else (if (file.size > 0L) file.size else entity.totalSize)
                
                val currentSize = if (isDownload) file.local.downloadedSize else file.remote.uploadedSize
                val progress = if (isCompleted) 1.0f else (if (totalSize > 0L) currentSize.toFloat() / totalSize.toFloat() else 0.0f)
                val isEffectivelyCompleted = isCompleted || (!isDownload && totalSize > 0L && currentSize >= totalSize)
                
                val status = when {
                    isEffectivelyCompleted -> Status.COMPLETED
                    isDownload && file.local.isDownloadingActive -> Status.DOWNLOADING
                    !isDownload && (file.remote.isUploadingActive || file.remote.uploadedSize > 0L) -> Status.UPLOADING
                    entity.status == Status.PAUSED -> Status.PAUSED
                    else -> entity.status
                }
                
                val isTerminal = status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED
                if (isTerminal) {
                    synchronized(queue) {
                        if (uniqueId.isNotEmpty()) completionDeferreds[uniqueId]?.complete(Unit)
                        completionDeferreds[entity.remoteUniqueId]?.complete(Unit)
                        completionDeferreds[fileId.toString()]?.complete(Unit)
                        
                        if (activeTransferId == uniqueId || activeTransferId == entity.remoteUniqueId || activeTransferId == fileId.toString()) {
                            activeTransferId = null
                            processQueue()
                        }
                    }

                    if (status == Status.COMPLETED) {
                        if (!isDownload) {
                            // আপলোড শেষ হলে সাথে সাথে টেম্পোরারি ক্যাশ ফাইল মুছে ফেলা হচ্ছে যাতে ক্যাশ জমে না থাকে
                            scope.launch {
                                if (uniqueId.isNotEmpty()) driveDao.updateLocalPathByUniqueId(uniqueId, null)
                                entity.localPath?.let { path ->
                                    val f = File(path)
                                    if (f.exists() && path.startsWith(context.cacheDir.absolutePath)) {
                                        f.delete()
                                    }
                                }
                            }
                        } else if (localPath.isNotEmpty()) {
                            // ডাউনলোড শেষ হলে অ্যাপের ভেতর অফলাইনের জন্য পাথ সেভ থাকবে (ফোন মেমোরিতে অটো যাবে না)
                            scope.launch {
                                transferDao.updateLocalPath(entity.remoteUniqueId, localPath)
                                if (uniqueId.isNotEmpty()) {
                                    driveDao.updateLocalPathByUniqueId(uniqueId, localPath)
                                }
                            }
                        }
                    }
                }
                
                if (uniqueId.isNotEmpty() && entity.remoteUniqueId != uniqueId) {
                    updateRemoteUniqueId(entity.remoteUniqueId, uniqueId, fileId, localPath.takeIf { it.isNotEmpty() })
                } else {
                    transferDao.updateProgress(entity.remoteUniqueId, progress, status, currentSize)
                    if (localPath.isNotEmpty() && isDownload) {
                        transferDao.updateLocalPath(entity.remoteUniqueId, localPath)
                    }
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
                        Toast.makeText(context, "File not found in local cache", Toast.LENGTH_SHORT).show()
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
                                input.copyTo(output)
                            }
                        }
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val destFile = File(downloadsDir, fileName)
                    FileOutputStream(destFile).use { output ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(output)
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
                        input.copyTo(output)
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
            completionDeferreds[oldId]?.let {
                completionDeferreds[newUniqueId] = it
                completionDeferreds.remove(oldId)
            }
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
            } catch (e: Exception) {
                Log.e("TransferRepo", "Migration failed: ${e.message}")
            }
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
            val key = if (remoteUniqueId.isNotEmpty()) remoteUniqueId else "temp_${System.currentTimeMillis()}_$fileId"
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
                    transferDao.updateProgress(next.remoteUniqueId, 0f, Status.FAILED, 0L)
                    synchronized(queue) {
                        if (activeTransferId == next.remoteUniqueId) activeTransferId = null
                    }
                    processQueue()
                }
            }
        }
    }

    fun addTransfer(fileId: Int, remoteUniqueId: String, fileName: String, isDownload: Boolean, totalSize: Long = 0, isCompleted: Boolean = false, status: String? = null, localPath: String? = null) {
        scope.launch {
            val key = if (remoteUniqueId.isNotEmpty()) remoteUniqueId else "temp_${System.currentTimeMillis()}_$fileId"
            val entity = TransferEntity(
                remoteUniqueId = key,
                fileId = fileId,
                fileName = fileName,
                progress = if (isCompleted) 1f else 0f,
                isDownload = isDownload,
                status = status ?: if (isCompleted) Status.COMPLETED else (if (isDownload) Status.DOWNLOADING else Status.UPLOADING),
                totalSize = totalSize,
                downloadedSize = if (isCompleted) totalSize else 0L,
                localPath = localPath
            )
            transferDao.insertTransfer(entity)
            if (entity.status == Status.DOWNLOADING || entity.status == Status.UPLOADING) {
                activeTransferId = key
            }
        }
    }

    fun updateTransferManual(remoteUniqueId: String, progress: Float, status: String, throttled: Boolean = false) {
        val targetId = synchronized(queue) { idMigrationMap[remoteUniqueId] ?: remoteUniqueId }
        if (throttled) {
            val now = System.currentTimeMillis()
            val lastTime = lastUpdateMap[targetId] ?: 0L
            val lastProgress = lastProgressMap[targetId] ?: -1f
            if (now - lastTime < 500 && kotlin.math.abs(progress - lastProgress) < 0.02f) return
            lastUpdateMap[targetId] = now
            lastProgressMap[targetId] = progress
        }

        scope.launch {
            try {
                if (progress >= 1.0f || status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED) {
                    val entity = transferDao.getTransferByUniqueId(targetId)
                    if (entity != null) {
                        transferDao.updateProgressFull(targetId, progress, status, entity.totalSize)
                    } else {
                        transferDao.updateProgressOnly(targetId, progress, status)
                    }
                    synchronized(queue) {
                        completionDeferreds[targetId]?.complete(Unit)
                        completionDeferreds[remoteUniqueId]?.complete(Unit)
                        if (activeTransferId == targetId || activeTransferId == remoteUniqueId) {
                            activeTransferId = null
                            processQueue()
                        }
                    }
                } else {
                    transferDao.updateProgressOnly(targetId, progress, status)
                }
            } catch (e: Exception) {
                Log.e("TransferRepo", "Failed to update progress: ${e.message}")
            }
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
            if (entity != null && !entity.isDownload && entity.localPath != null) {
                val f = File(entity.localPath)
                if (f.exists() && entity.localPath.startsWith(context.cacheDir.absolutePath)) {
                    f.delete()
                }
            }
            transferDao.deleteTransfer(remoteUniqueId)
        }
    }

    fun cancelTransfer(uniqueId: String) {
        scope.launch {
            pausedIds.remove(uniqueId)
            synchronized(queue) {
                queue.removeAll { it.remoteUniqueId == uniqueId }
                completionDeferreds[uniqueId]?.complete(Unit)
            }

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
                    if (f.exists() && path.startsWith(context.cacheDir.absolutePath)) {
                        f.delete()
                    }
                }
            }

            // ক্যানসেল করলে সাথে সাথে লিস্ট ও ক্যাশ থেকে ডিলিট হয়ে যাবে
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
                    if (file.isFile && (file.name.startsWith("enc_") || file.name.startsWith("bkp_"))) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    suspend fun waitForCompletion(remoteUniqueId: String) {
        val finalId = synchronized(queue) { idMigrationMap[remoteUniqueId] ?: remoteUniqueId }
        val currentTransfer = transfers.value[finalId]
        if (currentTransfer != null) {
            val status = currentTransfer.status
            if (status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED || status.startsWith("Failed")) {
                return
            }
        }

        val deferred = synchronized(queue) {
            val targetId = idMigrationMap[remoteUniqueId] ?: remoteUniqueId
            completionDeferreds.getOrPut(targetId) { CompletableDeferred() }
        }
        deferred.await()
        synchronized(queue) {
            completionDeferreds.remove(finalId)
            val originalId = idMigrationMap.filterValues { it == finalId }.keys.firstOrNull()
            if (originalId != null) idMigrationMap.remove(originalId)
        }
    }
}
