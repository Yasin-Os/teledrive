package com.awd.teledrive.data.repository

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.Job
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
        private const val DEDICATED_CHANNEL_TITLE = "MyCloud Storage"
        private const val PREF_STORAGE_CHAT_ID = "dedicated_storage_chat_id"
        private const val PREF_CHANNEL_PHOTO_SET = "dedicated_channel_photo_set_"
        private const val PREF_HELPER_BOT_USERNAME = "helper_bot_username"
        // P1-17: how often the on-disk cache size may be recomputed.
        private const val CACHE_SIZE_REFRESH_INTERVAL_MS = 15 * 60 * 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var savedMessagesChatId: Long = 0
    private var legacySavedMessagesId: Long = 0
    private val _savedMessagesChatIdFlow = MutableStateFlow(0L)
    private val isBackupRunning = AtomicBoolean(false)
    private var watcherDebounceJob: Job? = null
    private var manifestSaveDebounceJob: Job? = null

    private val channelInitLock = Any()
    private var isDiscoveringChannel = false
    private val pendingChatCallbacks = mutableListOf<(Long) -> Unit>()

    private val pendingUploadTempIds = ConcurrentHashMap<Long, String>()
    private val pendingAutoDeleteFiles = ConcurrentHashMap<Long, File>()
    private val pendingBackupKeys = ConcurrentHashMap<Long, String>()
    private val inFlightBackupKeys = ConcurrentHashMap.newKeySet<String>()

    fun getSavedMessagesChatIdFlow(): Flow<Long> = _savedMessagesChatIdFlow.asStateFlow()

    init {
        scope.launch {
            transferRepository.clearCompleted()
            registerRealTimeFolderWatcher()

            telegramClient.updates.collect { update ->
                when (update) {
                    is TdApi.UpdateFile -> {
                        val file = update.file
                        if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) {
                            driveDao.updateFileLocalPaths(file.id, file.remote.uniqueId, file.local.path)
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
                            inFlightBackupKeys.remove(bKey)
                            markFileBackedUp(bKey)
                        }

                        if (msg.chatId == savedMessagesChatId || msg.chatId == legacySavedMessagesId || savedMessagesChatId == 0L) {
                            mapMessageToEntity(msg.chatId, msg)?.let { entity ->
                                val cleanEntity = entity.copy(
                                    parentChatId = if (savedMessagesChatId != 0L) savedMessagesChatId else msg.chatId,
                                    localPath = null
                                )
                                driveDao.insertItemsPreservingStarred(listOf(cleanEntity))
                            }
                        }

                        // Auto-delete only once the upload is confirmed: a
                        // sent message alone does not prove the file bytes
                        // arrived. Delete now only if this message's own file
                        // reports the upload complete; otherwise leave the
                        // delete to the transfer-completion path, which
                        // removes the phone file after waitForCompletion
                        // reports success.
                        pendingAutoDeleteFiles.remove(oldId)?.let { phoneFile ->
                            if (file?.remote?.isUploadingCompleted == true) {
                                deletePhoneFileCompletely(phoneFile)
                            } else {
                                Log.w("DriveRepo", "Auto-delete deferred for $oldId: upload not yet confirmed")
                            }
                        }
                    }
                    is TdApi.UpdateMessageSendFailed -> {
                        val oldId = update.oldMessageId
                        val tempId = pendingUploadTempIds.remove(oldId)
                        pendingAutoDeleteFiles.remove(oldId)
                        pendingBackupKeys.remove(oldId)?.let { inFlightBackupKeys.remove(it) }
                        if (tempId != null) {
                            transferRepository.completeAndCleanupTransfer(tempId, success = false)
                        }
                    }
                }
            }
        }
    }

    private fun registerRealTimeFolderWatcher() {
        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    watcherDebounceJob?.cancel()
                    watcherDebounceJob = scope.launch {
                        // 30s debounce: MediaStore fires a burst of change
                        // events per file operation, so coalesce them into a
                        // single backup run instead of rescanning each time.
                        delay(30_000)
                        val folders = secureSettings.getStringList("backup_folders")
                        if (folders.isNotEmpty() && isAutomaticBackupAllowed()) {
                            runImmediateFolderBackup()
                        }
                    }
                }
            }
            val resolver = context.contentResolver
            resolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.registerContentObserver(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, observer)
            }
        } catch (e: Exception) {
            Log.e("DriveRepo", "Watcher registration error: ${e.message}")
        }
    }

    private fun isNetworkConnected(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Wi-Fi Only সম্মান করে: Wi-Fi Only চালু থাকলে শুধু unmetered
     * (সাধারণত Wi-Fi) নেটওয়ার্কেই ব্যাকআপ চলবে। নেটওয়ার্কের ধরন
     * জানা না গেলে সাবধানে মোবাইল ডাটা ধরে নেওয়া হয় (false)।
     */
    private fun isBackupNetworkAllowed(): Boolean {
        if (!isNetworkConnected()) return false
        if (!settingsRepository.isBackupWifiOnlyEnabled.value) return true
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (e: Exception) {
            false
        }
    }

    /** ম্যানুয়াল ("Backup Now") নাকি অটোমেটিক — সেই অনুযায়ী নেটওয়ার্ক যাচাই। */
    private fun isBackupNetworkAllowedFor(manual: Boolean): Boolean {
        return if (manual) isNetworkConnected() else isBackupNetworkAllowed()
    }

    /** অটোমেটিক ব্যাকআপ (লুপ/ওয়ার্কার/ওয়াচার) এখন চালানো যাবে কি না। */
    private fun isAutomaticBackupAllowed(): Boolean {
        return settingsRepository.isAutoBackupEnabled.value && isBackupNetworkAllowed()
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

    // TTL cache for verifyOfflinePath: File.exists()/length() on every row
    // of every Flow emission is expensive, so verified results (path or
    // null) are cached briefly. 30s TTL keeps stale misses short-lived.
    private val offlinePathCache = ConcurrentHashMap<String, Pair<String?, Long>>()
    private val offlinePathCacheTtlMs = 30_000L

    private fun verifyOfflinePath(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        val now = System.currentTimeMillis()
        offlinePathCache[path]?.let { (cached, cachedAt) ->
            if (now - cachedAt < offlinePathCacheTtlMs) return cached
        }
        val f = File(path)
        val result = if (f.exists() && f.length() > 0L) path else null
        if (offlinePathCache.size > 2000) offlinePathCache.clear()
        offlinePathCache[path] = result to now
        return result
    }

    private val MANIFEST_PREFIX = "#TELEDRIVE_MANIFEST"
    private val MANIFEST_BACKUP_PREFIX = "#TELEDRIVE_MANIFEST_BACKUP_"
    private val VFOLDER_META_PREFIX = "#TELEDRIVE_VFOLDER_DATA"
    private val FILE_META_PREFIX = "#TELEDRIVE_FILE_DATA"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var currentManifest = TeleDriveManifest()
    // Dedicated lock for manifest mutations. Never synchronize on
    // currentManifest itself: it is a var that gets reassigned, so the
    // lock object would change under the threads using it.
    private val manifestLock = Any()
    private var manifestMessageId: Long = 0

    private fun finishChannelDiscovery(chatId: Long) {
        val callbacks: List<(Long) -> Unit>
        synchronized(channelInitLock) {
            if (chatId != 0L) {
                savedMessagesChatId = chatId
                _savedMessagesChatIdFlow.value = chatId
                secureSettings.saveLong(PREF_STORAGE_CHAT_ID, chatId)
            }
            isDiscoveringChannel = false
            callbacks = pendingChatCallbacks.toList()
            pendingChatCallbacks.clear()
        }
        if (chatId != 0L) {
            ensureChannelBrandPhotoAndBot(chatId)
            callbacks.forEach { it(chatId) }
        }
    }

    private fun ensureSavedMessagesChat(onReady: (Long) -> Unit) {
        synchronized(channelInitLock) {
            if (savedMessagesChatId != 0L) {
                onReady(savedMessagesChatId)
                return
            }
            pendingChatCallbacks.add(onReady)
            if (isDiscoveringChannel) return
            isDiscoveringChannel = true
        }

        val cachedChannelId = secureSettings.getLong(PREF_STORAGE_CHAT_ID, 0L)
        if (cachedChannelId != 0L) {
            telegramClient.send(TdApi.GetChat(cachedChannelId)) { res ->
                if (res is TdApi.Chat) {
                    finishChannelDiscovery(res.id)
                } else {
                    secureSettings.saveLong(PREF_STORAGE_CHAT_ID, 0L)
                    discoverOrCreateDedicatedChannel()
                }
            }
            return
        }

        discoverOrCreateDedicatedChannel()
    }

    private fun discoverOrCreateDedicatedChannel() {
        telegramClient.send(TdApi.GetMe()) { meResult ->
            if (meResult is TdApi.User) {
                val myUserId = meResult.id
                telegramClient.send(TdApi.CreatePrivateChat(myUserId, false)) { selfChat ->
                    if (selfChat is TdApi.Chat) {
                        legacySavedMessagesId = selfChat.id
                    }
                }
            }

            telegramClient.send(TdApi.LoadChats(TdApi.ChatListArchive(), 50)) {
                telegramClient.send(TdApi.SearchChats(DEDICATED_CHANNEL_TITLE, 30)) { localRes ->
                    val localIds = (localRes as? TdApi.Chats)?.chatIds?.toList() ?: emptyList()
                    telegramClient.send(TdApi.SearchChatsOnServer(DEDICATED_CHANNEL_TITLE, 30)) { serverRes ->
                        val serverIds = (serverRes as? TdApi.Chats)?.chatIds?.toList() ?: emptyList()
                        val combinedIds = (localIds + serverIds).distinct()
                        if (combinedIds.isNotEmpty()) {
                            verifyCandidateChats(combinedIds, 0)
                        } else {
                            createAndArchiveStorageChannel()
                        }
                    }
                }
            }
        }
    }

    private fun verifyCandidateChats(chatIds: List<Long>, index: Int) {
        if (index >= chatIds.size) {
            createAndArchiveStorageChannel()
            return
        }
        val candidateId = chatIds[index]
        telegramClient.send(TdApi.GetChat(candidateId)) { chatRes ->
            if (chatRes is TdApi.Chat &&
                chatRes.title.equals(DEDICATED_CHANNEL_TITLE, ignoreCase = true) &&
                chatRes.type is TdApi.ChatTypeSupergroup
            ) {
                telegramClient.send(TdApi.AddChatToList(chatRes.id, TdApi.ChatListArchive())) {}
                finishChannelDiscovery(chatRes.id)
            } else {
                verifyCandidateChats(chatIds, index + 1)
            }
        }
    }

    private fun createAndArchiveStorageChannel() {
        val createReq = TdApi.CreateNewSupergroupChat(
            DEDICATED_CHANNEL_TITLE,
            false,
            true,
            "MyCloud Personal Unlimited Encrypted Drive • Developed by Yasin Adnan",
            null,
            0,
            false
        )
        telegramClient.send(createReq) { result ->
            if (result is TdApi.Chat) {
                telegramClient.send(TdApi.AddChatToList(result.id, TdApi.ChatListArchive())) {}
                finishChannelDiscovery(result.id)
            } else if (legacySavedMessagesId != 0L) {
                finishChannelDiscovery(legacySavedMessagesId)
            } else {
                finishChannelDiscovery(0L)
            }
        }
    }

    private fun ensureChannelBrandPhotoAndBot(chatId: Long) {
        if (chatId == 0L || chatId == legacySavedMessagesId) return

        val photoKey = "$PREF_CHANNEL_PHOTO_SET$chatId"
        if (!secureSettings.getBoolean(photoKey, false)) {
            telegramClient.send(TdApi.GetChat(chatId)) { res ->
                if (res is TdApi.Chat) {
                    if (res.photo == null) {
                        setChannelBrandPhoto(chatId, photoKey)
                    } else {
                        secureSettings.saveBoolean(photoKey, true)
                    }
                }
            }
        }

        val botUsername = secureSettings.getString(PREF_HELPER_BOT_USERNAME, "")?.trim() ?: ""
        if (botUsername.isNotEmpty()) {
            attachBotToStorageChannel(botUsername)
        }
    }

    private fun setChannelBrandPhoto(chatId: Long, photoPrefKey: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val size = 512
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 0f, size.toFloat(), size.toFloat(),
                        intArrayOf(0xFF070B14.toInt(), 0xFF0F253E.toInt()),
                        null,
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

                val drawable = ContextCompat.getDrawable(context, R.drawable.ic_mycloud_logo)
                    ?: ContextCompat.getDrawable(context, R.mipmap.ic_launcher)

                if (drawable != null) {
                    val pad = (size * 0.08f).toInt()
                    drawable.setBounds(pad, pad, size - pad, size - pad)
                    drawable.draw(canvas)
                }

                val photoFile = File(context.cacheDir, "mycloud_channel_avatar.jpg")
                FileOutputStream(photoFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }

                if (photoFile.exists() && photoFile.length() > 0) {
                    val chatPhoto = TdApi.InputChatPhotoStatic(TdApi.InputFileLocal(photoFile.absolutePath))
                    telegramClient.send(TdApi.SetChatPhoto(chatId, chatPhoto)) { result ->
                        if (result is TdApi.Ok) {
                            secureSettings.saveBoolean(photoPrefKey, true)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DriveRepo", "Failed to set channel brand photo: ${e.message}")
            }
        }
    }

    fun attachBotToStorageChannel(botUsername: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val cleanUsername = botUsername.removePrefix("@").trim()
        if (cleanUsername.isEmpty()) {
            onResult(false, "Invalid bot username")
            return
        }
        secureSettings.saveString(PREF_HELPER_BOT_USERNAME, cleanUsername)

        ensureSavedMessagesChat { chatId ->
            telegramClient.send(TdApi.SearchPublicChat(cleanUsername)) { searchRes ->
                if (searchRes is TdApi.Chat && searchRes.type is TdApi.ChatTypePrivate) {
                    val botUserId = (searchRes.type as TdApi.ChatTypePrivate).userId
                    val adminRights = TdApi.ChatAdministratorRights().apply {
                        canManageChat = true
                        canChangeInfo = true
                        canPostMessages = true
                        canEditMessages = true
                        canDeleteMessages = true
                        canInviteUsers = false
                        canRestrictMembers = false
                        canPinMessages = false
                        canManageTopics = false
                        canPromoteMembers = false
                        canManageVideoChats = false
                        canPostStories = true
                        canEditStories = true
                        canDeleteStories = true
                        isAnonymous = false
                    }
                    val status = TdApi.ChatMemberStatusAdministrator("MyCloud Bot", true, adminRights)
                    telegramClient.send(TdApi.SetChatMemberStatus(chatId, TdApi.MessageSenderUser(botUserId), status)) { promRes ->
                        if (promRes is TdApi.Ok) {
                            onResult(true, "Bot @$cleanUsername connected to MyCloud Storage!")
                        } else if (promRes is TdApi.Error) {
                            onResult(false, promRes.message)
                        }
                    }
                } else {
                    onResult(false, "Could not find bot @$cleanUsername")
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
                    if (!backedUpSet.contains(key) && !inFlightBackupKeys.contains(key)) {
                        foundFiles[dir.absolutePath] = dir
                    }
                }
            }
        }

        // Incremental scan: only ask MediaStore for files modified since
        // the last backup run (5-minute overlap so files modified during
        // a backup run are not missed). Watermark 0 = never backed up,
        // so fall back to the full scan as before. The File-tree walk
        // above and the SAF walk below stay unfiltered as a safety net.
        val lastBackupTime = secureSettings.getLong("last_backup_time", 0L)
        val watermarkSec = ((lastBackupTime - 5 * 60 * 1000).coerceAtLeast(0L)) / 1000
        val modifiedSelection = if (watermarkSec == 0L) null
            else "${MediaStore.MediaColumns.DATE_MODIFIED} > ?"
        val modifiedSelectionArgs = if (watermarkSec == 0L) null
            else arrayOf(watermarkSec.toString())

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
                context.contentResolver.query(uri, projection, modifiedSelection, modifiedSelectionArgs, null)?.use { cursor ->
                    val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (dataIdx != -1) {
                        while (cursor.moveToNext()) {
                            val filePath = cursor.getString(dataIdx) ?: continue
                            if (normalizedPaths.any { folder -> filePath.startsWith("$folder/") || filePath == folder }) {
                                val f = File(filePath)
                                if (f.exists() && f.isFile && f.length() > 0 && !f.name.startsWith(".")) {
                                    val key = "${f.absolutePath}_${f.length()}"
                                    if (!backedUpSet.contains(key) && !inFlightBackupKeys.contains(key)) {
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
                if (!backedUpSet.contains(key) && !inFlightBackupKeys.contains(key)) {
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
                if (!backedUpSet.contains(key) && !inFlightBackupKeys.contains(key)) {
                    val tempFile = File(context.cacheDir, name)
                    try {
                        context.contentResolver.openInputStream(doc.uri)?.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output, bufferSize = 512 * 1024)
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

    /**
     * ফোল্ডার ব্যাকআপ চালায়। [manual] = true হলে এটি ব্যবহারকারীর স্পষ্ট
     * "Backup Now" নির্দেশ — তখন Auto Backup টগল ও Wi-Fi Only দেখা হয় না।
     * অটোমেটিক কলের ক্ষেত্রে দুটি সেটিংই মানা হয়।
     */
    fun runImmediateFolderBackup(manual: Boolean = false, onFinished: (() -> Unit)? = null) {
        if (!manual && !isAutomaticBackupAllowed()) {
            onFinished?.invoke()
            return
        }
        if (!isBackupRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (!isBackupNetworkAllowedFor(manual)) return@launch
                val chatId = awaitSavedMessagesChatId()
                if (chatId == 0L) return@launch

                val backupFolders = secureSettings.getStringList("backup_folders")
                if (backupFolders.isEmpty()) return@launch

                val customPrefs = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
                val deleteEnabled = customPrefs.getBoolean("delete_after_backup", false)

                val filesToUpload = collectTargetBackupFiles(backupFolders)
                if (filesToUpload.isEmpty()) return@launch

                for (file in filesToUpload) {
                    if (!isBackupNetworkAllowedFor(manual)) break
                    if (!file.exists() || file.length() <= 0L) continue

                    val bKey = "${file.absolutePath}_${file.length()}"
                    val alreadyDone = customPrefs.getStringSet("backed_up_paths_v3", emptySet())?.contains(bKey) == true
                    if (alreadyDone || !inFlightBackupKeys.add(bKey)) continue

                    uploadFileAndWait(
                        filePath = file.absolutePath,
                        originalFileName = file.name,
                        forceDeleteSource = deleteEnabled,
                        backupTrackingKey = bKey
                    )
                    delay(80)
                }
                secureSettings.saveLong("last_backup_time", System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e("DriveRepo", "Sequential backup error: ${e.message}")
            } finally {
                isBackupRunning.set(false)
                onFinished?.invoke()
            }
        }
    }

    suspend fun uploadFileAndWait(
        filePath: String,
        originalFileName: String,
        forceDeleteSource: Boolean = false,
        backupTrackingKey: String? = null
    ) {
        uploadFile(
            filePath = filePath,
            originalFileName = originalFileName,
            forceDeleteSource = forceDeleteSource,
            backupTrackingKey = backupTrackingKey
        )
        delay(200)
        var waited = 0L
        val maxWait = 25 * 60 * 1000L
        while (waited < maxWait) {
            val stillActive = transferRepository.transfers.value.values.any {
                !it.isDownload && (it.localPath == filePath || it.fileName == originalFileName) &&
                it.status != TransferRepository.Status.COMPLETED &&
                it.status != TransferRepository.Status.CANCELLED &&
                !it.status.startsWith(TransferRepository.Status.FAILED)
            }
            if (!stillActive) break
            delay(200)
            waited += 200
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
                this.limit = 30
            }
            telegramClient.send(searchRequest) { result ->
                if (result is TdApi.Messages) synchronized(allMessages) { allMessages.addAll(result.messages.toList()) }
                searchDone = true
                tryFinish()
            }
            telegramClient.send(TdApi.GetChatHistory(chatId, 0, 0, 50, false)) { result ->
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
        synchronized(manifestLock) {
            // Re-merge under the lock so a manifest update made while the
            // cloud fetch was in flight is not clobbered by the wholesale
            // replace; cloud data still wins on conflicts (see mergeManifests).
            currentManifest = compactManifest(mergeManifests(currentManifest, mergedManifest))
        }
        syncVirtualFoldersToDb()
    }

    private fun compactManifest(manifest: TeleDriveManifest): TeleDriveManifest {
        val compactMappings = manifest.fileMappings.filterValues { it != "0" && it.isNotEmpty() }
        return manifest.copy(
            fileMappings = compactMappings,
            fileMetadataIds = emptyMap()
        )
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
            fileMappings = (base.fileMappings + other.fileMappings).filterValues { it != "0" && it.isNotEmpty() },
            secureFolderChatIds = base.secureFolderChatIds + other.secureFolderChatIds,
            splitFileMasters = base.splitFileMasters + other.splitFileMasters,
            folderMetadataIds = base.folderMetadataIds + other.folderMetadataIds,
            fileMetadataIds = emptyMap(),
            deletedFolderIds = mergedDeletedIds
        )
    }

    fun setCloudItemsSafeState(itemIds: List<Long>, hideInSafeFolder: Boolean) {
        scope.launch {
            val targetVirtualParent = if (hideInSafeFolder) SAFE_SPACE_VIRTUAL_ID else "0"
            val updatedMappings = currentManifest.fileMappings.toMutableMap()
            itemIds.forEach { id ->
                driveDao.updateVirtualParent(id, targetVirtualParent)
                if (hideInSafeFolder) {
                    updatedMappings[id.toString()] = SAFE_SPACE_VIRTUAL_ID
                } else {
                    updatedMappings.remove(id.toString())
                }
            }
            synchronized(manifestLock) {
                currentManifest = compactManifest(currentManifest.copy(fileMappings = updatedMappings, updatedAt = System.currentTimeMillis() / 1000))
            }
            saveCloudManifestDebounced()
        }
    }

    fun getSafeFolderFilesFlow(): Flow<List<DriveItem.File>> {
        return driveDao.getSafeFolderFiles().map { entities ->
            entities.map { entity ->
                    DriveItem.File(
                        entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType,
                        entity.telegramFileId, verifyOfflinePath(entity.thumbnailPath), verifyOfflinePath(entity.localPath), entity.isStarred,
                        entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted
                    )
                }
        }
    }

    fun exportManifestToJson(): String = json.encodeToString(compactManifest(currentManifest))

    fun importManifestFromJson(jsonStr: String): Boolean {
        return try {
            synchronized(manifestLock) {
                currentManifest = compactManifest(json.decodeFromString<TeleDriveManifest>(jsonStr))
            }
            saveCloudManifestDebounced()
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
                            synchronized(manifestLock) {
                                currentManifest = compactManifest(json.decodeFromString<TeleDriveManifest>(text.substring(jsonStartIndex)))
                            }
                            saveCloudManifestDebounced()
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
            val text = "$MANIFEST_BACKUP_PREFIX${System.currentTimeMillis()}\n\n${json.encodeToString(compactManifest(currentManifest))}"
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
        synchronized(manifestLock) {
            currentManifest = compactManifest(currentManifest.copy(virtualFolders = currentManifest.virtualFolders + (id to folder)))
        }
        syncVirtualFoldersToDb()
        saveCloudManifestDebounced()
    }

    private fun saveCloudManifestDebounced() {
        manifestSaveDebounceJob?.cancel()
        manifestSaveDebounceJob = scope.launch {
            delay(1200)
            saveCloudManifestNow()
        }
    }

    private fun saveCloudManifestNow() {
        ensureSavedMessagesChat { chatId ->
            val clean = compactManifest(currentManifest)
            val text = "$MANIFEST_PREFIX\n\n${json.encodeToString(clean)}"
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

    fun getAllFilesPage(limit: Int, offset: Int): Flow<List<DriveItem.File>> {
        return driveDao.getAllFilesPage(limit, offset).map { entities ->
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
        }.debounce(25)
    }

    fun getItemsPage(chatId: Long, virtualParentId: String = "0", limit: Int, offset: Int): Flow<List<DriveItem>> {
        val targetChatId = if (chatId != 0L) chatId else savedMessagesChatId
        val flow = if (targetChatId != 0L) driveDao.getItemsPage(targetChatId, virtualParentId, limit, offset)
        else kotlinx.coroutines.flow.flowOf(emptyList())

        return flow.map { entities ->
            entities.filter { (!it.isFolder || it.isVirtual) && (virtualParentId == SAFE_SPACE_VIRTUAL_ID || it.virtualParentId != SAFE_SPACE_VIRTUAL_ID) }
                .sortedByDescending { it.createdAt }
                .map { entity ->
                    if (entity.isFolder) DriveItem.Folder(entity.id, entity.parentChatId, entity.name, entity.id, entity.isStarred, entity.isVirtual, entity.virtualId, entity.virtualParentId, entity.isSecure)
                    else DriveItem.File(entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType, entity.telegramFileId, verifyOfflinePath(entity.thumbnailPath), verifyOfflinePath(entity.localPath), entity.isStarred, entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted)
                }
        }
    }

    fun searchGlobalPage(query: String, limit: Int, offset: Int): Flow<List<DriveItem>> {
        return driveDao.searchGlobalPage(query, limit, offset).map { entities ->
            entities.filter { (!it.isFolder || it.isVirtual) && it.virtualParentId != SAFE_SPACE_VIRTUAL_ID }
                .sortedByDescending { it.createdAt }
                .map { entity ->
                    if (entity.isFolder) DriveItem.Folder(entity.id, entity.parentChatId, entity.name, entity.id, entity.isStarred, entity.isVirtual, entity.virtualId, entity.virtualParentId, entity.isSecure)
                    else DriveItem.File(entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType, entity.telegramFileId, verifyOfflinePath(entity.thumbnailPath), verifyOfflinePath(entity.localPath), entity.isStarred, entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted)
                }
        }
    }

    // ---- Batch 7b smart search (additional path; getItems /
    // searchGlobalPage above are untouched and keep working for their
    // existing callers) ----

    /**
     * Name-FTS + content-FTS + filters search (see buildSmartSearchQuery).
     * The SQL already orders folders first then newest first — do NOT
     * re-sort here, or the folders-first order is lost.
     */
    fun smartSearch(
        query: String,
        filters: SmartSearchFilters
    ): Flow<List<SmartSearchResult>> {
        return driveDao.smartSearchRaw(buildSmartSearchQuery(query, filters)).map { rows ->
            rows.filter { (!it.item.isFolder || it.item.isVirtual) && it.item.virtualParentId != SAFE_SPACE_VIRTUAL_ID }
                .map { row ->
                    val entity = row.item
                    val item = if (entity.isFolder) DriveItem.Folder(entity.id, entity.parentChatId, entity.name, entity.id, entity.isStarred, entity.isVirtual, entity.virtualId, entity.virtualParentId, entity.isSecure)
                    else DriveItem.File(entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType, entity.telegramFileId, verifyOfflinePath(entity.thumbnailPath), verifyOfflinePath(entity.localPath), entity.isStarred, entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted)
                    SmartSearchResult(item, row.matchedInContent != 0)
                }
        }
    }

    fun getRecentSearches(): Flow<List<String>> {
        return driveDao.getRecentSearches().map { rows -> rows.map { it.query } }
    }

    fun saveRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        scope.launch { driveDao.saveRecentSearch(trimmed) }
    }

    fun deleteRecentSearch(query: String) {
        scope.launch { driveDao.deleteRecentSearch(query) }
    }

    /**
     * Batch 7b content search: best-effort index of one file's text.
     * Called from PreviewViewModel when a local copy is available for
     * preview. Encrypted files are skipped inside the indexer (never
     * index secure content into the plaintext FTS table) and any failure
     * is swallowed there, so this can never break preview.
     */
    fun indexFileContentForSearch(file: DriveItem.File) {
        if (file.isEncrypted) return
        scope.launch { contentSearchIndexer.indexFile(file) }
    }

    private val contentSearchIndexer by lazy { ContentSearchIndexer(driveDao) }

    // ---- Vault 2.0 encrypt-on-hide (Batch 7b vault side) ----

    /**
     * Records an encrypted local copy: localPath becomes the ".enc" path
     * and the row is marked encrypted. Used by SecureStorageViewModel to
     * make the encrypt-on-hide toggle actually persist (until this lands
     * the toggle is inert by design — see persistEncryptedLocalCopy).
     */
    suspend fun markLocalCopyEncrypted(itemId: Long, chatId: Long, encryptedPath: String) {
        driveDao.markLocalCopyEncrypted(itemId, chatId, encryptedPath)
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
        telegramClient.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, 100, false)) { result ->
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
                if (allFetchedIds.size < 2500 && lastMsgId != fromMessageId) {
                    fetchHistoryRecursively(chatId, lastMsgId, allFetchedIds)
                } else {
                    // The history fetch stopped before reaching the end
                    // (2,500-item cap or no progress): the local index may
                    // hold files this sync never saw, so pruning here would
                    // delete entries for files that still exist on Telegram.
                    // Only a sync that provably reached the end of history
                    // (the empty page above) may prune the local index.
                    Log.w("DriveRepo", "History sync stopped at ${allFetchedIds.size} items; skipping local prune")
                }
            }
        }
    }

    private fun mapMessageToEntity(chatId: Long, message: TdApi.Message): DriveItemEntity? {
        val isSecureFolder = currentManifest.secureFolderChatIds.contains(chatId)
        val content = message.content

        // "Auto-Load Thumbnails" বন্ধ থাকলে থাম্বনেইল নিজে থেকে ডাউনলোড
        // হবে না; thumbnailFileId সংরক্ষিত থাকে বলে ব্যবহারকারী কোনো
        // ফাইলে ট্যাপ করলে তখন ডাউনলোড হবে (downloadForPreview)।
        val autoDownloadThumbnails = settingsRepository.isThumbnailAutoDownloadEnabled.value
        
        if (content is TdApi.MessageText && (chatId == savedMessagesChatId || chatId == legacySavedMessagesId)) {
            val text = content.text.text
            if (text.startsWith(VFOLDER_META_PREFIX)) {
                try {
                    val jsonStartIndex = text.indexOf('{')
                    if (jsonStartIndex != -1) {
                        val vf = json.decodeFromString<VirtualFolder>(text.substring(jsonStartIndex))
                        val deletedAt = currentManifest.deletedFolderIds[vf.id]
                        if (deletedAt != null && vf.createdAt <= deletedAt) return null
                        synchronized(manifestLock) {
                            currentManifest = compactManifest(currentManifest.copy(
                                virtualFolders = currentManifest.virtualFolders + (vf.id to vf)
                            ))
                        }
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
                            synchronized(manifestLock) {
                                currentManifest = compactManifest(currentManifest.copy(splitFileMasters = currentManifest.splitFileMasters + (master.groupId to master.copy(metadataMessageId = message.id))))
                            }
                        } else {
                            val meta = json.decodeFromString<FileMetadata>(jsonStr)
                            val vId = meta.virtualFolderId ?: "0"
                            if (vId != "0") {
                                synchronized(manifestLock) {
                                    currentManifest = compactManifest(currentManifest.copy(
                                        fileMappings = currentManifest.fileMappings + (meta.messageId.toString() to vId)
                                    ))
                                }
                            }
                            scope.launch { driveDao.updateVirtualParent(meta.messageId, vId) }
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

                if (autoDownloadThumbnails && thumb != null && thumb.file.local.path.isEmpty() && thumb.file.local.canBeDownloaded) {
                    telegramClient.send(TdApi.DownloadFile(thumb.file.id, 32, 0, 0, false))
                } else if (autoDownloadThumbnails && thumb == null && !isEncrypted && mimeType.startsWith("image/") && docFile.local.path.isEmpty() && docFile.expectedSize < 15 * 1024 * 1024L) {
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
                if (autoDownloadThumbnails && thumb != null && thumb.photo.local.path.isEmpty() && thumb.photo.local.canBeDownloaded) {
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
                if (autoDownloadThumbnails && thumb != null && thumb.file.local.path.isEmpty() && thumb.file.local.canBeDownloaded) {
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
                if (autoDownloadThumbnails && thumb != null && thumb.file.local.path.isEmpty() && thumb.file.local.canBeDownloaded) {
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
            synchronized(manifestLock) {
                currentManifest = compactManifest(currentManifest.copy(
                    virtualFolders = currentManifest.virtualFolders + (finalId to newFolder),
                    fileMappings = if (tagId != null) currentManifest.fileMappings + (messageId.toString() to tagId) else currentManifest.fileMappings
                ))
            }
            syncVirtualFoldersToDb()
            saveCloudManifestDebounced()
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
            if (!sourceFile.exists()) {
                if (backupTrackingKey != null) inFlightBackupKeys.remove(backupTrackingKey)
                return@ensureSavedMessagesChat
            }

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
                        
                        val partUploadOk = BooleanArray(parts.size)
                        parts.forEachIndexed { index, part ->
                            val caption = "[TD_SPLIT|ID:$groupId|PART:$index/${parts.size}|NAME:$cleanName]$vfTag"
                            uploadSinglePart(
                                part.absolutePath, cleanName, targetChatId, caption, virtualFolderId,
                                deleteSourceAfter = true,
                                onMessageSent = { messageId ->
                                    if (messageId != 0L) partMessageIds.add(messageId)
                                },
                                onUploadCompleted = { success ->
                                    // Parts upload serially, so the last part's
                                    // completion is the moment every part has
                                    // been attempted — but only finalize when
                                    // ALL parts succeeded. Never mark the file
                                    // backed up or delete the phone original
                                    // on send-accept alone: the last part may
                                    // still be uploading, or an earlier part
                                    // may have failed.
                                    partUploadOk[index] = success
                                    if (index == parts.size - 1) {
                                        if (partUploadOk.all { it }) {
                                            sendSplitMasterMetadata(groupId, cleanName, sourceFile.length(), FileUtils.getMimeType(cleanName), parts.size, virtualFolderId, isChatSecure(targetChatId), partMessageIds)
                                            if (backupTrackingKey != null) {
                                                inFlightBackupKeys.remove(backupTrackingKey)
                                                markFileBackedUp(backupTrackingKey)
                                            }
                                            if (autoDeleteEnabled) deletePhoneFileCompletely(sourceFile)
                                        } else {
                                            if (backupTrackingKey != null) inFlightBackupKeys.remove(backupTrackingKey)
                                            Log.w("DriveRepo", "Split upload incomplete for $cleanName; phone original kept")
                                        }
                                    }
                                }
                            )
                        }
                    } catch (e: Exception) {
                        if (backupTrackingKey != null) inFlightBackupKeys.remove(backupTrackingKey)
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
                    while (opts.outWidth / sampleSize > 280 || opts.outHeight / sampleSize > 280) sampleSize *= 2
                    BitmapFactory.decodeFile(sourceFile.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                }
                mime.startsWith("video/") -> {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(sourceFile.absolutePath)
                    val frame = retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.frameAtTime
                    retriever.release()
                    frame
                }
                else -> null
            }

            if (bitmap != null) {
                val maxDim = 280f
                val ratio = minOf(maxDim / bitmap.width.coerceAtLeast(1), maxDim / bitmap.height.coerceAtLeast(1), 1f)
                val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
                val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)

                val thumbFile = File(context.cacheDir, "thumb_${System.nanoTime()}.jpg")
                FileOutputStream(thumbFile).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
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
        onMessageSent: (Long) -> Unit = {},
        onUploadCompleted: (Boolean) -> Unit = {}
    ) {
        startTransferService()
        val isSecure = isChatSecure(targetChatId) || (virtualFolderId != null && isVirtualFolderSecure(virtualFolderId))
        val password = secureSessionManager.decryptedPassword.value
        if (isSecure && password == null) {
            if (backupTrackingKey != null) inFlightBackupKeys.remove(backupTrackingKey)
            scope.launch(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.unlock_to_upload), Toast.LENGTH_LONG).show() }
            return
        }
        // The session password is being used: reset its inactivity timer.
        if (password != null) secureSessionManager.touchSession()

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
                            input.copyTo(output, bufferSize = 512 * 1024)
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
                    if (backupTrackingKey != null) inFlightBackupKeys.remove(backupTrackingKey)
                    transferRepository.updateTransferManual(tempId, 0f, TransferRepository.Status.FAILED)
                    try { onUploadCompleted(false) } catch (_: Exception) {}
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
                    TdApi.InputMessageDocument(TdApi.InputFileLocal(finalPath), inputThumb, true, TdApi.FormattedText(finalCaption, emptyArray()))
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

                    if (!virtualFolderId.isNullOrEmpty() && virtualFolderId != "0") {
                        synchronized(manifestLock) {
                            currentManifest = compactManifest(
                                currentManifest.copy(
                                    fileMappings = currentManifest.fileMappings + (result.id.toString() to virtualFolderId)
                                )
                            )
                        }
                        saveCloudManifestDebounced()
                    }

                    val file = extractTdFileFromMessage(result)
                    if (file != null) {
                        transferRepository.updateRemoteUniqueId(tempId, file.remote.uniqueId, file.id, finalPath)
                        if (file.remote.isUploadingCompleted) {
                            transferRepository.completeAndCleanupTransfer(file.remote.uniqueId, tempId, file.id, success = true)
                            if (backupTrackingKey != null) {
                                pendingBackupKeys.remove(result.id)
                                inFlightBackupKeys.remove(backupTrackingKey)
                                markFileBackedUp(backupTrackingKey)
                            }
                            if (deleteSourceAfter && !isTempCacheFile) {
                                pendingAutoDeleteFiles.remove(result.id)
                                deletePhoneFileCompletely(originalSourceFile)
                            }
                        }
                    } else {
                        if (backupTrackingKey != null) inFlightBackupKeys.remove(backupTrackingKey)
                        transferRepository.completeAndCleanupTransfer(tempId, success = false)
                    }
                } else {
                    if (backupTrackingKey != null) inFlightBackupKeys.remove(backupTrackingKey)
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
                if (!completedSuccess && backupTrackingKey != null) {
                    inFlightBackupKeys.remove(backupTrackingKey)
                }
            } catch (e: Exception) {}
            try { onUploadCompleted(completedSuccess) } catch (_: Exception) {}
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

    private fun sendSplitMasterMetadata(groupId: String, name: String, size: Long, mime: String, parts: Int, vfId: String?, enc: Boolean, ids: List<Long>) {
        ensureSavedMessagesChat { chatId ->
            val master = SplitFileMaster(groupId, name, size, mime, parts, vfId, enc, ids)
            val text = "$FILE_META_PREFIX\n\n${json.encodeToString(master)}"
            telegramClient.send(TdApi.SendMessage(chatId, null, null, null, null, TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true))) { result ->
                val finalMaster = if (result is TdApi.Message) master.copy(metadataMessageId = result.id) else master
                synchronized(manifestLock) {
                    currentManifest = compactManifest(currentManifest.copy(splitFileMasters = currentManifest.splitFileMasters + (groupId to finalMaster)))
                }
                saveCloudManifestDebounced()
            }
        }
    }

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
                return@launch
            }

            telegramClient.send(TdApi.GetMessage(chatId, messageId)) { result ->
                if (result is TdApi.Message) {
                    val file = extractTdFileFromMessage(result)
                    if (file != null && file.id != 0) {
                        if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty() && File(file.local.path).exists()) {
                            scope.launch {
                                driveDao.updateFileLocalPaths(file.id, file.remote.uniqueId, file.local.path)
                            }
                            return@send
                        }
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
                    }
                }
            }
        }
    }

    fun downloadForPreview(messageId: Long, chatId: Long, fileName: String) {
        scope.launch {
            val cachedEntity = driveDao.getItemById(messageId, chatId)
            if (verifyOfflinePath(cachedEntity?.localPath) != null) return@launch

            if (cachedEntity != null && cachedEntity.telegramFileId != 0) {
                telegramClient.send(TdApi.DownloadFile(cachedEntity.telegramFileId, 32, 0, 0, false))
                return@launch
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
                    if (password != null) {
                        secureSessionManager.touchSession()
                        encryptionManager.decryptFile(source, dest, password) { }
                    }
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
                synchronized(manifestLock) {
                    currentManifest = compactManifest(currentManifest.copy(virtualFolders = currentManifest.virtualFolders + (virtualId to folder.copy(name = newName))))
                }
                saveCloudManifestDebounced()
                driveDao.renameItem(item.id, item.parentChatId, newName)
            } else if (item is DriveItem.File) {
                val vfTag = if (item.virtualParentId != "0") " [VF:${item.virtualParentId}]" else ""
                telegramClient.send(TdApi.EditMessageCaption(item.parentChatId, item.id, null, TdApi.FormattedText("$newName$vfTag", emptyArray()), false)) { editResult ->
                    if (editResult is TdApi.Message) {
                        scope.launch { driveDao.renameItem(item.id, item.parentChatId, newName) }
                    } else {
                        // Telegram rejected the rename: leave the local
                        // name unchanged so phone and Telegram stay in sync.
                        Log.w("DriveRepo", "Rename failed for message ${item.id}: $editResult")
                    }
                }
            } else {
                // Non-virtual folders have no Telegram caption to edit.
                driveDao.renameItem(item.id, item.parentChatId, newName)
            }
        }
    }

    fun permanentlyDeleteItems(items: List<DriveItem>) {
        scope.launch {
            val filesByChat = items.filterIsInstance<DriveItem.File>().groupBy { it.parentChatId }
            filesByChat.forEach { (chatId, files) ->
                val ids = files.map { it.id }.toLongArray()
                telegramClient.send(TdApi.DeleteMessages(chatId, ids, true)) { result ->
                    if (result is TdApi.Ok) {
                        scope.launch {
                            files.forEach { f -> driveDao.deleteItemCompletely(f.id, chatId) }
                            // Only drop the manifest mappings once Telegram
                            // confirmed the delete; otherwise the files stay
                            // mapped and visible, and the delete can be retried.
                            val metadataIdsToDelete = mutableListOf<Long>()
                            synchronized(manifestLock) {
                                val updatedFileMappings = currentManifest.fileMappings.toMutableMap()
                                val updatedSplitMasters = currentManifest.splitFileMasters.toMutableMap()
                                files.forEach { file ->
                                    updatedFileMappings.remove(file.id.toString())
                                    file.splitGroupId?.let { gid ->
                                        updatedSplitMasters.remove(gid)?.metadataMessageId?.let { metadataIdsToDelete.add(it) }
                                    }
                                }
                                currentManifest = compactManifest(currentManifest.copy(
                                    fileMappings = updatedFileMappings,
                                    splitFileMasters = updatedSplitMasters,
                                    updatedAt = System.currentTimeMillis() / 1000
                                ))
                            }
                            if (metadataIdsToDelete.isNotEmpty() && savedMessagesChatId != 0L) {
                                telegramClient.send(TdApi.DeleteMessages(savedMessagesChatId, metadataIdsToDelete.toLongArray(), true)) {}
                            }
                            saveCloudManifestDebounced()
                        }
                    } else {
                        // Telegram refused the delete: keep the local rows
                        // so the files stay visible and can be retried.
                        Log.w("DriveRepo", "DeleteMessages failed for chat $chatId: $result")
                    }
                }
            }

            val virtualFolders = items.filterIsInstance<DriveItem.Folder>().filter { it.isVirtual }
            if (virtualFolders.isNotEmpty()) {
                virtualFolders.forEach { folder -> folder.virtualId?.let { vid -> deleteVirtualFolderRecursive(vid) } }
                saveCloudManifestDebounced()
            }
        }
    }

    private suspend fun deleteVirtualFolderRecursive(virtualId: String) {
        val children = driveDao.getItemsByVirtualParentSync(virtualId)
        val filesByChat = children.filter { !it.isFolder }.groupBy { it.parentChatId }
        var allSucceeded = true
        filesByChat.forEach { (chatId, entities) ->
            val deleteResult = CompletableDeferred<Boolean>()
            telegramClient.send(TdApi.DeleteMessages(chatId, entities.map { it.id }.toLongArray(), true)) { result ->
                deleteResult.complete(result is TdApi.Ok)
            }
            val ok = withTimeoutOrNull(30_000L) { deleteResult.await() } ?: false
            if (!ok) {
                allSucceeded = false
                Log.w("DriveRepo", "DeleteMessages failed for chat $chatId while deleting folder $virtualId")
            }
        }
        // Only remove local rows when Telegram confirmed every delete;
        // otherwise the folder stays visible and the delete can be retried.
        if (!allSucceeded) return
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

    // P1-5: Move was an empty stub, so Preview/Media "move" did nothing.
    // TDLib has no "move message" call, so a move between chats is a
    // forward + delete: the message is copied to the target chat
    // (sendCopy, so it lands without a "Forwarded from" header and with
    // its caption kept) and the source is deleted only after Telegram
    // confirms the copy. Local rows and manifest mappings are re-pointed
    // at the new message ids, and downloaded/starred/encrypted state is
    // carried over (matched by remote unique id — a forwarded copy is
    // the same file). If the source delete fails, the file simply exists
    // in both chats and the move can be retried; nothing is lost.
    fun moveItems(fromChatId: Long, toChatId: Long, messageIds: List<Long>) {
        if (messageIds.isEmpty() || fromChatId == 0L || toChatId == 0L || fromChatId == toChatId) return
        scope.launch {
            val entities = driveDao.getItemsByIds(messageIds, fromChatId).filter { !it.isFolder }
            if (entities.isEmpty()) {
                Log.w("DriveRepo", "Move: no local items found for ${messageIds.size} id(s) in chat $fromChatId")
                return@launch
            }
            moveMessagesInternal(fromChatId, toChatId, entities)
        }
    }

    // P1-5: Move every file stored in one folder chat into another chat.
    // The source chat itself is left in place (now empty) — leaving or
    // deleting a Telegram chat has no established pattern in this codebase
    // and is a destructive step the user should take in Telegram if wanted.
    fun moveFolderContentsAndDelete(fromFolderChatId: Long, toChatId: Long) {
        if (fromFolderChatId == 0L || toChatId == 0L || fromFolderChatId == toChatId) return
        scope.launch {
            val entities = driveDao.getItemsSync(fromFolderChatId).filter { !it.isFolder && !it.isVirtual }
            if (entities.isEmpty()) return@launch
            moveMessagesInternal(fromFolderChatId, toChatId, entities)
        }
    }

    // P1-5: Move files between virtual folders without touching Telegram:
    // only the manifest mapping and the local virtualParentId change, then
    // the manifest is saved. The caption [VF:] tag is left alone — the
    // manifest mapping wins over the tag when the file is next mapped
    // (see getAndEnsureVirtualFolder).
    fun moveItemsToVirtualFolder(messageIds: List<Long>, chatId: Long, virtualFolderId: String) {
        if (messageIds.isEmpty() || chatId == 0L) return
        scope.launch {
            val entities = driveDao.getItemsByIds(messageIds, chatId).filter { !it.isFolder }
            if (entities.isEmpty()) return@launch
            synchronized(manifestLock) {
                val updatedMappings = currentManifest.fileMappings.toMutableMap()
                entities.forEach { updatedMappings[it.id.toString()] = virtualFolderId }
                currentManifest = compactManifest(currentManifest.copy(
                    fileMappings = updatedMappings,
                    updatedAt = System.currentTimeMillis() / 1000
                ))
            }
            entities.forEach { driveDao.updateVirtualParent(it.id, virtualFolderId) }
            saveCloudManifestDebounced()
        }
    }

    // P1-5: forward-then-delete move shared by moveItems and
    // moveFolderContentsAndDelete. Returns true only when every movable
    // message now lives solely in the target chat.
    private suspend fun moveMessagesInternal(fromChatId: Long, toChatId: Long, entities: List<DriveItemEntity>): Boolean {
        // Split files are stored as several part messages plus a master
        // metadata message listing their ids; forwarding the parts would
        // orphan that master record, so split files are skipped.
        val (movable, split) = entities.partition { it.splitGroupId == null }
        if (split.isNotEmpty()) {
            Log.w("DriveRepo", "Move: skipping ${split.size} split-file part(s); split files cannot be moved yet")
        }
        if (movable.isEmpty()) return false
        val ids = movable.map { it.id }.toLongArray()

        val options = TdApi.MessageSendOptions().apply {
            disableNotification = false
            fromBackground = false
        }
        val forwardResult = CompletableDeferred<TdApi.Object?>()
        telegramClient.send(TdApi.ForwardMessages(toChatId, null, fromChatId, ids, options, true, false)) { result ->
            forwardResult.complete(result)
        }
        val forwarded = withTimeoutOrNull(60_000L) { forwardResult.await() } as? TdApi.Messages
        if (forwarded == null) {
            Log.w("DriveRepo", "Move: forward to chat $toChatId failed or timed out; sources left untouched")
            return false
        }

        val oldByUniqueId = movable.filter { !it.remoteUniqueId.isNullOrEmpty() }.associateBy { it.remoteUniqueId!! }
        val pairs = forwarded.messages.mapNotNull { message -> mapMessageToEntity(toChatId, message) }
            .mapIndexedNotNull { index, newEntity ->
                val old = newEntity.remoteUniqueId?.let { oldByUniqueId[it] } ?: movable.getOrNull(index)
                if (old == null) null else old to newEntity.copy(
                    isStarred = old.isStarred,
                    localPath = verifyOfflinePath(old.localPath) ?: newEntity.localPath,
                    thumbnailPath = verifyOfflinePath(old.thumbnailPath) ?: newEntity.thumbnailPath,
                    virtualParentId = old.virtualParentId,
                    isSecure = old.isSecure,
                    isEncrypted = old.isEncrypted
                )
            }
        if (pairs.isEmpty()) {
            Log.w("DriveRepo", "Move: forwarded ${forwarded.messages.size} message(s) but none could be mapped locally; sources left untouched")
            return false
        }
        driveDao.insertItems(pairs.map { it.second })

        // Re-point manifest mappings and file-metadata links at the new
        // ids. The old mappings stay until the source delete succeeds, so
        // a failed delete never orphans the source copy's folder.
        synchronized(manifestLock) {
            val updatedMappings = currentManifest.fileMappings.toMutableMap()
            val updatedFileMeta = currentManifest.fileMetadataIds.toMutableMap()
            pairs.forEach { (old, newEntity) ->
                val vfId = updatedMappings[old.id.toString()] ?: old.virtualParentId
                if (vfId != null && vfId != "0") updatedMappings[newEntity.id.toString()] = vfId
                updatedFileMeta[old.id.toString()]?.let { metaId -> updatedFileMeta[newEntity.id.toString()] = metaId }
            }
            currentManifest = compactManifest(currentManifest.copy(
                fileMappings = updatedMappings,
                fileMetadataIds = updatedFileMeta,
                updatedAt = System.currentTimeMillis() / 1000
            ))
        }
        saveCloudManifestDebounced()

        val deleteResult = CompletableDeferred<Boolean>()
        telegramClient.send(TdApi.DeleteMessages(fromChatId, pairs.map { it.first.id }.toLongArray(), true)) { result ->
            deleteResult.complete(result is TdApi.Ok)
        }
        val deleted = withTimeoutOrNull(30_000L) { deleteResult.await() } ?: false
        if (!deleted) {
            Log.w("DriveRepo", "Move: forwarded ${pairs.size} message(s) to chat $toChatId but deleting the sources failed; the files now exist in both chats")
            return false
        }
        pairs.forEach { (old, _) -> driveDao.deleteItemCompletely(old.id, fromChatId) }
        synchronized(manifestLock) {
            val updatedMappings = currentManifest.fileMappings.toMutableMap()
            val updatedFileMeta = currentManifest.fileMetadataIds.toMutableMap()
            pairs.forEach { (old, _) ->
                updatedMappings.remove(old.id.toString())
                updatedFileMeta.remove(old.id.toString())
            }
            currentManifest = compactManifest(currentManifest.copy(
                fileMappings = updatedMappings,
                fileMetadataIds = updatedFileMeta,
                updatedAt = System.currentTimeMillis() / 1000
            ))
        }
        saveCloudManifestDebounced()
        return true
    }

    // P1-17: the old flow re-walked cacheDir/filesDir/externalCacheDir
    // every 10 seconds for as long as Settings was open. The size is now
    // computed on demand on the IO scope, cached, and refreshed at most
    // every 15 minutes; call refreshInternalCacheSize() after clearing the
    // cache to update the displayed value immediately.
    private val internalCacheSize = MutableStateFlow(0L)
    @Volatile
    private var lastInternalCacheSizeComputedAtMs = 0L
    private var internalCacheSizeJob: Job? = null
    private val internalCacheSizeLock = Any()

    fun getInternalCacheSize(): Flow<Long> {
        maybeRefreshInternalCacheSize()
        return internalCacheSize.asStateFlow()
    }

    fun refreshInternalCacheSize() {
        synchronized(internalCacheSizeLock) {
            if (internalCacheSizeJob?.isActive == true) return
            internalCacheSizeJob = scope.launch {
                lastInternalCacheSizeComputedAtMs = System.currentTimeMillis()
                internalCacheSize.value = calculateTotalStorage()
            }
        }
    }

    private fun maybeRefreshInternalCacheSize() {
        if (System.currentTimeMillis() - lastInternalCacheSizeComputedAtMs > CACHE_SIZE_REFRESH_INTERVAL_MS) {
            refreshInternalCacheSize()
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
    
    fun getTotalStorageUsed(): Flow<Long> = driveDao.getFileSizeRows().map { rows ->
        var totalSize = 0L
        val processedGroups = mutableSetOf<String>()
        rows.forEach { row ->
            if (row.splitGroupId == null) totalSize += row.size
            else if (!processedGroups.contains(row.splitGroupId)) {
                val master = currentManifest.splitFileMasters[row.splitGroupId]
                totalSize += master?.totalSize ?: (row.size * row.totalParts)
                processedGroups.add(row.splitGroupId)
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
            .replace(Regex("\\s*\\[VF:[^]]+]"), "")
    }

    private data class SplitMetadata(val groupId: String, val partIndex: Int, val totalParts: Int, val originalName: String)
}
