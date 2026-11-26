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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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

    // আপলোড ৩টি প্যারালাল স্ট্রিমে এবং ডাউনলোড সুপার ফাস্ট করতে ৪টি প্যারালাল স্ট্রিম দেওয়া হয়েছে
    private val uploadSemaphore = Semaphore(3)
    private val downloadSemaphore = Semaphore(4)

    private val activeMap = ConcurrentHashMap<String, TransferInfo>()
    private val fileIdToKey = ConcurrentHashMap<Int, String>()
    private val pathToKey = ConcurrentHashMap<String, String>()
    private val idMigrationMap = ConcurrentHashMap<String, String>()
    private val completionDeferreds = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pausedIds = ConcurrentHashMap.newKeySet<String>()
    private val lastEmitTime = ConcurrentHashMap<String, Long>()

    // M34: every Room write for transfers runs through this
    // single-threaded dispatcher, in launch order. Independent
    // scope.launch calls could reorder — a deleteTransfer running
    // before its insertTransfer — leaving a stale row that comes back
    // as "Failed" on the next launch.
    private val dbWriteDispatcher = Dispatchers.IO.limitedParallelism(1)

    // H20: a transfer paused while still queued keeps its block here so
    // resume can start it later; startedBlocks is the single-flight
    // guard so a block can never run twice (a queued launch and a
    // resume relaunch racing for the semaphore).
    private val pendingBlocks = ConcurrentHashMap<String, suspend () -> Unit>()
    private val startedBlocks = ConcurrentHashMap.newKeySet<String>()

    private val _transfers = MutableStateFlow<Map<String, TransferInfo>>(emptyMap())
    val transfers: StateFlow<Map<String, TransferInfo>> = _transfers.asStateFlow()

    private fun publishState() {
        _transfers.value = HashMap(activeMap)
    }

    // P1-7: transfer persistence — in-memory map-এর পাশাপাশি Room
    // `transfers` টেবিলেও লেখা হয়, যাতে প্রসেস মারা গেলেও transfer
    // তালিকা হারিয়ে না যায়। Progress tick প্রতি DB লেখা হয় না:
    // persistProgress() ডিফল্ট 1s throttle করে, status বদলের সময়
    // force=true দিয়ে সাথে সাথে লেখা হয়।
    private val lastPersistTime = ConcurrentHashMap<String, Long>()
    private val persistThrottleMs = 1000L

    private fun TransferInfo.toEntity(): TransferEntity = TransferEntity(
        remoteUniqueId = remoteUniqueId,
        fileId = fileId,
        fileName = fileName,
        progress = progress,
        isDownload = isDownload,
        status = status,
        totalSize = totalSize,
        downloadedSize = downloadedSize,
        localPath = localPath
    )

    private fun TransferEntity.toTransferInfo(): TransferInfo = TransferInfo(
        fileId = fileId,
        remoteUniqueId = remoteUniqueId,
        fileName = fileName,
        progress = progress,
        isDownload = isDownload,
        status = status,
        totalSize = totalSize,
        downloadedSize = downloadedSize,
        localPath = localPath
    )

    private fun persistInsert(info: TransferInfo) {
        scope.launch(dbWriteDispatcher) {
            try { transferDao.insertTransfer(info.toEntity()) } catch (e: Exception) {}
        }
    }

    private fun persistProgress(info: TransferInfo, force: Boolean = false) {
        if (!force) {
            val now = System.currentTimeMillis()
            val last = lastPersistTime[info.remoteUniqueId] ?: 0L
            if (now - last < persistThrottleMs) return
            lastPersistTime[info.remoteUniqueId] = now
        }
        scope.launch(dbWriteDispatcher) {
            try {
                transferDao.updateProgressFull(info.remoteUniqueId, info.progress, info.status, info.downloadedSize)
            } catch (e: Exception) {}
        }
    }

    // P1-7: startup-এ DB থেকে transfer ফিরিয়ে আনা। মাঝপথে থেমে যাওয়া
    // (Queued/Downloading/Uploading) row Failed হিসেবে দেখানো হয় —
    // auto-resume করা হয় না, কারণ TDLib ডাউনলোড/আপলোড আবার শুরু
    // করতে মূল ফাইল/চ্যাট প্রসঙ্গ লাগে; resume আলাদা কাজে আসবে।
    // Paused row যেমন ছিল তেমনই থাকে।
    private suspend fun restorePersistedTransfers() {
        val rows = try {
            transferDao.getAllTransfersFlow().first()
        } catch (e: Exception) {
            return
        }
        for (row in rows) {
            val wasActive = row.status == Status.QUEUED ||
                row.status == Status.DOWNLOADING || row.status == Status.UPLOADING
            val restored = row.toTransferInfo().let {
                if (wasActive) it.copy(status = Status.FAILED) else it
            }
            val key = restored.remoteUniqueId
            activeMap[key] = restored
            completionDeferreds.putIfAbsent(key, CompletableDeferred())
            if (restored.fileId != 0) fileIdToKey[restored.fileId] = key
            if (!restored.localPath.isNullOrEmpty()) pathToKey[restored.localPath] = key
            if (wasActive) persistProgress(restored, force = true)
        }
        if (rows.isNotEmpty()) publishState()
    }

    init {
        scope.launch {
            // M34: clear completed rows on the serialized write
            // dispatcher before restoring, so the restore cannot read
            // rows the clear was about to delete.
            withContext(dbWriteDispatcher) {
                try { transferDao.clearCompleted() } catch (e: Exception) { Log.w("TransferRepo", "clearCompleted failed", e) }
            }

            restorePersistedTransfers()

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
                        driveDao.updateFileLocalPaths(fileId, uniqueId, localPath)
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
                        driveDao.updateFileLocalPaths(fileId, uniqueId, localPath)
                    }
                    completeAndCleanupTransfer(key, uniqueId, fileId, success = true)
                    return@collect
                }

                val now = System.currentTimeMillis()
                val last = lastEmitTime[key] ?: 0L
                if (now - last >= 80L) {
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
                    persistProgress(updatedInfo)
                }
            }
        }
    }

    fun completeAndCleanupTransfer(key: String, uniqueId: String = "", fileId: Int = 0, success: Boolean = true) {
        val info = activeMap.remove(key)
        if (uniqueId.isNotEmpty()) activeMap.remove(uniqueId)
        // M15: a finished transfer must not keep its file-id mapping or
        // its paused flag. Callers that complete by key only pass
        // fileId = 0, which used to leak the fileIdToKey entry; and the
        // paused flag was never cleared here at all, so a later transfer
        // reusing the same key had its updates dropped by the collector.
        // Only remove the file-id mapping if it still points at this
        // transfer — the id may already belong to a newer transfer.
        val effectiveFileId = if (fileId != 0) fileId else info?.fileId ?: 0
        if (effectiveFileId != 0) {
            val mappedKey = fileIdToKey[effectiveFileId]
            if (mappedKey == key || (uniqueId.isNotEmpty() && mappedKey == uniqueId)) {
                fileIdToKey.remove(effectiveFileId)
            }
        }
        pausedIds.remove(key)
        if (uniqueId.isNotEmpty()) pausedIds.remove(uniqueId)
        pendingBlocks.remove(key)
        if (uniqueId.isNotEmpty()) pendingBlocks.remove(uniqueId)
        startedBlocks.remove(key)
        if (uniqueId.isNotEmpty()) startedBlocks.remove(uniqueId)
        info?.localPath?.let { pathToKey.remove(it) }
        lastEmitTime.remove(key)
        lastPersistTime.remove(key)
        if (uniqueId.isNotEmpty()) lastPersistTime.remove(uniqueId)

        completionDeferreds.remove(key)?.complete(success)
        if (uniqueId.isNotEmpty()) completionDeferreds.remove(uniqueId)?.complete(success)
        if (fileId != 0) completionDeferreds.remove(fileId.toString())?.complete(success)

        idMigrationMap.entries.filter { it.value == key || (uniqueId.isNotEmpty() && it.value == uniqueId) }.forEach { (oldK, _) ->
            activeMap.remove(oldK)
            completionDeferreds.remove(oldK)?.complete(success)
            idMigrationMap.remove(oldK)
            pausedIds.remove(oldK)
            pendingBlocks.remove(oldK)
            startedBlocks.remove(oldK)
        }

        publishState()

        scope.launch(dbWriteDispatcher) {
            try {
                transferDao.deleteTransfer(key)
                if (uniqueId.isNotEmpty()) transferDao.deleteTransfer(uniqueId)
            } catch (e: Exception) { Log.w("TransferRepo", "completeAndCleanupTransfer cleanup failed", e) }
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
        pendingBlocks[key] = block
        publishState()
        persistInsert(info)

        startTransferBlock(key, fileId, remoteUniqueId, isDownload, block)
    }

    /**
     * Runs a queued transfer's block under the transfer semaphore. The
     * block is also retained in [pendingBlocks] until it starts, so a
     * transfer paused while queued can be started later by
     * [resumeTransfer] instead of running while paused.
     */
    private fun startTransferBlock(
        key: String,
        fileId: Int,
        remoteUniqueId: String,
        isDownload: Boolean,
        block: suspend () -> Unit
    ) {
        val semaphore = if (isDownload) downloadSemaphore else uploadSemaphore
        scope.launch {
            semaphore.withPermit {
                if (!activeMap.containsKey(key) && idMigrationMap[key] == null) {
                    return@withPermit
                }
                // H20: paused while still queued — do not run the block
                // now. It stays retained in pendingBlocks and resume
                // starts it; running it here would upload/download a
                // transfer the user paused.
                val currentKey = idMigrationMap[key] ?: key
                if (pausedIds.contains(currentKey) || pausedIds.contains(key)) {
                    return@withPermit
                }
                // Single-flight: a resume relaunch racing this launch
                // for the semaphore must not run the block a second time.
                if (!startedBlocks.add(key)) {
                    return@withPermit
                }
                activeMap[currentKey]?.let { current ->
                    val started = current.copy(
                        status = if (isDownload) Status.DOWNLOADING else Status.UPLOADING,
                        progress = 0.01f
                    )
                    activeMap[currentKey] = started
                    publishState()
                    persistProgress(started, force = true)
                }
                // The block is running now: forget the retained copy so a
                // later resume cannot launch it again.
                pendingBlocks.remove(key)
                try {
                    block()
                } catch (e: Exception) {
                    Log.e("TransferRepo", "Transfer error: ${e.message}")
                    completeAndCleanupTransfer(key, remoteUniqueId, fileId, success = false)
                } finally {
                    startedBlocks.remove(key)
                    // M16: an upload block that returns normally has
                    // finished its work — it waits for completion inside.
                    // If the entry is still here, no completion update
                    // ever arrived and it would sit in Uploading until
                    // the 30-minute waiter timeout. Complete it as
                    // failed so the entry, the deferred and the DB row
                    // do not leak. Downloads are excluded on purpose:
                    // their block returns right after issuing
                    // DownloadFile and the entry legitimately lives on
                    // until the file-update collector completes it.
                    if (!isDownload) {
                        val liveKey = idMigrationMap[key] ?: key
                        val live = activeMap[liveKey] ?: activeMap[remoteUniqueId]
                        // Fail it only if this block is still the live
                        // upload. Skip when paused (resume owns it now),
                        // or when the entry's file id moved on — resume
                        // re-issued the upload under a new file id and the
                        // collector has already picked that one up.
                        val stillThisUpload = live != null &&
                            live.status != Status.PAUSED &&
                            (fileId == 0 || live.fileId == fileId)
                        if (stillThisUpload) {
                            completeAndCleanupTransfer(liveKey, remoteUniqueId, fileId, success = false)
                        }
                    }
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
        scope.launch(dbWriteDispatcher) {
            try {
                if (oldId != targetKey) transferDao.updateUniqueId(oldId, targetKey)
                if (newFileId != 0) transferDao.updateFileId(targetKey, newFileId)
                if (!localPath.isNullOrEmpty()) transferDao.updateLocalPath(targetKey, localPath)
            } catch (e: Exception) {}
        }
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
        persistInsert(info)
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
            if (now - last < 100L) return
            lastEmitTime[targetId] = now
        }

        val current = activeMap[targetId] ?: activeMap[remoteUniqueId] ?: return
        val updated = current.copy(progress = progress, status = status)
        activeMap[targetId] = updated
        publishState()
        persistProgress(updated)
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
        persistProgress(info.copy(status = Status.PAUSED), force = true)
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
            // H20: pausing an upload cancelled its preliminary upload
            // at the TDLib level, so only flipping the status left the
            // transfer stalled forever. Re-issue the upload for the
            // local file: TDLib resumes (or restarts) the byte upload
            // and the file-update collector picks the progress up again
            // (by file id, or by local path if the resumed upload gets
            // a new file id). All uploads here are sent as documents,
            // so FileTypeDocument matches the original send.
            // fileId == 0 means the send has not reached TDLib yet —
            // there is nothing to re-issue; the running block sends it,
            // or the queued block is relaunched below.
            val path = info.localPath
            if (info.fileId != 0 && !path.isNullOrEmpty() && File(path).exists()) {
                telegramClient.send(TdApi.UploadFile(TdApi.InputFileLocal(path), TdApi.FileTypeDocument(), 32))
            }
        }
        // H20: the block never started (paused while queued) — start it
        // now. startTransferBlock's single-flight guard makes a resume
        // racing the original queued launch safe.
        pendingBlocks.remove(targetId)?.let { retained ->
            startTransferBlock(targetId, info.fileId, targetId, info.isDownload, retained)
        }
        activeMap[targetId]?.let { persistProgress(it, force = true) }
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
            v.status == TransferRepository.Status.COMPLETED || v.status == TransferRepository.Status.CANCELLED || v.status == TransferRepository.Status.FAILED
        }
        publishState()
        scope.launch(dbWriteDispatcher) {
            try { transferDao.clearCompleted() } catch (e: Exception) { Log.w("TransferRepo", "clearCompleted failed", e) }
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
                                input.copyTo(output, bufferSize = 512 * 1024)
                            }
                        }
                    }
                } else {
                    val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MyCloud")
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val destFile = File(downloadsDir, fileName)
                    FileOutputStream(destFile).use { output ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(output, bufferSize = 512 * 1024)
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
                        input.copyTo(output, bufferSize = 512 * 1024)
                    }
                }
            }
        } catch (e: Exception) { Log.w("TransferRepo", "saveToUserSelectedFolder failed", e) }
    }
}
