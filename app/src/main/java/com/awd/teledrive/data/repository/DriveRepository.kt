// File: app/src/main/java/com/awd/teledrive/data/repository/DriveRepository.kt
package com.awd.teledrive.data.repository

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.awd.teledrive.R
import com.awd.teledrive.core.utils.FileUtils
import com.awd.teledrive.data.local.DriveDao
import com.awd.teledrive.data.local.DriveItemEntity
import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.secure.EncryptionManager
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.data.secure.SecureSettings
import com.awd.teledrive.domain.model.CloudBackup
import com.awd.teledrive.domain.model.DriveItem
import com.awd.teledrive.domain.model.FileMetadata
import com.awd.teledrive.domain.model.SplitFileMaster
import com.awd.teledrive.domain.model.TeleDriveManifest
import com.awd.teledrive.domain.model.VirtualFolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveRepository @Inject constructor(
    private val telegramClient: TelegramClient,
    private val driveDao: DriveDao,
    private val encryptionManager: EncryptionManager,
    private val secureSessionManager: SecureSessionManager,
    private val transferRepository: TransferRepository,
    private val settingsRepository: SettingsRepository,
    private val secureSettings: SecureSettings,
    @ApplicationContext private val context: Context
) {
    companion object {
        const val SAFE_SPACE_VIRTUAL_ID = "vf_safe_space"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var savedMessagesChatId: Long = 0
    private val _savedMessagesChatIdFlow = MutableStateFlow(0L)
    private val isBackupRunning = AtomicBoolean(false)

    private val pendingUploadTempIds = ConcurrentHashMap<Long, String>()
    private val pendingAutoDeleteFiles = ConcurrentHashMap<Long, File>()
    private val pendingBackupKeys = ConcurrentHashMap<Long, String>()

    fun getSavedMessagesChatIdFlow(): Flow<Long> = _savedMessagesChatIdFlow.asStateFlow()

    init {
        scope.launch {
            transferRepository.clearCompleted()

            telegramClient.updates.collect { update ->
                when (update) {
                    is TdApi.UpdateFile -> {
                        val file = update.file
                        if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) {
                            driveDao.updateLocalPath(file.id, file.local.path)
                            driveDao.updateLocalPathByUniqueId(file.remote.uniqueId, file.local.path)
                            driveDao.updateThumbnailPathByUniqueId(file.remote.uniqueId, file.local.path)
                        }
                    }
                    is TdApi.UpdateMessageSendSucceeded -> {
                        val msg = update.message
                        val oldId = update.oldMessageId
                        val tempId = pendingUploadTempIds.remove(oldId)
                        val file = extractTdFileFromMessage(msg)

                        if (tempId != null || file != null) {
                            transferRepository.completeAndCleanupTransfer(
                                key = tempId ?: (file?.remote?.uniqueId ?: ""),
                                uniqueId = file?.remote?.uniqueId ?: "",
                                fileId = file?.id ?: 0,
                                success = true
                            )
                        }

                        pendingBackupKeys.remove(oldId)?.let { bKey ->
                            markFileBackedUp(bKey)
                        }

                        if (msg.chatId == savedMessagesChatId || savedMessagesChatId == 0L) {
                            mapMessageToEntity(msg.chatId, msg)?.let { entity ->
                                val cleanEntity = entity.copy(localPath = null)
                                driveDao.insertItemsPreservingStarred(listOf(cleanEntity))
                            }
                        }

                        pendingAutoDeleteFiles.remove(oldId)?.let { phoneFile ->
                            deletePhoneFileCompletely(phoneFile)
                        }
                    }
                    is TdApi.UpdateMessageSendFailed -> {
                        val oldId = update.oldMessageId
                        val tempId = pendingUploadTempIds.remove(oldId)
                        pendingAutoDeleteFiles.remove(oldId)
                        pendingBackupKeys.remove(oldId)
                        if (tempId != null) {
                            transferRepository.completeAndCleanupTransfer(tempId, success = false)
                        }
                    }
                }
            }
        }
    }

    private fun markFileBackedUp(fileKey: String) {
        try {
            val customPrefs = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
            synchronized(this) {
                val current = customPrefs.getStringSet("backed_up_paths_v3", emptySet())?.toMutableSet() ?: mutableSetOf()
                current.add(fileKey)
                customPrefs.edit().putStringSet("backed_up_paths_v3", current).apply()
            }
        } catch (e: Exception) {}
    }

    private fun extractTdFileFromMessage(msg: TdApi.Message): TdApi.File? {
        return when (val c = msg.content) {
            is TdApi.MessageDocument -> c.document.document
            is TdApi.MessagePhoto -> c.photo.sizes.lastOrNull()?.photo
            is TdApi.MessageVideo -> c.video.video
            is TdApi.MessageAudio -> c.audio.audio
            is TdApi.MessageAnimation -> c.animation.animation
            else -> null
        }
    }

    // অফলাইনে ফাইলটি আসলেই অ্যাপের ভেতরে ডাউনলোড করা আছে কি না তা চেক করবে
    private fun verifyOfflinePath(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        val f = File(path)
        return if (f.exists() && f.length() > 0L) path else null
    }

    private val MANIFEST_PREFIX = "#TELEDRIVE_MANIFEST"
    private val MANIFEST_BACKUP_PREFIX = "#TELEDRIVE_MANIFEST_BACKUP_"
    private val VFOLDER_META_PREFIX = "#TELEDRIVE_VFOLDER_DATA"
    private val FILE_META_PREFIX = "#TELEDRIVE_FILE_DATA"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var currentManifest = TeleDriveManifest()
    private var manifestMessageId: Long = 0

    private fun ensureSavedMessagesChat(onReady: (Long) -> Unit) {
        if (savedMessagesChatId != 0L) {
            onReady(savedMessagesChatId)
            return
        }
        telegramClient.send(TdApi.GetMe()) { result ->
            if (result is TdApi.User) {
                val myUserId = result.id
                telegramClient.send(TdApi.CreatePrivateChat(myUserId, false)) { chatResult ->
                    val resolvedChatId = if (chatResult is TdApi.Chat) chatResult.id else myUserId
                    savedMessagesChatId = resolvedChatId
                    _savedMessagesChatIdFlow.value = resolvedChatId
                    onReady(resolvedChatId)
                }
            }
        }
    }

    suspend fun awaitSavedMessagesChatId(): Long {
        if (savedMessagesChatId != 0L) return savedMessagesChatId
        val deferred = CompletableDeferred<Long>()
        ensureSavedMessagesChat { id -> deferred.complete(id) }
        return withTimeoutOrNull(15000L) { deferred.await() } ?: 0L
    }

    fun normalizeFolderPath(rawPath: String): String {
        var clean = try { URLDecoder.decode(rawPath, "UTF-8") } catch (e: Exception) { rawPath }
        if (clean.startsWith("content://")) {
            val afterTree = clean.substringAfter("/tree/", "")
            if (afterTree.isNotEmpty()) {
                clean = afterTree.substringBefore("/document/")
            }
        }
        if (clean.contains("primary:")) {
            val rel = clean.substringAfter("primary:").trimStart('/')
            return "${Environment.getExternalStorageDirectory().absolutePath}/$rel".trimEnd('/')
        }
        if (clean.contains("home:")) {
            val rel = clean.substringAfter("home:").trimStart('/')
            return "${Environment.getExternalStorageDirectory().absolutePath}/Documents/$rel".trimEnd('/')
        }
        if (clean.contains(":") && !clean.startsWith("/")) {
            val parts = clean.split(":")
            if (parts.size == 2) {
                val storageId = parts[0].substringAfterLast('/')
                val rel = parts[1].trimStart('/')
                val candidate = File("/storage/$storageId/$rel")
                if (candidate.exists()) return candidate.absolutePath.trimEnd('/')
            }
        }
        if (!clean.startsWith("/")) {
            val candidate = File(Environment.getExternalStorageDirectory(), clean)
            if (candidate.exists()) return candidate.absolutePath.trimEnd('/')
        }
        return clean.trimEnd('/')
    }

    fun collectTargetBackupFiles(rawFolders: List<String>): List<File> {
        val customPrefs = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
        val backedUpSet = customPrefs.getStringSet("backed_up_paths_v3", emptySet()) ?: emptySet()
        val foundFiles = LinkedHashMap<String, File>()

        val normalizedPaths = rawFolders.map { normalizeFolderPath(it) }.filter { it.isNotEmpty() }

        normalizedPaths.forEach { path ->
            val dir = File(path)
            if (dir.exists()) {
                if (dir.isDirectory) {
                    scanDirFast(dir, backedUpSet, foundFiles)
                } else if (dir.isFile && dir.length() > 0) {
                    val key = "${dir.absolutePath}_${dir.length()}"
                    if (!backedUpSet.contains(key)) foundFiles[dir.absolutePath] = dir
                }
            }
        }

        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        val uris = mutableListOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri("external")
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            uris.add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        }

        uris.forEach { uri ->
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (dataIdx != -1) {
                        while (cursor.moveToNext()) {
                            val filePath = cursor.getString(dataIdx) ?: continue
                            if (normalizedPaths.any { folder -> filePath.startsWith("$folder/") || filePath == folder }) {
                                val f = File(filePath)
                                if (f.exists() && f.isFile && f.length() > 0 && !f.name.startsWith(".")) {
                                    val key = "${f.absolutePath}_${f.length()}"
                                    if (!backedUpSet.contains(key)) {
                                        foundFiles[f.absolutePath] = f
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        rawFolders.filter { it.startsWith("content://") }.forEach { uriStr ->
            try {
                val treeUri = Uri.parse(uriStr)
                val docDir = DocumentFile.fromTreeUri(context, treeUri)
                if (docDir != null && docDir.exists()) {
                    scanDocumentTreeFallback(docDir, backedUpSet, foundFiles)
                }
            } catch (e: Exception) {}
        }

        return foundFiles.values.sortedBy { it.lastModified() }
    }

    private fun scanDirFast(dir: File, backedUpSet: Set<String>, out: MutableMap<String, File>) {
        val list = dir.listFiles() ?: return
        for (child in list) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                scanDirFast(child, backedUpSet, out)
            } else if (child.isFile && child.length() > 0) {
                val key = "${child.absolutePath}_${child.length()}"
                if (!backedUpSet.contains(key)) {
                    out[child.absolutePath] = child
                }
            }
        }
    }

    private fun scanDocumentTreeFallback(docDir: DocumentFile, backedUpSet: Set<String>, out: MutableMap<String, File>) {
        for (doc in docDir.listFiles()) {
            val name = doc.name ?: continue
            if (name.startsWith(".")) continue
            if (doc.isDirectory) {
                scanDocumentTreeFallback(doc, backedUpSet, out)
            } else if (doc.isFile && doc.length() > 0) {
                val key = "doc_${doc.uri}_${doc.length()}"
                if (!backedUpSet.contains(key)) {
                    val tempFile = File(context.cacheDir, name)
                    try {
                        context.contentResolver.openInputStream(doc.uri)?.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output, bufferSize = 256 * 1024)
                            }
                        }
                        if (tempFile.exists() && tempFile.length() > 0) {
                            out[key] = tempFile
                        }
                    } catch (e: Exception) {}
                }
            }
        }
    }

    fun runImmediateFolderBackup() {
        if (!isBackupRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                val chatId = awaitSavedMessagesChatId()
                if (chatId == 0L) return@launch

                val backupFolders = secureSettings.getStringList("backup_folders")
                if (backupFolders.isEmpty()) return@launch

                val customPrefs = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
                val deleteEnabled = customPrefs.getBoolean("delete_after_backup", false)

                val filesToUpload = collectTargetBackupFiles(backupFolders)
                if (filesToUpload.isEmpty()) return@launch

                filesToUpload.forEach { file ->
                    if (file.exists() && file.length() > 0) {
                        val bKey = "${file.absolutePath}_${file.length()}"
                        uploadFile(
                            filePath = file.absolutePath,
                            originalFileName = file.name,
                            forceDeleteSource = deleteEnabled,
                            backupTrackingKey = bKey
                        )
                        delay(80)
                    }
                }
                secureSettings.saveLong("last_backup_time", System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e("DriveRepo", "Immediate backup error: ${e.message}")
            } finally {
                isBackupRunning.set(false)
            }
        }
    }

    fun fetchCloudManifest(onComplete: () -> Unit = {}) {
        ensureSavedMessagesChat { chatId ->
            val allMessages = mutableListOf<TdApi.Message>()
            var searchDone = false
            var historyDone = false

            fun tryFinish() {
                if (searchDone && historyDone) {
                    processManifestMessages(allMessages.distinctBy { it.id })
                    onComplete()
                }
            }

            val searchRequest = TdApi.SearchChatMessages().apply {
                this.chatId = chatId
                this.query = MANIFEST_PREFIX
                this.limit = 50
            }
            telegramClient.send(searchRequest) { result ->
                if (result is TdApi.Messages) synchronized(allMessages) { allMessages.addAll(result.messages.toList()) }
                searchDone = true
                tryFinish()
            }
            telegramClient.send(TdApi.GetChatHistory(chatId, 0, 0, 100, false)) { result ->
                if (result is TdApi.Messages) synchronized(allMessages) { allMessages.addAll(result.messages.toList()) }
                historyDone = true
                tryFinish()
            }
        }
    }

    private fun processManifestMessages(messages: List<TdApi.Message>) {
        val possibleManifests = messages.filter { msg ->
            val content = msg.content
            if (content is TdApi.MessageText) {
                val text = content.text.text
                text.contains(MANIFEST_PREFIX) || text.contains(MANIFEST_BACKUP_PREFIX) || text.contains("\"virtualFolders\"")
            } else false
        }
        if (possibleManifests.isEmpty()) return

        manifestMessageId = possibleManifests
            .filter { (it.content as? TdApi.MessageText)?.text?.text?.contains(MANIFEST_PREFIX) == true && !(it.content as TdApi.MessageText).text.text.contains(MANIFEST_BACKUP_PREFIX) }
            .maxByOrNull { it.date }?.id ?: 0L

        var mergedManifest = TeleDriveManifest()
        possibleManifests.sortedBy { it.date }.forEach { msg ->
            val content = msg.content
            if (content is TdApi.MessageText) {
                val text = content.text.text
                val jsonStartIndex = text.indexOf('{')
                if (jsonStartIndex != -1) {
                    try {
                        val m = json.decodeFromString<TeleDriveManifest>(text.substring(jsonStartIndex).trim())
                        mergedManifest = mergeManifests(mergedManifest, m)
                    } catch (e: Exception) {}
                }
            }
        }
        currentManifest = mergedManifest
        syncVirtualFoldersToDb()
    }

    private fun mergeManifests(base: TeleDriveManifest, other: TeleDriveManifest): TeleDriveManifest {
        val mergedDeletedIds = base.deletedFolderIds.toMutableMap().apply { putAll(other.deletedFolderIds) }
        val mergedFolders = base.virtualFolders.toMutableMap()
        other.virtualFolders.forEach { (id, vf) ->
            val deletedAt = mergedDeletedIds[id]
            if (deletedAt == null || vf.createdAt > deletedAt) mergedFolders[id] = vf
        }
        mergedFolders.keys.removeIf { id ->
            val deletedAt = mergedDeletedIds[id]
            deletedAt != null && (mergedFolders[id]?.createdAt ?: 0L) <= deletedAt
        }
        return base.copy(
            updatedAt = maxOf(base.updatedAt, other.updatedAt),
            virtualFolders = mergedFolders,
            fileMappings = base.fileMappings + other.fileMappings,
            secureFolderChatIds = base.secureFolderChatIds + other.secureFolderChatIds,
            splitFileMasters = base.splitFileMasters + other.splitFileMasters,
            folderMetadataIds = base.folderMetadataIds + other.folderMetadataIds,
            fileMetadataIds = base.fileMetadataIds + other.fileMetadataIds,
            deletedFolderIds = mergedDeletedIds
        )
    }

    fun setCloudItemsSafeState(itemIds: List<Long>, hideInSafeFolder: Boolean) {
        scope.launch {
            val targetVirtualParent = if (hideInSafeFolder) SAFE_SPACE_VIRTUAL_ID else "0"
            val updatedMappings = currentManifest.fileMappings.toMutableMap()
            itemIds.forEach { id ->
                driveDao.updateVirtualParent(id, targetVirtualParent)
                updatedMappings[id.toString()] = targetVirtualParent
            }
            synchronized(currentManifest) {
                currentManifest = currentManifest.copy(fileMappings = updatedMappings, updatedAt = System.currentTimeMillis() / 1000)
            }
            saveCloudManifest()
        }
    }

    fun getSafeFolderFilesFlow(): Flow<List<DriveItem.File>> {
        return driveDao.getAllFiles().map { entities ->
            entities.filter { it.virtualParentId == SAFE_SPACE_VIRTUAL_ID || it.isEncrypted || it.isSecure }
                .map { entity ->
                    DriveItem.File(
                        entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType,
                        entity.telegramFileId, verifyOfflinePath(entity.thumbnailPath), verifyOfflinePath(entity.localPath), entity.isStarred,
                        entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted
                    )
                }
        }
    }

    fun exportManifestToJson(): String = json.encodeToString(currentManifest)

    fun importManifestFromJson(jsonStr: String): Boolean {
        return try {
            currentManifest = json.decodeFromString<TeleDriveManifest>(jsonStr)
            saveCloudManifest()
            syncVirtualFoldersToDb()
            scope.launch { driveDao.resetAllFileVirtualParents(); fetchFiles() }
            true
        } catch (e: Exception) { false }
    }

    fun fetchCloudBackups(onResult: (List<CloudBackup>) -> Unit) {
        ensureSavedMessagesChat { chatId ->
            val searchRequest = TdApi.SearchChatMessages().apply {
                this.chatId = chatId
                this.query = MANIFEST_BACKUP_PREFIX
                this.limit = 50
            }
            telegramClient.send(searchRequest) { result ->
                if (result is TdApi.Messages) {
                    val backups = result.messages.mapNotNull { msg ->
                        val content = msg.content
                        if (content is TdApi.MessageText && content.text.text.startsWith(MANIFEST_BACKUP_PREFIX)) {
                            val text = content.text.text
                            val jsonStartIndex = text.indexOf('{')
                            if (jsonStartIndex != -1) {
                                try {
                                    val manifest = json.decodeFromString<TeleDriveManifest>(text.substring(jsonStartIndex))
                                    CloudBackup(msg.id, msg.date.toLong() * 1000, manifest.virtualFolders.size)
                                } catch (e: Exception) { null }
                            } else null
                        } else null
                    }
                    onResult(backups)
                } else onResult(emptyList())
            }
        }
    }

    fun restoreManifestFromMessage(messageId: Long, onResult: (Boolean) -> Unit) {
        ensureSavedMessagesChat { chatId ->
            telegramClient.send(TdApi.GetMessage(chatId, messageId)) { result ->
                if (result is TdApi.Message && result.content is TdApi.MessageText) {
                    val text = (result.content as TdApi.MessageText).text.text
                    val jsonStartIndex = text.indexOf('{')
                    if (jsonStartIndex != -1) {
                        try {
                            currentManifest = json.decodeFromString<TeleDriveManifest>(text.substring(jsonStartIndex))
                            saveCloudManifest()
                            syncVirtualFoldersToDb()
                            scope.launch { driveDao.resetAllFileVirtualParents(); fetchFiles() }
                            onResult(true)
                        } catch (e: Exception) { onResult(false) }
                    } else onResult(false)
                } else onResult(false)
            }
        }
    }

    fun createManualCloudBackup() {
        ensureSavedMessagesChat { chatId ->
            val text = "$MANIFEST_BACKUP_PREFIX${System.currentTimeMillis()}\n\n${json.encodeToString(currentManifest)}"
            telegramClient.send(TdApi.SendMessage(chatId, null, null, null, null, TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true)))
        }
    }

    private fun syncVirtualFoldersToDb() {
        ensureSavedMessagesChat { chatId ->
            scope.launch {
                val vFolders = currentManifest.virtualFolders.values.filter { it.id != SAFE_SPACE_VIRTUAL_ID }
                val entities = vFolders.map { vf ->
                    val existing = driveDao.getVirtualFolderById(vf.id)
                    val finalVirtualParentId = if (vf.parentId.startsWith("vf_")) vf.parentId else "0"
                    val deterministicId = vf.id.replace("vf_", "").filter { it.isDigit() }.toLongOrNull() 
                        ?: (vf.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)

                    DriveItemEntity(
                        existing?.id ?: deterministicId, vf.name, 0, "virtual_folder", 0, chatId, true,
                        isVirtual = true, virtualId = vf.id, virtualParentId = finalVirtualParentId,
                        createdAt = vf.createdAt * 1000, isStarred = existing?.isStarred ?: false, isSecure = vf.isSecure
                    )
                }
                driveDao.syncVirtualFolders(entities)
            }
        }
    }

    fun createVirtualFolder(name: String, parentId: String = "0", isSecure: Boolean = false) {
        val id = "vf_${System.nanoTime()}"
        val folder = VirtualFolder(id, name, parentId, System.currentTimeMillis() / 1000, "virtual_folder", isSecure)
        currentManifest = currentManifest.copy(virtualFolders = currentManifest.virtualFolders + (id to folder))
        syncVirtualFoldersToDb()
        
        ensureSavedMessagesChat { chatId ->
            val text = "$VFOLDER_META_PREFIX\n\n${json.encodeToString(folder)}"
            telegramClient.send(TdApi.SendMessage(chatId, null, null, null, null, TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true))) { result ->
                if (result is TdApi.Message) {
                    synchronized(currentManifest) {
                        currentManifest = currentManifest.copy(folderMetadataIds = currentManifest.folderMetadataIds + (id to result.id))
                        saveCloudManifest()
                    }
                }
            }
        }
    }

    private fun saveCloudManifest() {
        ensureSavedMessagesChat { chatId ->
            val text = "$MANIFEST_PREFIX\n\n${json.encodeToString(currentManifest)}"
            val formatted = TdApi.FormattedText(text, emptyArray())
            if (manifestMessageId != 0L) {
                telegramClient.send(TdApi.EditMessageText(chatId, manifestMessageId, null, TdApi.InputMessageText(formatted, null, true)))
            } else {
                telegramClient.send(TdApi.SendMessage(chatId, null, null, null, null, TdApi.InputMessageText(formatted, null, true))) { result ->
                    if (result is TdApi.Message) manifestMessageId = result.id
                }
            }
        }
    }

    fun getAllFiles(): Flow<List<DriveItem.File>> {
        return driveDao.getAllFiles().map { entities ->
            entities.filter { it.virtualParentId != SAFE_SPACE_VIRTUAL_ID }
                .map { entity ->
                    DriveItem.File(
                        entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType,
                        entity.telegramFileId, verifyOfflinePath(entity.thumbnailPath), verifyOfflinePath(entity.localPath), entity.isStarred,
                        entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted
                    )
                }
        }
    }

    fun getStarredItems(): Flow<List<DriveItem>> {
        return driveDao.getStarredItems().map { entities ->
            entities.filter { (it.isVirtual || !it.isFolder) && it.virtualParentId != SAFE_SPACE_VIRTUAL_ID }.map { entity ->
                if (entity.isFolder) {
                    DriveItem.Folder(entity.id, entity.parentChatId, entity.name, entity.id, entity.isStarred, entity.isVirtual, entity.virtualId, entity.virtualParentId, entity.isSecure)
                } else {
                    DriveItem.File(entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType, entity.telegramFileId, verifyOfflinePath(entity.thumbnailPath), verifyOfflinePath(entity.localPath), entity.isStarred, entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted)
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    fun getItems(chatId: Long?, virtualParentId: String = "0", searchQuery: String = ""): Flow<List<DriveItem>> {
        val targetChatId = savedMessagesChatId
        val flow = if (searchQuery.isNotEmpty()) driveDao.searchGlobal(searchQuery)
        else if (targetChatId != 0L) driveDao.getItemsFlow(targetChatId, virtualParentId)
        else kotlinx.coroutines.flow.flowOf(emptyList())

        return flow.map { entities ->
            entities.filter { (!it.isFolder || it.isVirtual) && (virtualParentId == SAFE_SPACE_VIRTUAL_ID || it.virtualParentId != SAFE_SPACE_VIRTUAL_ID) }
                .sortedByDescending { it.createdAt }
                .map { entity ->
                    if (entity.isFolder) DriveItem.Folder(entity.id, entity.parentChatId, entity.name, entity.id, entity.isStarred, entity.isVirtual, entity.virtualId, entity.virtualParentId, entity.isSecure)
                    else DriveItem.File(entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType, entity.telegramFileId, verifyOfflinePath(entity.thumbnailPath), verifyOfflinePath(entity.localPath), entity.isStarred, entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted)
                }
        }.debounce(40)
    }

    fun fetchFiles(chatId: Long? = null) {
        ensureSavedMessagesChat { resolvedChatId ->
            telegramClient.send(TdApi.LoadChats(TdApi.ChatListMain(), 20)) {
                fetchCloudManifest {
                    fetchHistoryRecursively(resolvedChatId, 0, mutableListOf())
                }
            }
        }
    }

    private fun fetchHistoryRecursively(chatId: Long, fromMessageId: Long, allFetchedIds: MutableList<Long>) {
        telegramClient.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, 50, false)) { result ->
            if (result is TdApi.Messages) {
                if (result.messages.isEmpty()) {
                    if (fromMessageId != 0L) {
                        scope.launch { driveDao.deleteFilesByChatNotInList(chatId, allFetchedIds) }
                    }
                    return@send
                }

                val entities = result.messages.mapNotNull { message ->
                    if (message.sendingState != null) null else mapMessageToEntity(chatId, message)
                }
                
                allFetchedIds.addAll(entities.map { it.id })
                scope.launch { driveDao.insertItemsPreservingStarred(entities) }

                val lastMsgId = result.messages.last().id
                if (allFetchedIds.size < 2000 && lastMsgId != fromMessageId) {
                    fetchHistoryRecursively(chatId, lastMsgId, allFetchedIds)
                } else {
                    scope.launch { driveDao.deleteFilesByChatNotInList(chatId, allFetchedIds) }
                }
            }
        }
    }

    private fun mapMessageToEntity(chatId: Long, message: TdApi.Message): DriveItemEntity? {
        val isSecureFolder = currentManifest.secureFolderChatIds.contains(chatId)
        val content = message.content
        
        if (content is TdApi.MessageText && chatId == savedMessagesChatId) {
            val text = content.text.text
            if (text.startsWith(VFOLDER_META_PREFIX)) {
                try {
                    val jsonStartIndex = text.indexOf('{')
                    if (jsonStartIndex != -1) {
                        val vf = json.decodeFromString<VirtualFolder>(text.substring(jsonStartIndex))
                        val deletedAt = currentManifest.deletedFolderIds[vf.id]
                        if (deletedAt != null && vf.createdAt <= deletedAt) return null
                        currentManifest = currentManifest.copy(
                            virtualFolders = currentManifest.virtualFolders + (vf.id to vf),
                            folderMetadataIds = currentManifest.folderMetadataIds + (vf.id to message.id)
                        )
                        syncVirtualFoldersToDb()
                    }
                } catch (e: Exception) { }
            } else if (text.startsWith(FILE_META_PREFIX)) {
                try {
                    val jsonStartIndex = text.indexOf('{')
                    if (jsonStartIndex != -1) {
                        val jsonStr = text.substring(jsonStartIndex)
                        if (jsonStr.contains("partMessageIds")) {
                            val master = json.decodeFromString<SplitFileMaster>(jsonStr)
                            currentManifest = currentManifest.copy(splitFileMasters = currentManifest.splitFileMasters + (master.groupId to master.copy(metadataMessageId = message.id)))
                        } else {
                            val meta = json.decodeFromString<FileMetadata>(jsonStr)
                            currentManifest = currentManifest.copy(
                                fileMappings = currentManifest.fileMappings + (meta.messageId.toString() to (meta.virtualFolderId ?: "0")),
                                fileMetadataIds = currentManifest.fileMetadataIds + (meta.messageId.toString() to message.id)
                            )
                            scope.launch { driveDao.updateVirtualParent(meta.messageId, meta.virtualFolderId ?: "0") }
                        }
                    }
                } catch (e: Exception) { }
            }
        }

        return when (content) {
            is TdApi.MessageDocument -> {
                val caption = content.caption.text
                val isEncrypted = caption.contains("[ENC]") || isSecureFolder
                val splitInfo = parseSplitMetadata(caption)
                val cleanName = cleanEncryptedFileName(splitInfo?.originalName ?: content.document.fileName)
                val mimeType = if (content.document.mimeType == "application/octet-stream") FileUtils.getMimeType(cleanName) else content.document.mimeType
                val docFile = content.document.document
                val thumb = content.document.thumbnail

                if (thumb != null && thumb.file.local.path.isEmpty() && thumb.file.local.canBeDownloaded) {
                    telegramClient.send(TdApi.DownloadFile(thumb.file.id, 32, 0, 0, false))
                } else if (thumb == null && !isEncrypted && mimeType.startsWith("image/") && docFile.local.path.isEmpty() && docFile.expectedSize < 15 * 1024 * 1024L) {
                    telegramClient.send(TdApi.DownloadFile(docFile.id, 16, 0, 0, false))
                }

                DriveItemEntity(
                    message.id, cleanName, docFile.expectedSize, mimeType, docFile.id, chatId, false,
                    thumbnailPath = thumb?.file?.local?.path?.takeIf { it.isNotEmpty() } ?: (if (mimeType.startsWith("image/")) docFile.local.path.takeIf { it.isNotEmpty() } else null),
                    localPath = docFile.local.path.takeIf { it.isNotEmpty() && docFile.local.isDownloadingCompleted },
                    createdAt = message.date.toLong() * 1000,
                    thumbnailFileId = thumb?.file?.id,
                    remoteUniqueId = docFile.remote.uniqueId,
                    thumbnailRemoteUniqueId = thumb?.file?.remote?.uniqueId,
                    splitGroupId = splitInfo?.groupId,
                    partIndex = splitInfo?.partIndex ?: 0,
                    totalParts = splitInfo?.totalParts ?: 1,
                    virtualParentId = getAndEnsureVirtualFolder(message.id, caption),
                    isSecure = isSecureFolder,
                    isEncrypted = isEncrypted
                )
            }
            is TdApi.MessagePhoto -> {
                val caption = content.caption.text
                val isEncrypted = caption.contains("[ENC]") || isSecureFolder
                val sizes = content.photo.sizes
                val thumb = sizes.firstOrNull()
                val full = sizes.lastOrNull()?.photo
                if (thumb != null && thumb.photo.local.path.isEmpty() && thumb.photo.local.canBeDownloaded) {
                    telegramClient.send(TdApi.DownloadFile(thumb.photo.id, 32, 0, 0, false))
                }
                DriveItemEntity(
                    message.id, "Photo_${message.id}.jpg", full?.expectedSize ?: 0L, "image/jpeg", full?.id ?: 0, chatId, false,
                    thumbnailPath = thumb?.photo?.local?.path?.takeIf { it.isNotEmpty() },
                    localPath = full?.local?.path?.takeIf { it.isNotEmpty() && full.local.isDownloadingCompleted },
                    createdAt = message.date.toLong() * 1000,
                    thumbnailFileId = thumb?.photo?.id,
                    remoteUniqueId = full?.remote?.uniqueId,
                    thumbnailRemoteUniqueId = thumb?.photo?.remote?.uniqueId,
                    virtualParentId = getAndEnsureVirtualFolder(message.id, caption),
                    isSecure = isSecureFolder,
                    isEncrypted = isEncrypted
                )
            }
            is TdApi.MessageVideo -> {
                val caption = content.caption.text
                val isEncrypted = caption.contains("[ENC]") || isSecureFolder
                val splitInfo = parseSplitMetadata(caption)
                val cleanName = cleanEncryptedFileName(splitInfo?.originalName ?: content.video.fileName)
                val mimeType = if (content.video.mimeType == "application/octet-stream") FileUtils.getMimeType(cleanName) else content.video.mimeType
                val thumb = content.video.thumbnail
                if (thumb != null && thumb.file.local.path.isEmpty() && thumb.file.local.canBeDownloaded) {
                    telegramClient.send(TdApi.DownloadFile(thumb.file.id, 32, 0, 0, false))
                }
                DriveItemEntity(
                    message.id, cleanName, content.video.video.expectedSize, mimeType, content.video.video.id, chatId, false,
                    thumbnailPath = thumb?.file?.local?.path?.takeIf { it.isNotEmpty() },
                    localPath = content.video.video.local.path.takeIf { it.isNotEmpty() && content.video.video.local.isDownloadingCompleted },
                    createdAt = message.date.toLong() * 1000,
                    thumbnailFileId = thumb?.file?.id,
                    remoteUniqueId = content.video.video.remote.uniqueId,
                    thumbnailRemoteUniqueId = thumb?.file?.remote?.uniqueId,
                    splitGroupId = splitInfo?.groupId,
                    partIndex = splitInfo?.partIndex ?: 0,
                    totalParts = splitInfo?.totalParts ?: 1,
                    virtualParentId = getAndEnsureVirtualFolder(message.id, caption),
                    isSecure = isSecureFolder,
                    isEncrypted = isEncrypted
                )
            }
            is TdApi.MessageAudio -> {
                val caption = content.caption.text
                val isEncrypted = caption.contains("[ENC]") || isSecureFolder
                val splitInfo = parseSplitMetadata(caption)
                val cleanName = cleanEncryptedFileName(splitInfo?.originalName ?: content.audio.fileName)
                val mimeType = if (content.audio.mimeType == "application/octet-stream" || content.audio.mimeType.isEmpty()) FileUtils.getMimeType(cleanName) else content.audio.mimeType
                DriveItemEntity(
                    message.id, cleanName, content.audio.audio.expectedSize, mimeType, content.audio.audio.id, chatId, false,
                    localPath = content.audio.audio.local.path.takeIf { it.isNotEmpty() && content.audio.audio.local.isDownloadingCompleted },
                    createdAt = message.date.toLong() * 1000,
                    splitGroupId = splitInfo?.groupId,
                    partIndex = splitInfo?.partIndex ?: 0,
                    totalParts = splitInfo?.totalParts ?: 1,
                    virtualParentId = getAndEnsureVirtualFolder(message.id, caption),
                    isSecure = isSecureFolder,
                    isEncrypted = isEncrypted,
                    remoteUniqueId = content.audio.audio.remote.uniqueId
                )
            }
            is TdApi.MessageAnimation -> {
                val caption = content.caption.text
                val isEncrypted = caption.contains("[ENC]") || isSecureFolder
                val thumb = content.animation.thumbnail
                if (thumb != null && thumb.file.local.path.isEmpty() && thumb.file.local.canBeDownloaded) {
                    telegramClient.send(TdApi.DownloadFile(thumb.file.id, 32, 0, 0, false))
                }
                DriveItemEntity(
                    message.id, cleanEncryptedFileName("Animation_${message.id}.mp4"), content.animation.animation.expectedSize, "video/mp4", content.animation.animation.id, chatId, false,
                    thumbnailPath = thumb?.file?.local?.path?.takeIf { it.isNotEmpty() },
                    localPath = content.animation.animation.local.path.takeIf { it.isNotEmpty() && content.animation.animation.local.isDownloadingCompleted },
                    createdAt = message.date.toLong() * 1000,
                    thumbnailFileId = thumb?.file?.id,
                    remoteUniqueId = content.animation.animation.remote.uniqueId,
                    thumbnailRemoteUniqueId = thumb?.file?.remote?.uniqueId,
                    virtualParentId = getAndEnsureVirtualFolder(message.id, caption),
                    isSecure = isSecureFolder,
                    isEncrypted = isEncrypted
                )
            }
            else -> null
        }
    }

    private fun getAndEnsureVirtualFolder(messageId: Long, caption: String): String {
        val mappedId = currentManifest.fileMappings[messageId.toString()]
        val tagId = parseVirtualFolderTag(caption)
        val finalId = mappedId ?: tagId ?: "0"
        if (finalId == SAFE_SPACE_VIRTUAL_ID) return SAFE_SPACE_VIRTUAL_ID
        
        if (finalId != "0" && !currentManifest.virtualFolders.containsKey(finalId)) {
            if (currentManifest.deletedFolderIds.containsKey(finalId)) return "0"
            val newFolder = VirtualFolder(id = finalId, name = "Folder_${finalId.takeLast(4)}", parentId = "0", createdAt = System.currentTimeMillis() / 1000)
            currentManifest = currentManifest.copy(
                virtualFolders = currentManifest.virtualFolders + (finalId to newFolder),
                fileMappings = if (tagId != null) currentManifest.fileMappings + (messageId.toString() to tagId) else currentManifest.fileMappings
            )
            syncVirtualFoldersToDb()
            saveCloudManifest()
        }
        return finalId
    }

    fun createFolder(name: String) = createVirtualFolder(name, "0", isSecure = false)
    fun createSecureFolder(name: String) = createVirtualFolder(name, "0", isSecure = true)

    fun uploadFile(
        filePath: String,
        originalFileName: String,
        chatId: Long? = null,
        virtualFolderId: String? = null,
        forceDeleteSource: Boolean = false,
        backupTrackingKey: String? = null
    ) {
        ensureSavedMessagesChat { targetChatId ->
            val customPrefs = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
            val autoDeleteEnabled = forceDeleteSource || customPrefs.getBoolean("delete_after_backup", false)
            val cleanName = cleanEncryptedFileName(originalFileName)

            val vfTag = if (virtualFolderId != null && virtualFolderId != "0") "[VF:$virtualFolderId]" else ""
            val sourceFile = File(filePath)
            if (!sourceFile.exists()) return@ensureSavedMessagesChat

            if (sourceFile.length() > FileUtils.MAX_FILE_SIZE) {
                if (!FileUtils.hasEnoughSpace(context, sourceFile.length())) return@ensureSavedMessagesChat
                startTransferService()
                scope.launch {
                    val placeholderId = "prep_${System.currentTimeMillis()}"
                    transferRepository.addTransfer(0, placeholderId, cleanName, false, sourceFile.length(), status = context.getString(R.string.splitting_file, ""))
                    try {
                        val parts = FileUtils.splitFile(context, sourceFile) { progress ->
                            transferRepository.updateTransferManual(placeholderId, progress, context.getString(R.string.splitting_file_perc, (progress * 100).toInt()))
                        }
                        transferRepository.removeTransfer(placeholderId)
                        val groupId = FileUtils.generateSplitGroupId()
                        val partMessageIds = mutableListOf<Long>()
                        
                        parts.forEachIndexed { index, part ->
                            val caption = "[TD_SPLIT|ID:$groupId|PART:$index/${parts.size}|NAME:$cleanName]$vfTag"
                            uploadSinglePart(part.absolutePath, cleanName, targetChatId, caption, virtualFolderId, deleteSourceAfter = true) { messageId ->
                                if (messageId != 0L) partMessageIds.add(messageId)
                                if (index == parts.size - 1) {
                                    sendSplitMasterMetadata(groupId, cleanName, sourceFile.length(), FileUtils.getMimeType(cleanName), parts.size, virtualFolderId, isChatSecure(targetChatId), partMessageIds)
                                    if (backupTrackingKey != null) markFileBackedUp(backupTrackingKey)
                                    if (autoDeleteEnabled) deletePhoneFileCompletely(sourceFile)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        transferRepository.updateTransferManual(placeholderId, 0f, TransferRepository.Status.FAILED)
                    }
                }
            } else {
                uploadSinglePart(
                    filePath = filePath,
                    originalFileName = cleanName,
                    targetChatId = targetChatId,
                    caption = if (vfTag.isNotEmpty()) "$cleanName $vfTag" else cleanName,
                    virtualFolderId = virtualFolderId,
                    deleteSourceAfter = autoDeleteEnabled,
                    backupTrackingKey = backupTrackingKey
                )
            }
        }
    }

    private fun generateUploadThumbnail(sourceFile: File, fileName: String): Pair<TdApi.InputThumbnail, File>? {
        return try {
            val mime = FileUtils.getMimeType(fileName)
            val bitmap: Bitmap? = when {
                mime.startsWith("image/") -> {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(sourceFile.absolutePath, opts)
                    var sampleSize = 1
                    while (opts.outWidth / sampleSize > 320 || opts.outHeight / sampleSize > 320) sampleSize *= 2
                    BitmapFactory.decodeFile(sourceFile.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                }
                mime.startsWith("video/") -> {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(sourceFile.absolutePath)
                    val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.frameAtTime
                    retriever.release()
                    frame
                }
                else -> null
            }

            if (bitmap != null) {
                val maxDim = 320f
                val ratio = minOf(maxDim / bitmap.width.coerceAtLeast(1), maxDim / bitmap.height.coerceAtLeast(1), 1f)
                val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
                val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)

                val thumbFile = File(context.cacheDir, "thumb_${System.nanoTime()}.jpg")
                FileOutputStream(thumbFile).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                TdApi.InputThumbnail(TdApi.InputFileLocal(thumbFile.absolutePath), w, h) to thumbFile
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun uploadSinglePart(
        filePath: String,
        originalFileName: String,
        targetChatId: Long,
        caption: String? = null,
        virtualFolderId: String? = null,
        deleteSourceAfter: Boolean = false,
        backupTrackingKey: String? = null,
        onMessageSent: (Long) -> Unit = {}
    ) {
        startTransferService()
        val isSecure = isChatSecure(targetChatId) || (virtualFolderId != null && isVirtualFolderSecure(virtualFolderId))
        val password = secureSessionManager.decryptedPassword.value
        if (isSecure && password == null) {
            scope.launch(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.unlock_to_upload), Toast.LENGTH_LONG).show() }
            return
        }

        val tempId = "up_${System.nanoTime()}"
        val originalSourceFile = File(filePath)
        val isTempCacheFile = filePath.startsWith(context.cacheDir.absolutePath)

        transferRepository.enqueueTransfer(
            fileId = 0,
            remoteUniqueId = tempId,
            fileName = originalFileName,
            isDownload = false,
            totalSize = originalSourceFile.length(),
            localPath = filePath
        ) {
            var uploadReadyFile = originalSourceFile
            var tempCopyFile: File? = null
            if (!isTempCacheFile && !originalSourceFile.canRead()) {
                try {
                    val copyTarget = File(context.cacheDir, "send_${System.nanoTime()}_$originalFileName")
                    originalSourceFile.inputStream().use { input ->
                        FileOutputStream(copyTarget).use { output ->
                            input.copyTo(output, bufferSize = 256 * 1024)
                        }
                    }
                    if (copyTarget.exists() && copyTarget.length() > 0) {
                        uploadReadyFile = copyTarget
                        tempCopyFile = copyTarget
                    }
                } catch (e: Exception) {}
            }

            var finalPath = uploadReadyFile.absolutePath
            var finalCaption = caption ?: originalFileName
            var tempEncFile: File? = null
            var tempThumbFile: File? = null
            var inputThumb: TdApi.InputThumbnail? = null
            
            if (isSecure && password != null) {
                try {
                    tempEncFile = File(context.cacheDir, "enc_${System.nanoTime()}_$originalFileName")
                    encryptionManager.encryptFile(uploadReadyFile, tempEncFile, password) { progress ->
                        transferRepository.updateTransferManual(tempId, progress, context.getString(R.string.encrypting_perc, (progress * 100).toInt()), throttled = true)
                    }
                    finalPath = tempEncFile.absolutePath
                    finalCaption = if (caption != null) "$caption [ENC]" else "$originalFileName [ENC]"
                    transferRepository.updateRemoteUniqueId(tempId, tempId, 0, finalPath)
                } catch (e: Exception) {
                    transferRepository.updateTransferManual(tempId, 0f, TransferRepository.Status.FAILED)
                    return@enqueueTransfer 
                }
            } else {
                generateUploadThumbnail(uploadReadyFile, originalFileName)?.let { (thumbObj, tFile) ->
                    inputThumb = thumbObj
                    tempThumbFile = tFile
                }
            }

            telegramClient.send(
                TdApi.SendMessage(
                    targetChatId, null, null, null, null,
                    TdApi.InputMessageDocument(TdApi.InputFileLocal(finalPath), inputThumb, false, TdApi.FormattedText(finalCaption, emptyArray()))
                )
            ) { result ->
                if (result is TdApi.Message) {
                    onMessageSent(result.id)
                    pendingUploadTempIds[result.id] = tempId
                    if (backupTrackingKey != null) {
                        pendingBackupKeys[result.id] = backupTrackingKey
                    }
                    if (deleteSourceAfter && !isTempCacheFile) {
                        pendingAutoDeleteFiles[result.id] = originalSourceFile
                    }

                    val file = extractTdFileFromMessage(result)
                    if (file != null) {
                        transferRepository.updateRemoteUniqueId(tempId, file.remote.uniqueId, file.id, finalPath)
                        if (file.remote.isUploadingCompleted) {
                            transferRepository.completeAndCleanupTransfer(file.remote.uniqueId, tempId, file.id, success = true)
                            if (backupTrackingKey != null) {
                                pendingBackupKeys.remove(result.id)
                                markFileBackedUp(backupTrackingKey)
                            }
                            if (deleteSourceAfter && !isTempCacheFile) {
                                pendingAutoDeleteFiles.remove(result.id)
                                deletePhoneFileCompletely(originalSourceFile)
                            }
                        }
                        if (!finalCaption.contains("[TD_SPLIT|")) {
                            sendFileMetadata(result.id, originalFileName, virtualFolderId, isSecure)
                        }
                    } else {
                        transferRepository.completeAndCleanupTransfer(tempId, success = false)
                    }
                } else {
                    transferRepository.completeAndCleanupTransfer(tempId, success = false)
                }
            }
            
            val completedSuccess = transferRepository.waitForCompletion(tempId)
            
            try {
                tempEncFile?.let { if (it.exists()) it.delete() }
                tempThumbFile?.let { if (it.exists()) it.delete() }
                tempCopyFile?.let { if (it.exists()) it.delete() }
                if (isTempCacheFile) {
                    if (originalSourceFile.exists()) originalSourceFile.delete()
                } else if (completedSuccess && deleteSourceAfter) {
                    deletePhoneFileCompletely(originalSourceFile)
                }
            } catch (e: Exception) {}
        }
    }

    private fun deletePhoneFileCompletely(file: File) {
        val path = file.absolutePath
        try { if (file.exists()) file.delete() } catch (e: Exception) {}
        try {
            val externalUri = MediaStore.Files.getContentUri("external")
            context.contentResolver.delete(externalUri, "${MediaStore.MediaColumns.DATA}=?", arrayOf(path))
        } catch (e: Exception) {}
        try { MediaScannerConnection.scanFile(context, arrayOf(path), null, null) } catch (e: Exception) {}
    }

    private fun sendFileMetadata(messageId: Long, fileName: String, virtualFolderId: String?, isEncrypted: Boolean) {
        ensureSavedMessagesChat { chatId ->
            val meta = FileMetadata(messageId, fileName, virtualFolderId, isEncrypted)
            val text = "$FILE_META_PREFIX\n\n${json.encodeToString(meta)}"
            telegramClient.send(TdApi.SendMessage(chatId, null, null, null, null, TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true))) { result ->
                if (result is TdApi.Message) {
                    currentManifest = currentManifest.copy(fileMetadataIds = currentManifest.fileMetadataIds + (messageId.toString() to result.id))
                }
            }
        }
    }

    private fun sendSplitMasterMetadata(groupId: String, name: String, size: Long, mime: String, parts: Int, vfId: String?, enc: Boolean, ids: List<Long>) {
        ensureSavedMessagesChat { chatId ->
            val master = SplitFileMaster(groupId, name, size, mime, parts, vfId, enc, ids)
            val text = "$FILE_META_PREFIX\n\n${json.encodeToString(master)}"
            telegramClient.send(TdApi.SendMessage(chatId, null, null, null, null, TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true))) { result ->
                val finalMaster = if (result is TdApi.Message) master.copy(metadataMessageId = result.id) else master
                currentManifest = currentManifest.copy(splitFileMasters = currentManifest.splitFileMasters + (groupId to finalMaster))
                saveCloudManifest()
            }
        }
    }

    // ইন-অ্যাপ অফলাইন ডাউনলোড: এটি ফাইলকে অ্যাপের ভেতরে অফলাইনে চলার জন্য ডাউনলোড করবে (ফোন মেমোরিতে পাঠাবে না)
    fun downloadFile(messageId: Long, chatId: Long, fileName: String) {
        scope.launch {
            val cachedEntity = driveDao.getItemById(messageId, chatId)
            val existingOfflineFile = verifyOfflinePath(cachedEntity?.localPath)?.let { File(it) }
            if (existingOfflineFile != null) {
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "ফাইলটি ইতিমধ্যে অফলাইনে সংরক্ষিত আছে: $fileName", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            startTransferService()
            if (cachedEntity != null && cachedEntity.telegramFileId != 0) {
                transferRepository.enqueueTransfer(
                    fileId = cachedEntity.telegramFileId,
                    remoteUniqueId = cachedEntity.remoteUniqueId ?: "dl_${cachedEntity.telegramFileId}",
                    fileName = fileName,
                    isDownload = true,
                    totalSize = cachedEntity.size,
                    localPath = null
                ) {
                    telegramClient.send(TdApi.DownloadFile(cachedEntity.telegramFileId, 32, 0, 0, false))
                }
            }

            telegramClient.send(TdApi.GetMessage(chatId, messageId)) { result ->
                if (result is TdApi.Message) {
                    val file = extractTdFileFromMessage(result)
                    if (file != null && file.id != 0) {
                        if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty() && File(file.local.path).exists()) {
                            scope.launch {
                                driveDao.updateLocalPath(file.id, file.local.path)
                                if (file.remote.uniqueId.isNotEmpty()) {
                                    driveDao.updateLocalPathByUniqueId(file.remote.uniqueId, file.local.path)
                                }
                            }
                            return@send
                        }
                        if (cachedEntity == null || cachedEntity.telegramFileId != file.id) {
                            transferRepository.enqueueTransfer(
                                fileId = file.id,
                                remoteUniqueId = file.remote.uniqueId.ifEmpty { "dl_${file.id}" },
                                fileName = fileName,
                                isDownload = true,
                                totalSize = file.expectedSize.takeIf { it > 0 } ?: file.size,
                                localPath = null
                            ) {
                                telegramClient.send(TdApi.DownloadFile(file.id, 32, 0, 0, false))
                            }
                        } else {
                            telegramClient.send(TdApi.DownloadFile(file.id, 32, 0, 0, false))
                        }
                    }
                }
            }
        }
    }

    // ইনস্ট্যান্ট প্রিভিউ ও স্ট্রিমিংয়ের জন্য (অফলাইনে থাকলে সাথে সাথে ওপেন হবে, না থাকলে ক্লাউড থেকে স্ট্রিম করবে)
    fun downloadForPreview(messageId: Long, chatId: Long, fileName: String) {
        scope.launch {
            val cachedEntity = driveDao.getItemById(messageId, chatId)
            if (verifyOfflinePath(cachedEntity?.localPath) != null) return@launch

            if (cachedEntity != null && cachedEntity.telegramFileId != 0) {
                telegramClient.send(TdApi.DownloadFile(cachedEntity.telegramFileId, 32, 0, 0, false))
            }

            telegramClient.send(TdApi.GetMessage(chatId, messageId)) { result ->
                if (result is TdApi.Message) {
                    val file = extractTdFileFromMessage(result)
                    if (file != null && file.id != 0) {
                        if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) {
                            scope.launch { driveDao.updateLocalPath(file.id, file.local.path) }
                        } else {
                            telegramClient.send(TdApi.DownloadFile(file.id, 32, 0, 0, false))
                        }
                    }
                }
            }
        }
    }

    // ইউজার যখন নিজে "Save to Phone Storage" চাপবে তখনই কেবল ফোনের Downloads ফোল্ডারে সেভ হবে
    fun saveToPublicStorage(file: DriveItem.File) {
        val source = verifyOfflinePath(file.localPath)?.let { File(it) }
        if (source == null) {
            Toast.makeText(context, "আগে ফাইলটি অফলাইনে ডাউনলোড হতে দিন", Toast.LENGTH_SHORT).show()
            return
        }
        if (file.isEncrypted) {
            scope.launch(Dispatchers.IO) {
                try {
                    val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MyCloud")
                    if (!downloadDir.exists()) downloadDir.mkdirs()
                    val dest = File(downloadDir, file.name)
                    val password = secureSessionManager.decryptedPassword.value
                    if (password != null) encryptionManager.decryptFile(source, dest, password) { }
                    scope.launch(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.save_success), Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) { }
            }
        } else {
            transferRepository.saveToPublicDownloads(source.absolutePath, file.name)
        }
    }

    fun toggleStarred(item: DriveItem) {
        scope.launch { driveDao.updateStarred(item.id, item.parentChatId, !item.isStarred) }
    }

    fun renameItem(item: DriveItem, newName: String) {
        scope.launch {
            if (item is DriveItem.Folder && item.isVirtual) {
                val virtualId = item.virtualId ?: return@launch
                val folder = currentManifest.virtualFolders[virtualId] ?: return@launch
                currentManifest = currentManifest.copy(virtualFolders = currentManifest.virtualFolders + (virtualId to folder.copy(name = newName)))
                saveCloudManifest()
            } else if (item is DriveItem.File) {
                telegramClient.send(TdApi.GetMessage(item.parentChatId, item.id)) { result ->
                    if (result is TdApi.Message) {
                        telegramClient.send(TdApi.EditMessageCaption(item.parentChatId, item.id, null, TdApi.FormattedText(newName, emptyArray()), false)) { editResult ->
                            if (editResult is TdApi.Message) scope.launch { driveDao.renameItem(item.id, item.parentChatId, newName) }
                        }
                    }
                }
            }
            driveDao.renameItem(item.id, item.parentChatId, newName)
        }
    }

    fun permanentlyDeleteItems(items: List<DriveItem>) {
        scope.launch {
            val filesByChat = items.filterIsInstance<DriveItem.File>().groupBy { it.parentChatId }
            filesByChat.forEach { (chatId, files) ->
                val ids = files.map { it.id }.toLongArray()
                telegramClient.send(TdApi.DeleteMessages(chatId, ids, true)) {
                    scope.launch { files.forEach { f -> driveDao.deleteItemCompletely(f.id, chatId) } }
                }
            }

            if (filesByChat.isNotEmpty()) {
                synchronized(currentManifest) {
                    val updatedFileMetaIds = currentManifest.fileMetadataIds.toMutableMap()
                    val updatedFileMappings = currentManifest.fileMappings.toMutableMap()
                    val updatedSplitMasters = currentManifest.splitFileMasters.toMutableMap()
                    val metadataIdsToDelete = mutableListOf<Long>()

                    items.filterIsInstance<DriveItem.File>().forEach { file ->
                        updatedFileMetaIds.remove(file.id.toString())?.let { metadataIdsToDelete.add(it) }
                        updatedFileMappings.remove(file.id.toString())
                        file.splitGroupId?.let { gid ->
                            updatedSplitMasters.remove(gid)?.metadataMessageId?.let { metadataIdsToDelete.add(it) }
                        }
                    }

                    currentManifest = currentManifest.copy(
                        fileMetadataIds = updatedFileMetaIds,
                        fileMappings = updatedFileMappings,
                        splitFileMasters = updatedSplitMasters,
                        updatedAt = System.currentTimeMillis() / 1000
                    )
                    if (metadataIdsToDelete.isNotEmpty() && savedMessagesChatId != 0L) {
                        telegramClient.send(TdApi.DeleteMessages(savedMessagesChatId, metadataIdsToDelete.toLongArray(), true)) {}
                    }
                }
                saveCloudManifest()
            }

            val virtualFolders = items.filterIsInstance<DriveItem.Folder>().filter { it.isVirtual }
            if (virtualFolders.isNotEmpty()) {
                virtualFolders.forEach { folder -> folder.virtualId?.let { vid -> deleteVirtualFolderRecursive(vid) } }
                saveCloudManifest()
            }
        }
    }

    private suspend fun deleteVirtualFolderRecursive(virtualId: String) {
        val children = driveDao.getItemsByVirtualParentSync(virtualId)
        val filesByChat = children.filter { !it.isFolder }.groupBy { it.parentChatId }
        filesByChat.forEach { (chatId, entities) ->
            telegramClient.send(TdApi.DeleteMessages(chatId, entities.map { it.id }.toLongArray(), true)) {}
        }
        val entity = driveDao.getVirtualFolderById(virtualId)
        if (entity != null) driveDao.deleteItemCompletely(entity.id, entity.parentChatId)
        children.forEach { child -> driveDao.deleteItemCompletely(child.id, child.parentChatId) }
    }

    fun downloadFolderContents(folder: DriveItem.Folder) {
        if (folder.isVirtual) folder.virtualId?.let { downloadVirtualFolderRecursive(it) }
    }

    private fun downloadVirtualFolderRecursive(virtualId: String) {
        scope.launch {
            val children = driveDao.getItemsByVirtualParentSync(virtualId)
            children.forEach { child ->
                if (child.isFolder && child.isVirtual) child.virtualId?.let { downloadVirtualFolderRecursive(it) }
                else if (!child.isFolder) downloadFile(child.id, child.parentChatId, child.name)
            }
        }
    }

    fun moveItems(fromChatId: Long, toChatId: Long, messageIds: List<Long>) { }
    fun moveFolderContentsAndDelete(fromFolderChatId: Long, toChatId: Long) { }

    fun getInternalCacheSize(): Flow<Long> = kotlinx.coroutines.flow.flow {
        emit(calculateTotalStorage())
        while (true) {
            delay(10000)
            emit(calculateTotalStorage())
        }
    }

    private fun calculateTotalStorage(): Long {
        return calculateDirectorySize(context.cacheDir) + calculateDirectorySize(context.filesDir) +
               (context.externalCacheDir?.let { calculateDirectorySize(it) } ?: 0L)
    }

    private fun calculateDirectorySize(directory: File?): Long {
        if (directory == null || !directory.exists()) return 0L
        var size: Long = 0
        try {
            val files = directory.listFiles() ?: return 0L
            for (file in files) size += if (file.isDirectory) calculateDirectorySize(file) else file.length()
        } catch (e: Exception) { }
        return size
    }

    fun clearDatabaseLocalPaths(onlyThumbnails: Boolean = false, onlyFiles: Boolean = false) {
        scope.launch {
            if (onlyThumbnails) driveDao.clearAllThumbnailPaths()
            else if (onlyFiles) driveDao.clearAllLocalPaths()
            else {
                driveDao.clearAllLocalPaths()
                driveDao.clearAllThumbnailPaths()
            }
        }
    }

    fun getCloudFileTypeStats(): Flow<List<DriveDao.FileTypeStat>> = driveDao.getCloudFileTypeStats()
    
    fun getTotalStorageUsed(): Flow<Long> = driveDao.getAllFiles().map { files ->
        var totalSize = 0L
        val processedGroups = mutableSetOf<String>()
        files.forEach { file ->
            if (file.splitGroupId == null) totalSize += file.size
            else if (!processedGroups.contains(file.splitGroupId)) {
                val master = currentManifest.splitFileMasters[file.splitGroupId]
                totalSize += master?.totalSize ?: (file.size * file.totalParts)
                processedGroups.add(file.splitGroupId)
            }
        }
        totalSize
    }

    fun isChatSecure(chatId: Long): Boolean = currentManifest.secureFolderChatIds.contains(chatId)
    fun isVirtualFolderSecure(virtualId: String): Boolean = currentManifest.virtualFolders[virtualId]?.isSecure == true
    fun getVirtualFolderName(virtualId: String): String? = currentManifest.virtualFolders[virtualId]?.name
    fun getParentVirtualId(virtualId: String): String = currentManifest.virtualFolders[virtualId]?.parentId ?: "0"
    fun getSavedMessagesChatId(): Long = savedMessagesChatId

    private fun startTransferService() {
        try {
            val intent = Intent(context, com.awd.teledrive.data.service.TransferService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (e: Exception) {}
    }

    private fun parseSplitMetadata(caption: String): SplitMetadata? {
        if (!caption.contains("[TD_SPLIT|")) return null
        val id = caption.substringAfter("ID:").substringBefore("|")
        val partInfo = caption.substringAfter("PART:").substringBefore("|")
        val partIndex = partInfo.substringBefore("/").toIntOrNull() ?: 0
        val totalParts = partInfo.substringAfter("/").toIntOrNull() ?: 1
        val originalName = caption.substringAfter("NAME:").substringBefore("]")
        return SplitMetadata(id, partIndex, totalParts, originalName)
    }

    private fun parseVirtualFolderTag(caption: String): String? {
        val startTag = "[VF:"
        val startIndex = caption.indexOf(startTag)
        if (startIndex == -1) return null
        val endIndex = caption.indexOf("]", startIndex)
        if (endIndex == -1) return null
        return caption.substring(startIndex + startTag.length, endIndex)
    }

    private fun cleanEncryptedFileName(fileName: String): String {
        return fileName
            .replace(Regex("^(enc|up|bkp|send)_\\d+_"), "")
            .replace(Regex("^(enc|up|bkp|send)_"), "")
    }

    private data class SplitMetadata(val groupId: String, val partIndex: Int, val totalParts: Int, val originalName: String)
}
