package com.awd.teledrive.data.repository

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.drinkless.tdlib.TdApi
import java.io.File
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

    fun getSavedMessagesChatIdFlow(): Flow<Long> = _savedMessagesChatIdFlow.asStateFlow()

    init {
        scope.launch {
            // অ্যাপ চালু হলে পুরনো জমে থাকা Completed / Cancelled ডাউনলোড ক্যাশ পরিষ্কার করা হবে
            transferRepository.clearCompleted()

            telegramClient.fileUpdates.collect { update ->
                val file = update.file
                if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) {
                    driveDao.updateLocalPathByUniqueId(file.remote.uniqueId, file.local.path)
                    driveDao.updateThumbnailPathByUniqueId(file.remote.uniqueId, file.local.path)
                }
            }
        }
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
                if (result is TdApi.Messages) {
                    synchronized(allMessages) { allMessages.addAll(result.messages.toList()) }
                }
                searchDone = true
                tryFinish()
            }

            telegramClient.send(TdApi.GetChatHistory(chatId, 0, 0, 100, false)) { result ->
                if (result is TdApi.Messages) {
                    synchronized(allMessages) { allMessages.addAll(result.messages.toList()) }
                }
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
                text.contains(MANIFEST_PREFIX) || 
                text.contains(MANIFEST_BACKUP_PREFIX) || 
                text.contains("\"virtualFolders\"")
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
                    } catch (e: Exception) { 
                        Log.e("DriveRepo", "Error decoding manifest: ${e.message}")
                    }
                }
            }
        }
        
        currentManifest = mergedManifest
        syncVirtualFoldersToDb()
    }

    private fun mergeManifests(base: TeleDriveManifest, other: TeleDriveManifest): TeleDriveManifest {
        val mergedDeletedIds = base.deletedFolderIds.toMutableMap()
        mergedDeletedIds.putAll(other.deletedFolderIds)

        val mergedFolders = base.virtualFolders.toMutableMap()
        other.virtualFolders.forEach { (id, vf) ->
            val deletedAt = mergedDeletedIds[id]
            if (deletedAt == null || vf.createdAt > deletedAt) {
                mergedFolders[id] = vf
            }
        }
        mergedFolders.keys.removeIf { id ->
            val deletedAt = mergedDeletedIds[id]
            deletedAt != null && (mergedFolders[id]?.createdAt ?: 0L) <= deletedAt
        }
        
        val mergedMappings = base.fileMappings.toMutableMap()
        mergedMappings.putAll(other.fileMappings)
        
        val mergedSecureIds = base.secureFolderChatIds.toMutableSet()
        mergedSecureIds.addAll(other.secureFolderChatIds)
        
        val mergedSplitMasters = base.splitFileMasters.toMutableMap()
        mergedSplitMasters.putAll(other.splitFileMasters)

        val mergedFolderMetaIds = base.folderMetadataIds.toMutableMap()
        mergedFolderMetaIds.putAll(other.folderMetadataIds)

        val mergedFileMetaIds = base.fileMetadataIds.toMutableMap()
        mergedFileMetaIds.putAll(other.fileMetadataIds)
        
        return base.copy(
            updatedAt = maxOf(base.updatedAt, other.updatedAt),
            virtualFolders = mergedFolders,
            fileMappings = mergedMappings,
            secureFolderChatIds = mergedSecureIds,
            splitFileMasters = mergedSplitMasters,
            folderMetadataIds = mergedFolderMetaIds,
            fileMetadataIds = mergedFileMetaIds,
            deletedFolderIds = mergedDeletedIds
        )
    }

    // ক্লাউডের নিজস্ব ফাইলগুলোকে Safe Folder-এ হাইড অথবা আনহাইড করার ফাংশন
    fun setCloudItemsSafeState(itemIds: List<Long>, hideInSafeFolder: Boolean) {
        scope.launch {
            val targetVirtualParent = if (hideInSafeFolder) SAFE_SPACE_VIRTUAL_ID else "0"
            val updatedMappings = currentManifest.fileMappings.toMutableMap()

            itemIds.forEach { id ->
                driveDao.updateVirtualParent(id, targetVirtualParent)
                updatedMappings[id.toString()] = targetVirtualParent
            }

            synchronized(currentManifest) {
                currentManifest = currentManifest.copy(
                    fileMappings = updatedMappings,
                    updatedAt = System.currentTimeMillis() / 1000
                )
            }
            saveCloudManifest()
        }
    }

    fun getSafeFolderFilesFlow(): Flow<List<DriveItem.File>> {
        return driveDao.getAllFiles().map { entities ->
            entities.filter { it.virtualParentId == SAFE_SPACE_VIRTUAL_ID || it.isEncrypted || it.isSecure }
                .map { entity ->
                    DriveItem.File(
                        entity.id,
                        entity.parentChatId,
                        entity.name,
                        entity.size,
                        entity.mimeType,
                        entity.telegramFileId,
                        entity.thumbnailPath,
                        entity.localPath,
                        entity.isStarred,
                        entity.remoteUniqueId ?: "",
                        entity.splitGroupId,
                        entity.totalParts,
                        entity.virtualParentId,
                        entity.isEncrypted
                    )
                }
        }
    }

    // ফোন স্টোরেজের নির্বাচিত ফোল্ডার থেকে সাথে সাথে আপলোড ও অটো-ডিলিট করার ফাংশন
    fun runImmediateFolderBackup() {
        if (!isBackupRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                val backupFolders = secureSettings.getStringList("backup_folders")
                if (backupFolders.isEmpty()) return@launch

                val customPrefs = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
                val backedUpSet = customPrefs.getStringSet("backed_up_paths_v2", emptySet())?.toMutableSet() ?: mutableSetOf()

                val filesToUpload = mutableListOf<File>()
                backupFolders.forEach { folderPath ->
                    val dir = File(folderPath)
                    if (dir.exists() && dir.isDirectory) {
                        scanDirRecursively(dir, backedUpSet, filesToUpload)
                    } else if (dir.exists() && dir.isFile) {
                        val key = "${dir.absolutePath}_${dir.length()}"
                        if (!backedUpSet.contains(key)) filesToUpload.add(dir)
                    }
                }

                if (filesToUpload.isEmpty()) return@launch

                filesToUpload.distinctBy { it.absolutePath }.forEach { file ->
                    if (file.exists() && file.length() > 0) {
                        val key = "${file.absolutePath}_${file.length()}"
                        backedUpSet.add(key)
                        customPrefs.edit().putStringSet("backed_up_paths_v2", backedUpSet).apply()
                        uploadFile(file.absolutePath, file.name)
                        delay(400)
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

    private fun scanDirRecursively(dir: File, backedUpSet: Set<String>, out: MutableList<File>) {
        val list = dir.listFiles() ?: return
        for (child in list) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                scanDirRecursively(child, backedUpSet, out)
            } else if (child.isFile && child.length() > 0) {
                val key = "${child.absolutePath}_${child.length()}"
                if (!backedUpSet.contains(key)) {
                    out.add(child)
                }
            }
        }
    }

    fun exportManifestToJson(): String = json.encodeToString(currentManifest)

    fun importManifestFromJson(jsonStr: String): Boolean {
        return try {
            val imported = json.decodeFromString<TeleDriveManifest>(jsonStr)
            currentManifest = imported
            saveCloudManifest()
            syncVirtualFoldersToDb()
            scope.launch {
                driveDao.resetAllFileVirtualParents()
                fetchFiles()
            }
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
                } else {
                    onResult(emptyList())
                }
            }
        }
    }

    fun restoreManifestFromMessage(messageId: Long, onResult: (Boolean) -> Unit) {
        ensureSavedMessagesChat { chatId ->
            telegramClient.send(TdApi.GetMessage(chatId, messageId)) { result ->
                if (result is TdApi.Message) {
                    val content = result.content
                    if (content is TdApi.MessageText) {
                        val text = content.text.text
                        val jsonStartIndex = text.indexOf('{')
                        if (jsonStartIndex != -1) {
                            try {
                                val imported = json.decodeFromString<TeleDriveManifest>(text.substring(jsonStartIndex))
                                currentManifest = imported
                                saveCloudManifest()
                                syncVirtualFoldersToDb()
                                scope.launch {
                                    driveDao.resetAllFileVirtualParents()
                                    fetchFiles()
                                }
                                onResult(true)
                            } catch (e: Exception) { onResult(false) }
                        } else onResult(false)
                    } else onResult(false)
                } else onResult(false)
            }
        }
    }

    fun createManualCloudBackup() {
        ensureSavedMessagesChat { chatId ->
            val manifestJson = json.encodeToString(currentManifest)
            val text = "$MANIFEST_BACKUP_PREFIX${System.currentTimeMillis()}\n\n$manifestJson"
            val formatted = TdApi.FormattedText(text, emptyArray())
            telegramClient.send(TdApi.SendMessage(chatId, null, null, null, null, TdApi.InputMessageText(formatted, null, true)))
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
                        existing?.id ?: deterministicId,
                        vf.name,
                        0,
                        "virtual_folder",
                        0,
                        chatId,
                        true,
                        isVirtual = true,
                        virtualId = vf.id,
                        virtualParentId = finalVirtualParentId,
                        createdAt = vf.createdAt * 1000,
                        isStarred = existing?.isStarred ?: false,
                        isSecure = vf.isSecure
                    )
                }
                driveDao.syncVirtualFolders(entities)
            }
        }
    }

    fun createVirtualFolder(name: String, parentId: String = "0", isSecure: Boolean = false) {
        val id = "vf_${System.nanoTime()}"
        val folder = VirtualFolder(id, name, parentId, System.currentTimeMillis() / 1000, "virtual_folder", isSecure)
        val updatedFolders = currentManifest.virtualFolders.toMutableMap()
        updatedFolders[id] = folder
        currentManifest = currentManifest.copy(virtualFolders = updatedFolders)
        syncVirtualFoldersToDb()
        
        ensureSavedMessagesChat { chatId ->
            val metaJson = json.encodeToString(folder)
            val text = "$VFOLDER_META_PREFIX\n\n$metaJson"
            telegramClient.send(TdApi.SendMessage(chatId, null, null, null, null, TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true))) { result ->
                if (result is TdApi.Message) {
                    synchronized(currentManifest) {
                        val updatedMetaIds = currentManifest.folderMetadataIds.toMutableMap()
                        updatedMetaIds[id] = result.id
                        currentManifest = currentManifest.copy(folderMetadataIds = updatedMetaIds)
                        saveCloudManifest()
                    }
                }
            }
        }
    }

    private fun saveCloudManifest() {
        ensureSavedMessagesChat { chatId ->
            val manifestJson = json.encodeToString(currentManifest)
            val text = "$MANIFEST_PREFIX\n\n$manifestJson"
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
                        entity.id,
                        entity.parentChatId,
                        entity.name,
                        entity.size,
                        entity.mimeType,
                        entity.telegramFileId,
                        entity.thumbnailPath,
                        entity.localPath,
                        entity.isStarred,
                        entity.remoteUniqueId ?: "",
                        entity.splitGroupId,
                        entity.totalParts,
                        entity.virtualParentId,
                        entity.isEncrypted
                    )
                }
        }
    }

    fun getStarredItems(): Flow<List<DriveItem>> {
        return driveDao.getStarredItems().map { entities ->
            entities.filter { (it.isVirtual || !it.isFolder) && it.virtualParentId != SAFE_SPACE_VIRTUAL_ID }.map { entity ->
                if (entity.isFolder) {
                    DriveItem.Folder(
                        entity.id,
                        entity.parentChatId,
                        entity.name,
                        entity.id,
                        entity.isStarred,
                        entity.isVirtual,
                        entity.virtualId,
                        entity.virtualParentId,
                        entity.isSecure
                    )
                } else {
                    DriveItem.File(
                        entity.id,
                        entity.parentChatId,
                        entity.name,
                        entity.size,
                        entity.mimeType,
                        entity.telegramFileId,
                        entity.thumbnailPath,
                        entity.localPath,
                        entity.isStarred,
                        entity.remoteUniqueId ?: "",
                        entity.splitGroupId,
                        entity.totalParts,
                        entity.virtualParentId,
                        entity.isEncrypted
                    )
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
                    else DriveItem.File(entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType, entity.telegramFileId, entity.thumbnailPath, entity.localPath, entity.isStarred, entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted)
                }
        }.debounce(50)
    }

    fun fetchFiles(chatId: Long? = null) {
        ensureSavedMessagesChat { resolvedChatId ->
            telegramClient.send(TdApi.LoadChats(TdApi.ChatListMain(), 20)) {
                fetchCloudManifest {
                    loadAllDriveItems(resolvedChatId)
                }
            }
        }
    }

    private fun loadAllDriveItems(chatId: Long) {
        fetchHistoryRecursively(chatId, 0, mutableListOf())
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
                    if (message.sendingState != null) return@mapNotNull null
                    mapMessageToEntity(chatId, message)
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

                        val updatedFolders = currentManifest.virtualFolders.toMutableMap()
                        val updatedMetaIds = currentManifest.folderMetadataIds.toMutableMap()
                        updatedFolders[vf.id] = vf
                        updatedMetaIds[vf.id] = message.id
                        currentManifest = currentManifest.copy(virtualFolders = updatedFolders, folderMetadataIds = updatedMetaIds)
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
                            val updatedMasters = currentManifest.splitFileMasters.toMutableMap()
                            updatedMasters[master.groupId] = master.copy(metadataMessageId = message.id)
                            currentManifest = currentManifest.copy(splitFileMasters = updatedMasters)
                        } else {
                            val meta = json.decodeFromString<FileMetadata>(jsonStr)
                            val updatedMappings = currentManifest.fileMappings.toMutableMap()
                            val updatedFileMetaIds = currentManifest.fileMetadataIds.toMutableMap()
                            updatedMappings[meta.messageId.toString()] = meta.virtualFolderId ?: "0"
                            updatedFileMetaIds[meta.messageId.toString()] = message.id
                            currentManifest = currentManifest.copy(fileMappings = updatedMappings, fileMetadataIds = updatedFileMetaIds)
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
                val cleanName = if (isEncrypted) cleanEncryptedFileName(splitInfo?.originalName ?: content.document.fileName) else splitInfo?.originalName ?: content.document.fileName
                val mimeType = if (content.document.mimeType == "application/octet-stream") FileUtils.getMimeType(cleanName) else content.document.mimeType
                val thumb = content.document.thumbnail
                if (thumb != null && thumb.file.local.path.isEmpty() && thumb.file.local.canBeDownloaded) {
                    telegramClient.send(TdApi.DownloadFile(thumb.file.id, 1, 0, 0, false))
                }
                DriveItemEntity(
                    message.id, cleanName, content.document.document.expectedSize, mimeType, content.document.document.id, chatId, false,
                    thumbnailPath = thumb?.file?.local?.path?.takeIf { it.isNotEmpty() },
                    localPath = content.document.document.local.path.takeIf { it.isNotEmpty() },
                    createdAt = message.date.toLong() * 1000,
                    thumbnailFileId = thumb?.file?.id,
                    remoteUniqueId = content.document.document.remote.uniqueId,
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
                    telegramClient.send(TdApi.DownloadFile(thumb.photo.id, 1, 0, 0, false))
                }
                DriveItemEntity(
                    message.id, "Photo_${message.id}.jpg", full?.expectedSize ?: 0L, "image/jpeg", full?.id ?: 0, chatId, false,
                    thumbnailPath = thumb?.photo?.local?.path?.takeIf { it.isNotEmpty() },
                    localPath = full?.local?.path?.takeIf { it.isNotEmpty() },
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
                val cleanName = if (isEncrypted) cleanEncryptedFileName(splitInfo?.originalName ?: content.video.fileName) else splitInfo?.originalName ?: content.video.fileName
                val mimeType = if (content.video.mimeType == "application/octet-stream") FileUtils.getMimeType(cleanName) else content.video.mimeType
                val thumb = content.video.thumbnail
                if (thumb != null && thumb.file.local.path.isEmpty() && thumb.file.local.canBeDownloaded) {
                    telegramClient.send(TdApi.DownloadFile(thumb.file.id, 1, 0, 0, false))
                }
                DriveItemEntity(
                    message.id, cleanName, content.video.video.expectedSize, mimeType, content.video.video.id, chatId, false,
                    thumbnailPath = thumb?.file?.local?.path?.takeIf { it.isNotEmpty() },
                    localPath = content.video.video.local.path.takeIf { it.isNotEmpty() },
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
                val cleanName = if (isEncrypted) cleanEncryptedFileName(splitInfo?.originalName ?: content.audio.fileName) else splitInfo?.originalName ?: content.audio.fileName
                val mimeType = if (content.audio.mimeType == "application/octet-stream" || content.audio.mimeType.isEmpty()) FileUtils.getMimeType(cleanName) else content.audio.mimeType
                DriveItemEntity(
                    message.id, cleanName, content.audio.audio.expectedSize, mimeType, content.audio.audio.id, chatId, false,
                    localPath = content.audio.audio.local.path.takeIf { it.isNotEmpty() },
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
                    telegramClient.send(TdApi.DownloadFile(thumb.file.id, 1, 0, 0, false))
                }
                DriveItemEntity(
                    message.id, cleanEncryptedFileName("Animation_${message.id}.mp4"), content.animation.animation.expectedSize, "video/mp4", content.animation.animation.id, chatId, false,
                    thumbnailPath = thumb?.file?.local?.path?.takeIf { it.isNotEmpty() },
                    localPath = content.animation.animation.local.path.takeIf { it.isNotEmpty() },
                    createdAt = message.date.toLong() * 1000,
                    thumbnailFileId = thumb?.file?.id,
                    remoteUniqueId = content.animation.animation.remote.uniqueId,
                    thumbnailRemoteUniqueId = thumb?.file?.remote?.uniqueId,
                    virtualParentId = getAndEnsureVirtualFolder(message.id, caption),
                    isSecure = isSecureFolder,
                    isEncrypted = isEncrypted
                )
            }
            is TdApi.MessageVoiceNote -> {
                DriveItemEntity(message.id, "Voice_${message.id}.ogg", content.voiceNote.voice.expectedSize, "audio/ogg", content.voiceNote.voice.id, chatId, false, localPath = content.voiceNote.voice.local.path.takeIf { it.isNotEmpty() }, createdAt = message.date.toLong() * 1000, virtualParentId = "0", isSecure = isSecureFolder, isEncrypted = isSecureFolder, remoteUniqueId = content.voiceNote.voice.remote.uniqueId)
            }
            is TdApi.MessageVideoNote -> {
                DriveItemEntity(message.id, "VideoNote_${message.id}.mp4", content.videoNote.video.expectedSize, "video/mp4", content.videoNote.video.id, chatId, false, localPath = content.videoNote.video.local.path.takeIf { it.isNotEmpty() }, createdAt = message.date.toLong() * 1000, virtualParentId = "0", isSecure = isSecureFolder, isEncrypted = isSecureFolder, remoteUniqueId = content.videoNote.video.remote.uniqueId)
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
            val deletedAt = currentManifest.deletedFolderIds[finalId]
            if (deletedAt != null) return "0"

            val recoveredName = "Folder_${finalId.takeLast(4)}"
            val newFolder = VirtualFolder(id = finalId, name = recoveredName, parentId = "0", createdAt = System.currentTimeMillis() / 1000)
            val updatedFolders = currentManifest.virtualFolders.toMutableMap()
            updatedFolders[finalId] = newFolder
            val updatedMappings = currentManifest.fileMappings.toMutableMap()
            if (tagId != null) updatedMappings[messageId.toString()] = tagId
            currentManifest = currentManifest.copy(virtualFolders = updatedFolders, fileMappings = updatedMappings)
            syncVirtualFoldersToDb()
            saveCloudManifest()
        }
        return finalId
    }

    fun createFolder(name: String) {
        createVirtualFolder(name, "0", isSecure = false)
    }

    fun createSecureFolder(name: String) {
        createVirtualFolder(name, "0", isSecure = true)
    }

    fun uploadFile(filePath: String, originalFileName: String, chatId: Long? = null, virtualFolderId: String? = null) {
        ensureSavedMessagesChat { targetChatId ->
            val customPrefs = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
            val autoDeleteEnabled = customPrefs.getBoolean("delete_after_backup", false)

            val vfTag = if (virtualFolderId != null && virtualFolderId != "0") "[VF:$virtualFolderId]" else ""
            val sourceFile = File(filePath)
            if (sourceFile.length() > FileUtils.MAX_FILE_SIZE) {
                if (!FileUtils.hasEnoughSpace(context, sourceFile.length())) return@ensureSavedMessagesChat
                startTransferService()
                scope.launch {
                    val placeholderId = "prep_${System.currentTimeMillis()}"
                    transferRepository.addTransfer(0, placeholderId, originalFileName, false, sourceFile.length(), status = context.getString(R.string.splitting_file, ""))
                    try {
                        val parts = FileUtils.splitFile(context, sourceFile) { progress ->
                            transferRepository.updateTransferManual(placeholderId, progress, context.getString(R.string.splitting_file_perc, (progress * 100).toInt()))
                        }
                        transferRepository.removeTransfer(placeholderId)
                        val groupId = FileUtils.generateSplitGroupId()
                        val partMessageIds = mutableListOf<Long>()
                        
                        parts.forEachIndexed { index, part ->
                            val caption = "[TD_SPLIT|ID:$groupId|PART:$index/${parts.size}|NAME:$originalFileName]$vfTag"
                            uploadSinglePart(part.absolutePath, originalFileName, targetChatId, caption, virtualFolderId, deleteSourceAfter = true) { messageId ->
                                if (messageId != 0L) partMessageIds.add(messageId)
                                if (index == parts.size - 1) {
                                    sendSplitMasterMetadata(groupId, originalFileName, sourceFile.length(), FileUtils.getMimeType(originalFileName), parts.size, virtualFolderId, isChatSecure(targetChatId), partMessageIds)
                                    if (autoDeleteEnabled) {
                                        deletePhoneFileCompletely(sourceFile)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        transferRepository.updateTransferManual(placeholderId, 0f, context.getString(R.string.split_fail))
                    }
                }
            } else {
                uploadSinglePart(
                    filePath = filePath,
                    originalFileName = originalFileName,
                    targetChatId = targetChatId,
                    caption = if (vfTag.isNotEmpty()) "$originalFileName $vfTag" else null,
                    virtualFolderId = virtualFolderId,
                    deleteSourceAfter = autoDeleteEnabled
                )
            }
        }
    }

    private fun uploadSinglePart(filePath: String, originalFileName: String, targetChatId: Long, caption: String? = null, virtualFolderId: String? = null, deleteSourceAfter: Boolean = false, onMessageSent: (Long) -> Unit = {}) {
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
            localPath = if (isSecure) null else filePath
        ) {
            var finalPath = filePath
            var finalCaption = caption ?: originalFileName
            var tempEncFile: File? = null
            var uploadSuccess = false
            
            transferRepository.updateTransferManual(tempId, 0f, context.getString(R.string.status_waiting))
            
            if (isSecure && password != null) {
                try {
                    tempEncFile = File(context.cacheDir, "enc_${System.nanoTime()}_${originalSourceFile.name}")
                    encryptionManager.encryptFile(originalSourceFile, tempEncFile, password) { progress ->
                        transferRepository.updateTransferManual(tempId, progress, context.getString(R.string.encrypting_perc, (progress * 100).toInt()), throttled = true)
                    }
                    finalPath = tempEncFile.absolutePath
                    finalCaption = if (caption != null) "$caption [ENC]" else "$originalFileName [ENC]"
                    transferRepository.updateRemoteUniqueId(tempId, tempId, 0, finalPath)
                } catch (e: Exception) {
                    transferRepository.updateTransferManual(tempId, 0f, context.getString(R.string.failed_with_error, "Encryption failed"))
                    return@enqueueTransfer 
                }
            }

            telegramClient.send(TdApi.SendMessage(targetChatId, null, null, null, null, TdApi.InputMessageDocument(TdApi.InputFileLocal(finalPath), null, false, TdApi.FormattedText(finalCaption, emptyArray())))) { result ->
                if (result is TdApi.Message) {
                    uploadSuccess = true
                    onMessageSent(result.id)
                    val content = result.content
                    val file = when (content) {
                        is TdApi.MessageDocument -> content.document.document
                        is TdApi.MessagePhoto -> content.photo.sizes.lastOrNull()?.photo
                        is TdApi.MessageVideo -> content.video.video
                        is TdApi.MessageAudio -> content.audio.audio
                        else -> null
                    }
                    
                    if (file != null) {
                        transferRepository.updateRemoteUniqueId(tempId, file.remote.uniqueId, file.id, finalPath)
                        if (file.remote.isUploadingCompleted) {
                            transferRepository.updateTransferManual(file.remote.uniqueId, 1.0f, TransferRepository.Status.COMPLETED)
                        }
                        if (!finalCaption.contains("[TD_SPLIT|")) {
                            sendFileMetadata(result.id, originalFileName, virtualFolderId, isSecure)
                        }
                    } else {
                        transferRepository.updateTransferManual(tempId, 1.0f, TransferRepository.Status.COMPLETED)
                    }
                } else {
                    val errMsg = if (result is TdApi.Error) result.message else "SendMessage failed"
                    transferRepository.updateTransferManual(tempId, 0f, context.getString(R.string.failed_with_error, errMsg))
                }
            }
            
            transferRepository.waitForCompletion(tempId)
            
            try {
                tempEncFile?.let { if (it.exists()) it.delete() }
                if (isTempCacheFile) {
                    if (originalSourceFile.exists()) originalSourceFile.delete()
                } else if (deleteSourceAfter && uploadSuccess) {
                    deletePhoneFileCompletely(originalSourceFile)
                }
            } catch (e: Exception) {
                Log.e("DriveRepo", "Cleanup error", e)
            }
            
            fetchFiles(targetChatId)
        }
    }

    private fun deletePhoneFileCompletely(file: File) {
        val path = file.absolutePath
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {}
        try {
            val externalUri = MediaStore.Files.getContentUri("external")
            context.contentResolver.delete(externalUri, "${MediaStore.MediaColumns.DATA}=?", arrayOf(path))
        } catch (e: Exception) {}
        try {
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        } catch (e: Exception) {}
    }

    private fun sendFileMetadata(messageId: Long, fileName: String, virtualFolderId: String?, isEncrypted: Boolean) {
        ensureSavedMessagesChat { chatId ->
            val meta = FileMetadata(messageId, fileName, virtualFolderId, isEncrypted)
            val text = "$FILE_META_PREFIX\n\n${json.encodeToString(meta)}"
            telegramClient.send(TdApi.SendMessage(chatId, null, null, null, null, TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true))) { result ->
                if (result is TdApi.Message) {
                    val updatedFileMetaIds = currentManifest.fileMetadataIds.toMutableMap()
                    updatedFileMetaIds[messageId.toString()] = result.id
                    currentManifest = currentManifest.copy(fileMetadataIds = updatedFileMetaIds)
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
                val updatedMasters = currentManifest.splitFileMasters.toMutableMap()
                updatedMasters[groupId] = finalMaster
                currentManifest = currentManifest.copy(splitFileMasters = updatedMasters)
                saveCloudManifest()
            }
        }
    }

    fun downloadFile(messageId: Long, chatId: Long, fileName: String) {
        startTransferService()
        telegramClient.send(TdApi.GetMessage(chatId, messageId)) { result ->
            if (result is TdApi.Message) {
                val fileId = when (val content = result.content) {
                    is TdApi.MessageDocument -> content.document.document.id
                    is TdApi.MessagePhoto -> content.photo.sizes.lastOrNull()?.photo?.id ?: 0
                    is TdApi.MessageVideo -> content.video.video.id
                    is TdApi.MessageAudio -> content.audio.audio.id
                    else -> 0
                }
                val remoteUniqueId = when (val content = result.content) {
                    is TdApi.MessageDocument -> content.document.document.remote.uniqueId
                    is TdApi.MessagePhoto -> content.photo.sizes.lastOrNull()?.photo?.remote?.uniqueId ?: ""
                    is TdApi.MessageVideo -> content.video.video.remote.uniqueId
                    is TdApi.MessageAudio -> content.audio.audio.remote.uniqueId
                    else -> ""
                }
                val expectedSize = when (val content = result.content) {
                    is TdApi.MessageDocument -> content.document.document.expectedSize
                    is TdApi.MessagePhoto -> content.photo.sizes.lastOrNull()?.photo?.expectedSize ?: 0L
                    is TdApi.MessageVideo -> content.video.video.expectedSize
                    is TdApi.MessageAudio -> content.audio.audio.expectedSize
                    else -> 0L
                }

                if (fileId != 0) {
                    transferRepository.enqueueTransfer(
                        fileId,
                        remoteUniqueId,
                        fileName,
                        true,
                        expectedSize,
                        localPath = null
                    ) {
                        telegramClient.send(TdApi.DownloadFile(fileId, 32, 0, 0, false))
                    }
                }
            }
        }
    }

    // প্রিভিউ করার সময় Downloads পপআপে কোনো ক্যাশ বা লিস্ট জমা করবে না
    fun downloadForPreview(messageId: Long, chatId: Long, fileName: String) {
        telegramClient.send(TdApi.GetMessage(chatId, messageId)) { result ->
            if (result is TdApi.Message) {
                val fileId = when (val content = result.content) {
                    is TdApi.MessageDocument -> content.document.document.id
                    is TdApi.MessagePhoto -> content.photo.sizes.lastOrNull()?.photo?.id ?: 0
                    is TdApi.MessageVideo -> content.video.video.id
                    is TdApi.MessageAudio -> content.audio.audio.id
                    else -> 0
                }
                if (fileId != 0) {
                    telegramClient.send(TdApi.DownloadFile(fileId, 16, 0, 0, false))
                }
            }
        }
    }

    fun saveToPublicStorage(file: DriveItem.File) {
        val source = file.localPath?.let { File(it) } ?: return
        if (!source.exists()) return
        scope.launch(Dispatchers.IO) {
            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dest = File(downloadDir, file.name)
                if (file.isEncrypted) {
                    val password = secureSessionManager.decryptedPassword.value
                    if (password != null) {
                        encryptionManager.decryptFile(source, dest, password) { }
                    }
                } else {
                    source.copyTo(dest, overwrite = true)
                }
                scope.launch(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.save_success), Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { }
        }
    }

    fun toggleStarred(item: DriveItem) {
        scope.launch { driveDao.updateStarred(item.id, item.parentChatId, !item.isStarred) }
    }

    fun renameItem(item: DriveItem, newName: String) {
        scope.launch {
            if (item is DriveItem.Folder) {
                if (item.isVirtual) {
                    val virtualId = item.virtualId ?: return@launch
                    val updatedFolders = currentManifest.virtualFolders.toMutableMap()
                    val folder = updatedFolders[virtualId] ?: return@launch
                    updatedFolders[virtualId] = folder.copy(name = newName)
                    currentManifest = currentManifest.copy(virtualFolders = updatedFolders)
                    saveCloudManifest()
                }
            } else if (item is DriveItem.File) {
                telegramClient.send(TdApi.GetMessage(item.parentChatId, item.id)) { result ->
                    if (result is TdApi.Message) {
                        val currentCaption = when (val content = result.content) {
                            is TdApi.MessageDocument -> content.caption.text
                            is TdApi.MessagePhoto -> content.caption.text
                            is TdApi.MessageVideo -> content.caption.text
                            is TdApi.MessageAudio -> content.caption.text
                            else -> ""
                        }
                        val tagRegex = Regex("\\[(ENC|TD_SPLIT|VF:|ID:|PART:|NAME:).*?\\]")
                        val tags = tagRegex.findAll(currentCaption).map { it.value }.toList()
                        val updatedTags = tags.map { tag ->
                            if (tag.startsWith("[TD_SPLIT|") && tag.contains("NAME:")) tag.replace(Regex("NAME:.*?(?=\\]|\\|)"), "NAME:$newName")
                            else tag
                        }
                        val newCaptionText = if (updatedTags.isNotEmpty()) {
                            if (updatedTags.any { it.startsWith("[TD_SPLIT") }) updatedTags.joinToString("")
                            else "$newName ${updatedTags.joinToString("")}"
                        } else newName
                        telegramClient.send(TdApi.EditMessageCaption(item.parentChatId, item.id, null, TdApi.FormattedText(newCaptionText, emptyArray()), false)) { editResult ->
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
                virtualFolders.forEach { folder ->
                    folder.virtualId?.let { vid -> deleteVirtualFolderRecursive(vid) }
                }
                saveCloudManifest()
            }
        }
    }

    private suspend fun deleteVirtualFolderRecursive(virtualId: String) {
        val children = driveDao.getItemsByVirtualParentSync(virtualId)
        val filesByChat = children.filter { !it.isFolder }.groupBy { it.parentChatId }
        filesByChat.forEach { (chatId, entities) ->
            val ids = entities.map { it.id }.toLongArray()
            telegramClient.send(TdApi.DeleteMessages(chatId, ids, true)) {}
        }
        
        val metadataIdsToDelete = mutableListOf<Long>()
        currentManifest.folderMetadataIds[virtualId]?.let { metadataIdsToDelete.add(it) }
        
        children.forEach { child ->
            if (!child.isFolder) {
                currentManifest.fileMetadataIds[child.id.toString()]?.let { metadataIdsToDelete.add(it) }
                child.splitGroupId?.let { gid ->
                    currentManifest.splitFileMasters[gid]?.metadataMessageId?.let { metadataIdsToDelete.add(it) }
                }
            }
        }
        
        if (metadataIdsToDelete.isNotEmpty() && savedMessagesChatId != 0L) {
            telegramClient.send(TdApi.DeleteMessages(savedMessagesChatId, metadataIdsToDelete.toLongArray(), true)) {}
        }

        synchronized(currentManifest) {
            val updatedFolders = currentManifest.virtualFolders.toMutableMap()
            updatedFolders.remove(virtualId)
            val updatedMetaIds = currentManifest.folderMetadataIds.toMutableMap()
            updatedMetaIds.remove(virtualId)
            val updatedFileMetaIds = currentManifest.fileMetadataIds.toMutableMap()
            val updatedFileMappings = currentManifest.fileMappings.toMutableMap()
            val updatedSplitMasters = currentManifest.splitFileMasters.toMutableMap()
            val updatedDeletedIds = currentManifest.deletedFolderIds.toMutableMap()
            updatedDeletedIds[virtualId] = System.currentTimeMillis() / 1000

            children.forEach { child ->
                if (!child.isFolder) {
                    updatedFileMetaIds.remove(child.id.toString())
                    updatedFileMappings.remove(child.id.toString())
                    child.splitGroupId?.let { updatedSplitMasters.remove(it) }
                }
            }

            currentManifest = currentManifest.copy(
                virtualFolders = updatedFolders,
                folderMetadataIds = updatedMetaIds,
                fileMetadataIds = updatedFileMetaIds,
                fileMappings = updatedFileMappings,
                splitFileMasters = updatedSplitMasters,
                deletedFolderIds = updatedDeletedIds,
                updatedAt = System.currentTimeMillis() / 1000
            )
        }

        children.filter { it.isFolder && it.isVirtual }.forEach { sf ->
            sf.virtualId?.let { deleteVirtualFolderRecursive(it) }
        }

        val entity = driveDao.getVirtualFolderById(virtualId)
        if (entity != null) driveDao.deleteItemCompletely(entity.id, entity.parentChatId)
        children.forEach { child ->
            driveDao.deleteItemCompletely(child.id, child.parentChatId)
        }
    }

    fun downloadFolderContents(folder: DriveItem.Folder) {
        if (folder.isVirtual) {
            folder.virtualId?.let { downloadVirtualFolderRecursive(it) }
        }
    }

    private fun downloadVirtualFolderRecursive(virtualId: String) {
        scope.launch {
            val children = driveDao.getItemsByVirtualParentSync(virtualId)
            children.forEach { child ->
                if (child.isFolder && child.isVirtual) {
                    child.virtualId?.let { downloadVirtualFolderRecursive(it) }
                } else if (!child.isFolder) {
                    downloadFile(child.id, child.parentChatId, child.name)
                }
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
        return calculateDirectorySize(context.cacheDir) + 
               calculateDirectorySize(context.filesDir) +
               (context.externalCacheDir?.let { calculateDirectorySize(it) } ?: 0L) +
               (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                   calculateDirectorySize(context.noBackupFilesDir) + calculateDirectorySize(context.codeCacheDir)
               } else 0L)
    }

    private fun calculateDirectorySize(directory: File?): Long {
        if (directory == null || !directory.exists()) return 0L
        var size: Long = 0
        try {
            val files = directory.listFiles() ?: return 0L
            for (file in files) {
                size += if (file.isDirectory) calculateDirectorySize(file) else file.length()
            }
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
            if (file.splitGroupId == null) {
                totalSize += file.size
            } else if (!processedGroups.contains(file.splitGroupId)) {
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
        val intent = Intent(context, com.awd.teledrive.data.service.TransferService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
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
        val endTag = "]"
        val startIndex = caption.indexOf(startTag)
        if (startIndex == -1) return null
        val endIndex = caption.indexOf(endTag, startIndex)
        if (endIndex == -1) return null
        return caption.substring(startIndex + startTag.length, endIndex)
    }

    private fun cleanEncryptedFileName(fileName: String): String {
        return fileName.replace(Regex("^enc_\\d+_"), "").replace(Regex("^enc_"), "")
    }

    private data class SplitMetadata(val groupId: String, val partIndex: Int, val totalParts: Int, val originalName: String)
}
