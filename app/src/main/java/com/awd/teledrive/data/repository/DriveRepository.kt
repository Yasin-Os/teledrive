package com.awd.teledrive.data.repository

import android.content.ContentUris
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
import com.awd.teledrive.data.local.FileHashEntity
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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
        // H14: separate prefs set ("key|mtime" entries) tracking the
        // last-modified time of each backed-up file, so a file edited
        // without a size change is backed up again.
        private const val KEY_BACKED_UP_MTIME = "backed_up_mtime_v1"
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

                        // The id TDLib returns in a send callback is a
                        // temporary local id; the final server id only
                        // arrives here. Fix up every place that recorded
                        // the temp id, or later edits/deletes target a
                        // message that does not exist (MESSAGE_NOT_FOUND).
                        if (oldId != 0L && oldId != msg.id) {
                            if (manifestMessageId == oldId) manifestMessageId = msg.id
                            pendingManifestPartTempIds.remove(oldId)?.let { (generation, partIndex) ->
                                // The final id can arrive before the chunked
                                // save's completion block adopts the
                                // generation: then the temp id is not in
                                // manifestPartMessageIds yet, so stash it for
                                // the completion block to pick up.
                                if (manifestPartMessageIds[partIndex] == oldId) {
                                    manifestPartMessageIds[partIndex] = msg.id
                                } else {
                                    manifestPartFinalIds[oldId] = msg.id
                                }
                            }
                            pendingSplitPartTempIds.remove(oldId)?.let { (groupId, index) ->
                                splitUploads[groupId]?.let { state ->
                                    synchronized(state) { state.finalPartIds[index] = msg.id }
                                    trySendSplitMaster(state)
                                }
                            }
                            // A split-master metadata message also
                            // carries its temp id until this update.
                            fixSplitMasterMetadataId(oldId, msg.id)
                            synchronized(manifestLock) {
                                val vf = currentManifest.fileMappings[oldId.toString()]
                                if (vf != null) {
                                    val updated = currentManifest.fileMappings.toMutableMap()
                                    updated.remove(oldId.toString())
                                    updated[msg.id.toString()] = vf
                                    currentManifest = compactManifest(currentManifest.copy(fileMappings = updated))
                                    saveCloudManifestDebounced()
                                }
                            }
                        }

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
                // A file that just succeeded is no longer failed.
                val failed = customPrefs.getStringSet("backup_failed_v1", emptySet())?.toMutableSet() ?: mutableSetOf()
                if (failed.remove(fileKey)) {
                    customPrefs.edit().putStringSet("backup_failed_v1", failed).apply()
                }
            }
            // Last *successful* backup: only stamped when a file really
            // finished uploading (unlike last_backup_time, which is also
            // stamped after a run where nothing succeeded).
            // H14: record the file's last-modified time too, so a later
            // edit (same size) is detected and re-backed-up.
            recordBackupMtime(fileKey)
            val now = System.currentTimeMillis()
            secureSettings.saveLong("last_successful_backup_time", now)
            // Data uploaded this month (key "path_length" carries the size).
            fileKey.substringAfterLast("_").toLongOrNull()?.let { size ->
                val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(now))
                val bytesKey = "backup_bytes_$month"
                secureSettings.saveLong(bytesKey, secureSettings.getLong(bytesKey, 0L) + size)
            }
        } catch (e: Exception) {}
    }

    /** Remember a backup file key ("path_length") that failed to upload, so the backup health dashboard can offer a retry. */
    private fun recordBackupFailed(fileKey: String) {
        try {
            val customPrefs = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
            synchronized(this) {
                val failed = customPrefs.getStringSet("backup_failed_v1", emptySet())?.toMutableSet() ?: mutableSetOf()
                if (failed.add(fileKey)) {
                    customPrefs.edit().putStringSet("backup_failed_v1", failed).apply()
                }
            }
        } catch (e: Exception) { Log.w("DriveRepo", "markFileBackedUp failed", e) }
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
        // L13: a genuine 0-byte file is still a real offline file — the
        // old length check made it look missing. And a negative result
        // must not be cached: a download finishing a moment later would
        // keep reading the stale "missing" answer for the whole TTL.
        val f = File(path)
        val result = if (f.exists()) path else null
        if (offlinePathCache.size > 2000) offlinePathCache.clear()
        if (result != null) offlinePathCache[path] = result to now
        return result
    }

    private val MANIFEST_PREFIX = "#TELEDRIVE_MANIFEST"
    private val MANIFEST_BACKUP_PREFIX = "#TELEDRIVE_MANIFEST_BACKUP_"
    private val VFOLDER_META_PREFIX = "#TELEDRIVE_VFOLDER_DATA"
    private val FILE_META_PREFIX = "#TELEDRIVE_FILE_DATA"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // M8: updatedAt = 0 until the first fetch/mutation (a fresh TeleDriveManifest() defaults updatedAt to 'now', which would wrongly outrank a newer cloud manifest in mergeManifests' recency check).
    private var currentManifest = TeleDriveManifest(updatedAt = 0)
    // Dedicated lock for manifest mutations. Never synchronize on
    // currentManifest itself: it is a var that gets reassigned, so the
    // lock object would change under the threads using it.
    private val manifestLock = Any()
    // L17: written from TDLib callbacks, read from IO scope — without
    // @Volatile the read could see a stale 0 and re-save the manifest.
    @Volatile private var manifestMessageId: Long = 0

    // Manifest transport limits: a Telegram text message caps at 4096
    // chars. A manifest that no longer fits in one message is saved as
    // numbered parts instead of being silently dropped (H9).
    private val MANIFEST_PART_PREFIX = "#TELEDRIVE_MANIFEST_PART"
    // H9 backup follow-up: chunked manual backups use the same header
    // format as the live manifest (`<prefix> <generation> <index>/<total>`)
    // and the same splitManifestJson, but a backup-specific prefix, so
    // the live reader never mistakes a backup part for a live-manifest
    // part (and vice versa). It starts with MANIFEST_BACKUP_PREFIX, so
    // the existing backup search finds it.
    private val MANIFEST_BACKUP_PART_PREFIX = "#TELEDRIVE_MANIFEST_BACKUP_PART"
    private val MANIFEST_SINGLE_MESSAGE_LIMIT = 4000
    private val MANIFEST_CHUNK_SIZE = 3500
    // Chunked-manifest bookkeeping: part index -> message id of the
    // newest complete generation, plus that generation's id. The ids
    // TDLib returns in send callbacks are temporary local ids; the final
    // server ids arrive via UpdateMessageSendSucceeded (see the init
    // collector) and replace them here.
    private val manifestPartMessageIds = ConcurrentHashMap<Int, Long>()
    private var manifestPartGeneration: Long = 0L
    private var lastManifestPartGeneration: Long = 0L
    // H9 backup follow-up: newest chunked-backup generation, so two
    // manual backups in the same millisecond never share a generation
    // (same guard as the live manifest's lastManifestPartGeneration).
    private var lastManualBackupGeneration: Long = 0L
    // Temporary local message id -> (chunk generation, part index), so
    // UpdateMessageSendSucceeded can fix a chunk id up to its final id.
    private val pendingManifestPartTempIds = ConcurrentHashMap<Long, Pair<Long, Int>>()
    // Temporary local message id -> final server id, for chunk parts
    // whose UpdateMessageSendSucceeded arrived BEFORE the generation was
    // adopted (the completion block picks these up when it adopts).
    private val manifestPartFinalIds = ConcurrentHashMap<Long, Long>()

    // In-flight split uploads (C11): one accumulator per group, holding
    // the final (server-assigned) part message ids and per-part success.
    // The master record is sent only once every part has both uploaded
    // successfully AND its final id is known.
    private class SplitUploadState(
        val groupId: String,
        val cleanName: String,
        val totalSize: Long,
        val mimeType: String,
        val totalParts: Int,
        val virtualFolderId: String?,
        val isEncrypted: Boolean,
        val backupTrackingKey: String?,
        val autoDeleteEnabled: Boolean,
        val sourceFile: File
    ) {
        // Final Telegram message ids, by part index; null until the
        // UpdateMessageSendSucceeded for that part arrives.
        val finalPartIds = arrayOfNulls<Long>(totalParts)
        val partOk = BooleanArray(totalParts)
        var completedCount = 0
        var masterSent = false
    }
    private val splitUploads = ConcurrentHashMap<String, SplitUploadState>()
    // Temporary local message id -> (split group id, part index), so the
    // UpdateMessageSendSucceeded handler can record the final part id.
    private val pendingSplitPartTempIds = ConcurrentHashMap<Long, Pair<String, Int>>()

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

    /**
     * H14: one backup candidate. [file] is the local file to upload — null
     * for SAF targets when the scan was asked not to materialize copies
     * (health check): only [key], [name], [size] and [lastModified] are
     * meaningful then. [sourceFolder] is the backup folder the target
     * belongs to (used to group pending files in the health snapshot).
     */
    data class BackupTargetFile(
        val key: String,
        val file: File?,
        val name: String,
        val size: Long,
        val lastModified: Long,
        val sourceFolder: String
    )

    /**
     * H14: dedup is not just "key in backedUpSet" — the key is
     * path_length, so a file edited without a size change was never
     * re-backed-up. This checker also tracks last-modified times in a
     * separate prefs set (entries "key|mtime"): a key with no recorded
     * mtime is grandfathered (recorded now, treated as backed-up); a
     * changed mtime means the file was edited and is pending again.
     */
    private inner class BackupDedupChecker(
        private val backedUp: Set<String>,
        private val mtimes: MutableMap<String, Long>
    ) {
        private val grandfathers = mutableMapOf<String, Long>()

        fun isBackedUp(key: String, lastModified: Long): Boolean {
            if (!backedUp.contains(key)) return false
            val recorded = mtimes[key]
            if (recorded == null) {
                grandfathers[key] = lastModified
                return true
            }
            return recorded == lastModified
        }

        fun flushGrandfathers() {
            if (grandfathers.isEmpty()) return
            try {
                val customPrefs = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
                val current = (customPrefs.getStringSet(KEY_BACKED_UP_MTIME, emptySet()) ?: emptySet()).toMutableSet()
                grandfathers.forEach { (key, mtime) -> current.add("$key|$mtime") }
                customPrefs.edit().putStringSet(KEY_BACKED_UP_MTIME, current).apply()
            } catch (_: Exception) { }
        }
    }

    private fun readBackupMtimes(): MutableMap<String, Long> {
        val out = mutableMapOf<String, Long>()
        try {
            val customPrefs = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
            val entries = customPrefs.getStringSet(KEY_BACKED_UP_MTIME, emptySet()) ?: emptySet()
            entries.forEach { entry ->
                val mtime = entry.substringAfterLast("|").toLongOrNull() ?: return@forEach
                out[entry.substringBeforeLast("|")] = mtime
            }
        } catch (_: Exception) { }
        return out
    }

    /** Best-effort last-modified for a backup key: regular file or SAF doc. */
    private fun backupMtimeForKey(key: String): Long? {
        return try {
            if (key.startsWith("doc_")) {
                val uriStr = key.removePrefix("doc_").substringBeforeLast("_")
                DocumentFile.fromSingleUri(context, Uri.parse(uriStr))?.lastModified()?.takeIf { it > 0L }
            } else {
                val path = key.substringBeforeLast("_")
                File(path).let { if (it.exists()) it.lastModified() else null }?.takeIf { it > 0L }
            }
        } catch (_: Exception) { null }
    }

    /** Record the current last-modified time for a key that just backed up. */
    private fun recordBackupMtime(key: String) {
        val mtime = backupMtimeForKey(key) ?: return
        try {
            val customPrefs = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
            val current = (customPrefs.getStringSet(KEY_BACKED_UP_MTIME, emptySet()) ?: emptySet()).toMutableSet()
            current.removeAll { it.substringBeforeLast("|") == key }
            current.add("$key|$mtime")
            customPrefs.edit().putStringSet(KEY_BACKED_UP_MTIME, current).apply()
        } catch (_: Exception) { }
    }

    /**
     * H14: SAF temp copies from earlier runs (crashed/interrupted, or left
     * behind before this fix) used to pile up in cacheDir forever. Sweep
     * ones older than an hour — no single-file upload runs longer than
     * that, so nothing in flight is touched.
     */
    private fun sweepStaleSafTempFiles() {
        try {
            val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith("saf_") && f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Exception) { }
    }

    /** Unique SAF temp name: a hash of the doc URI stops same-name files in different subfolders from overwriting each other. */
    private fun safTempName(uri: Uri, name: String): String {
        val hash = uri.toString().hashCode().toUInt().toString(16)
        val safeName = name.replace('/', '_').replace(Regex("[\u0000-\u001F]"), "_")
        return "saf_${hash}_$safeName"
    }

    /**
     * H14: delete a SAF temp copy once its upload is recorded as backed up.
     * Called after uploadFileAndWait returns; if the record is not there
     * yet (the message-send callback can lag the transfer), the hourly
     * stale-temp sweep above picks it up on the next scan.
     */
    fun cleanupSafTempFile(target: BackupTargetFile) {
        val file = target.file ?: return
        if (!target.key.startsWith("doc_")) return
        try {
            val customPrefs = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
            val backedUpSet = customPrefs.getStringSet("backed_up_paths_v3", emptySet()) ?: emptySet()
            if (backedUpSet.contains(target.key) && file.absolutePath.startsWith(context.cacheDir.absolutePath)) {
                file.delete()
            }
        } catch (_: Exception) { }
    }

    /**
     * Collects the files that still need backing up. [materializeSafCopies]
     * controls whether SAF docs are copied to cacheDir temp files (the
     * upload paths need real files) or reported without a copy (the health
     * snapshot only counts them — H14: opening Backup Health used to copy
     * GBs of SAF files into cacheDir and never delete them).
     */
    fun collectTargetBackupFiles(rawFolders: List<String>, materializeSafCopies: Boolean = true): List<BackupTargetFile> {
        val customPrefs = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
        val backedUpSet = customPrefs.getStringSet("backed_up_paths_v3", emptySet()) ?: emptySet()
        val dedup = BackupDedupChecker(backedUpSet, readBackupMtimes())
        val foundFiles = LinkedHashMap<String, BackupTargetFile>()

        sweepStaleSafTempFiles()

        val normalizedPaths = rawFolders.map { normalizeFolderPath(it) }.filter { it.isNotEmpty() }

        normalizedPaths.forEach { path ->
            val dir = File(path)
            if (dir.exists()) {
                if (dir.isDirectory) {
                    scanDirFast(dir, dedup, foundFiles, path)
                } else if (dir.isFile) { // L13: 0-byte files are real files too
                    val key = "${dir.absolutePath}_${dir.length()}"
                    if (!dedup.isBackedUp(key, dir.lastModified()) && !inFlightBackupKeys.contains(key)) {
                        foundFiles[key] = BackupTargetFile(key, dir, dir.name, dir.length(), dir.lastModified(), dir.parent ?: path)
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

        // The deprecated DATA column is gone: reconstruct the file
        // path from _ID + DISPLAY_NAME + RELATIVE_PATH instead.
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
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
                    val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    if (idIdx != -1 && nameIdx != -1) {
                        while (cursor.moveToNext()) {
                            val displayName = cursor.getString(nameIdx) ?: continue
                            // RELATIVE_PATH is null/-1 for files at the
                            // storage root and ends with '/' otherwise.
                            val relativePath = if (relIdx != -1) cursor.getString(relIdx).orEmpty() else ""
                            val relDir = if (relativePath.isEmpty() || relativePath.endsWith("/")) relativePath else "$relativePath/"
                            val filePath = File(Environment.getExternalStorageDirectory(), relDir + displayName).absolutePath
                            if (normalizedPaths.any { folder -> filePath.startsWith("$folder/") || filePath == folder }) {
                                val f = File(filePath)
                                if (f.exists() && f.isFile && !f.name.startsWith(".")) { // L13: no length gate
                                    val key = "${f.absolutePath}_${f.length()}"
                                    if (!dedup.isBackedUp(key, f.lastModified()) && !inFlightBackupKeys.contains(key)) {
                                        foundFiles[key] = BackupTargetFile(key, f, f.name, f.length(), f.lastModified(), f.parent ?: "")
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) { Log.w("DriveRepo", "collectTargetBackupFiles failed", e) }
        }

        rawFolders.filter { it.startsWith("content://") }.forEach { uriStr ->
            try {
                val treeUri = Uri.parse(uriStr)
                val docDir = DocumentFile.fromTreeUri(context, treeUri)
                if (docDir != null && docDir.exists()) {
                    scanDocumentTreeFallback(docDir, dedup, foundFiles, materializeSafCopies, normalizeFolderPath(uriStr))
                }
            } catch (e: Exception) { Log.w("DriveRepo", "collectTargetBackupFiles failed", e) }
        }

        dedup.flushGrandfathers()
        return foundFiles.values.sortedBy { it.lastModified }
    }

    private fun scanDirFast(dir: File, dedup: BackupDedupChecker, out: MutableMap<String, BackupTargetFile>, sourceFolder: String) {
        val list = dir.listFiles() ?: return
        for (child in list) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                scanDirFast(child, dedup, out, sourceFolder)
            } else if (child.isFile) { // L13: 0-byte files are real files too
                val key = "${child.absolutePath}_${child.length()}"
                if (!dedup.isBackedUp(key, child.lastModified()) && !inFlightBackupKeys.contains(key)) {
                    out[key] = BackupTargetFile(key, child, child.name, child.length(), child.lastModified(), sourceFolder)
                }
            }
        }
    }

    private fun scanDocumentTreeFallback(docDir: DocumentFile, dedup: BackupDedupChecker, out: MutableMap<String, BackupTargetFile>, materializeCopies: Boolean, sourceFolder: String) {
        for (doc in docDir.listFiles()) {
            val name = doc.name ?: continue
            if (name.startsWith(".")) continue
            if (doc.isDirectory) {
                scanDocumentTreeFallback(doc, dedup, out, materializeCopies, sourceFolder)
            } else if (doc.isFile) { // L13: 0-byte files are real files too
                // H14: the scan key is "doc_<uri>_<length>" — the same key
                // the upload path records, so a backed-up SAF file is not
                // scanned again (the old code re-derived the key from the
                // temp file's path, which never matched).
                val key = "doc_${doc.uri}_${doc.length()}"
                val mtime = doc.lastModified()
                if (!dedup.isBackedUp(key, mtime) && !inFlightBackupKeys.contains(key)) {
                    if (!materializeCopies) {
                        // H14: health checks only count pending files — no
                        // copy is needed, and copying GBs into cacheDir
                        // (never deleted) was the old behaviour.
                        out[key] = BackupTargetFile(key, null, name, doc.length(), mtime, sourceFolder)
                    } else {
                        // H14: the temp used to be named just the doc name —
                        // same-name files in different subfolders overwrote
                        // each other. The name now carries a hash of the URI.
                        val tempFile = File(context.cacheDir, safTempName(doc.uri, name))
                        try {
                            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                                FileOutputStream(tempFile).use { output ->
                                    input.copyTo(output, bufferSize = 512 * 1024)
                                }
                            }
                            if (tempFile.exists()) { // L13: a 0-byte SAF source makes a 0-byte temp
                                out[key] = BackupTargetFile(key, tempFile, name, doc.length(), mtime, sourceFolder)
                            }
                        } catch (e: Exception) { Log.w("DriveRepo", "scanDocumentTreeFallback failed", e) }
                    }
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
        // L15: a concurrent call used to return without invoking
        // onFinished, so its caller waited forever.
        if (!isBackupRunning.compareAndSet(false, true)) {
            onFinished?.invoke()
            return
        }
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

                // L15: remember whether anything really backed up, so the
                // run's end time is only stamped on real progress (below).
                val lastSuccessfulBefore = secureSettings.getLong("last_successful_backup_time", 0L)

                for (target in filesToUpload) {
                    if (!isBackupNetworkAllowedFor(manual)) break
                    val file = target.file ?: continue
                    if (!file.exists()) continue // L13: 0-byte files are real files too

                    // H14: use the scan key — NOT a key re-derived from the
                    // temp file's path (for SAF targets that never matched
                    // the recorded key, so every SAF file re-uploaded
                    // forever). The upload name is the real file name, not
                    // the temp file's name.
                    val bKey = target.key
                    val alreadyDone = customPrefs.getStringSet("backed_up_paths_v3", emptySet())?.contains(bKey) == true
                    if (alreadyDone || !inFlightBackupKeys.add(bKey)) continue

                    uploadFileAndWait(
                        filePath = file.absolutePath,
                        originalFileName = target.name,
                        forceDeleteSource = deleteEnabled,
                        backupTrackingKey = bKey
                    )
                    // H14: a SAF temp copy is deleted once its upload is
                    // recorded (the stale-temp sweep is the backstop).
                    cleanupSafTempFile(target)
                    delay(80)
                }
                // L15: "last backup time" used to be stamped even when the
                // run broke early on network loss or every upload failed.
                // Only stamp it when at least one file really backed up.
                if (secureSettings.getLong("last_successful_backup_time", 0L) != lastSuccessfulBefore) {
                    secureSettings.saveLong("last_backup_time", System.currentTimeMillis())
                }
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
            // M34: both flags and the message list are shared by two
            // concurrent TDLib callbacks. Guard every read and write with
            // the same lock, and finish exactly once: previously the
            // flags were unsynchronized and the list was read outside
            // synchronized(allMessages), so the fetch could complete
            // twice, miss messages, or throw ConcurrentModificationException.
            var finished = false

            fun tryFinish() {
                val snapshot: List<TdApi.Message> = synchronized(allMessages) {
                    if (finished || !searchDone || !historyDone) return
                    finished = true
                    allMessages.distinctBy { it.id }
                }
                processManifestMessages(snapshot)
                onComplete()
            }

            val searchRequest = TdApi.SearchChatMessages().apply {
                this.chatId = chatId
                this.query = MANIFEST_PREFIX
                this.limit = 30
            }
            telegramClient.send(searchRequest) { result ->
                synchronized(allMessages) {
                    if (result is TdApi.Messages) allMessages.addAll(result.messages.toList())
                    searchDone = true
                }
                tryFinish()
            }
            telegramClient.send(TdApi.GetChatHistory(chatId, 0, 0, 50, false)) { result ->
                synchronized(allMessages) {
                    if (result is TdApi.Messages) allMessages.addAll(result.messages.toList())
                    historyDone = true
                }
                tryFinish()
            }
        }
    }

    private fun processManifestMessages(messages: List<TdApi.Message>) {
        val texts = messages.mapNotNull { msg ->
            val content = msg.content
            if (content is TdApi.MessageText) msg to content.text.text else null
        }
        // Chunked manifests (H9) are reassembled first: only the newest
        // complete generation counts, and chunk messages must never be
        // mistaken for single-manifest messages below.
        val chunked = reassembleChunkedManifest(texts)
        val possibleManifests = messages.filter { msg ->
            val content = msg.content
            if (content is TdApi.MessageText) {
                val text = content.text.text
                !text.startsWith(MANIFEST_PART_PREFIX) &&
                    (text.contains(MANIFEST_PREFIX) || text.contains(MANIFEST_BACKUP_PREFIX) || text.contains("\"virtualFolders\""))
            } else false
        }
        if (possibleManifests.isEmpty() && chunked == null) return

        manifestMessageId = possibleManifests
            .filter { (it.content as? TdApi.MessageText)?.text?.text?.contains(MANIFEST_PREFIX) == true && !(it.content as TdApi.MessageText).text.text.contains(MANIFEST_BACKUP_PREFIX) && !(it.content as TdApi.MessageText).text.text.startsWith(MANIFEST_PART_PREFIX) }
            .maxByOrNull { it.date }?.id ?: 0L

        var mergedManifest = TeleDriveManifest(updatedAt = 0)
        val ordered = mutableListOf<Pair<Int, TeleDriveManifest>>()
        possibleManifests.sortedBy { it.date }.forEach { msg ->
            val content = msg.content
            if (content is TdApi.MessageText) {
                val text = content.text.text
                val jsonStartIndex = text.indexOf('{')
                if (jsonStartIndex != -1) {
                    try {
                        val m = json.decodeFromString<TeleDriveManifest>(text.substring(jsonStartIndex).trim())
                        ordered.add(msg.date to m)
                    } catch (e: Exception) { Log.w("DriveRepo", "processManifestMessages failed", e) }
                }
            }
        }
        // The chunked manifest joins the merge at its newest part's date.
        if (chunked != null) ordered.add(chunked.second to chunked.first)
        ordered.sortBy { it.first }
        ordered.forEach { (_, m) -> mergedManifest = mergeManifests(mergedManifest, m) }
        synchronized(manifestLock) {
            // Re-merge under the lock so a manifest update made while the
            // cloud fetch was in flight is not clobbered by the wholesale
            // replace; cloud data still wins on conflicts (see mergeManifests).
            currentManifest = compactManifest(mergeManifests(currentManifest, mergedManifest))
        }
        syncVirtualFoldersToDb()
    }

    /**
     * H9: one chunked-manifest part message. Header:
     * `#TELEDRIVE_MANIFEST_PART <generation> <index>/<total>`, followed
     * by a slice of the manifest JSON.
     */
    private data class ManifestPart(
        val generation: Long,
        val index: Int,
        val total: Int,
        val payload: String,
        val date: Int
    )

    private fun parseManifestPart(text: String, date: Int): ManifestPart? {
        val newline = text.indexOf('\n')
        if (newline == -1) return null
        val header = text.substring(0, newline).trim().split(' ')
        if (header.size != 3) return null
        val generation = header[1].toLongOrNull() ?: return null
        val indexTotal = header[2].split('/')
        if (indexTotal.size != 2) return null
        val index = indexTotal[0].toIntOrNull() ?: return null
        val total = indexTotal[1].toIntOrNull() ?: return null
        if (index < 1 || total < 1 || index > total) return null
        val payload = text.substring(newline + 1)
        if (payload.isEmpty()) return null
        return ManifestPart(generation, index, total, payload, date)
    }

    /**
     * H9: reassemble the newest COMPLETE generation of chunked-manifest
     * parts. An incomplete generation (a part failed to send) is ignored
     * so a half-written manifest can never corrupt the local state.
     * Returns the manifest and its newest part's date, or null.
     */
    private fun reassembleChunkedManifest(texts: List<Pair<TdApi.Message, String>>): Pair<TeleDriveManifest, Int>? {
        val parts = texts.mapNotNull { (msg, text) ->
            if (!text.startsWith(MANIFEST_PART_PREFIX)) null
            else parseManifestPart(text, msg.date)
        }
        if (parts.isEmpty()) return null
        val byGeneration = parts.groupBy { it.generation }
        val complete = byGeneration.entries.mapNotNull { (generation, genParts) ->
            val total = genParts.first().total
            if (genParts.map { it.index }.toSet().size != total) null
            else {
                // Duplicate part sends: keep the newest copy of each index.
                val byIndex = genParts.groupBy { it.index }
                    .mapValues { (_, same) -> same.maxByOrNull { it.date }!! }
                generation to byIndex
            }
        }
        if (complete.isEmpty()) return null
        val (generation, byIndex) = complete.maxByOrNull { it.first }!!
        val total = byIndex.values.first().total
        val payloads = (1..total).map { index -> byIndex[index]?.payload }
        if (payloads.any { it == null }) return null
        val jsonText = payloads.joinToString("") { it!! }
        return try {
            val manifest = json.decodeFromString<TeleDriveManifest>(jsonText)
            val newestDate = byIndex.values.maxOf { it.date }
            manifest to newestDate
        } catch (e: Exception) {
            Log.w("DriveRepo", "Chunked manifest generation $generation failed to decode", e)
            null
        }
    }

    /**
     * H9 backup follow-up: group parsed chunked-backup parts by
     * generation, keeping the newest copy of each index. The backup
     * list and the chunked restore share this, so one chunked backup
     * is one list entry and a generation is only trusted when every
     * part is present. (Same grouping rule as the live manifest's
     * reassembleChunkedManifest; backup parts are parsed with the same
     * parseManifestPart.)
     */
    private fun groupChunkedBackupParts(parts: List<Pair<Long, ManifestPart>>): Map<Long, Map<Int, Pair<Long, ManifestPart>>> {
        return parts.groupBy { it.second.generation }
            .mapValues { (_, genParts) ->
                genParts.groupBy { it.second.index }
                    .mapValues { (_, same) -> same.maxByOrNull { it.second.date }!! }
            }
    }

    /**
     * H9 backup follow-up: reassemble one grouped generation. Returns
     * the manifest, or null when a part is missing, the parts disagree
     * on the total, or the joined JSON does not decode. Never returns
     * a partial manifest.
     */
    private fun decodeChunkedBackupGeneration(generation: Long, byIndex: Map<Int, Pair<Long, ManifestPart>>): TeleDriveManifest? {
        val total = byIndex.values.firstOrNull()?.second?.total ?: return null
        if (byIndex.values.any { it.second.total != total }) {
            Log.w("DriveRepo", "Chunked backup generation $generation has parts disagreeing on total")
            return null
        }
        if (byIndex.keys.toSet() != (1..total).toSet()) return null
        val jsonText = (1..total).joinToString("") { byIndex[it]!!.second.payload }
        return try {
            json.decodeFromString<TeleDriveManifest>(jsonText)
        } catch (e: Exception) {
            Log.w("DriveRepo", "Chunked backup generation $generation failed to decode", e)
            null
        }
    }

    private fun compactManifest(manifest: TeleDriveManifest): TeleDriveManifest {
        // Drop only dead mappings ("0"/empty targets). fileMetadataIds
        // must survive: moveMessagesInternal re-points it at the new
        // message ids, and wiping it here made that re-pointing dead (M6).
        val compactMappings = manifest.fileMappings.filterValues { it != "0" && it.isNotEmpty() }
        val compactFileMeta = manifest.fileMetadataIds.filterValues { it != 0L }
        return manifest.copy(
            fileMappings = compactMappings,
            fileMetadataIds = compactFileMeta
        )
    }

    private fun mergeManifests(base: TeleDriveManifest, other: TeleDriveManifest): TeleDriveManifest {
        // M8: the manifest with the newer updatedAt wins overlapping
        // keys. A concurrent cloud fetch must not undo local edits that
        // are still inside the 1200 ms save debounce, and a newer cloud
        // manifest must still win. Keys only one side has are always kept.
        // (The per-message accumulation in processManifestMessages starts
        // from updatedAt = 0, so newer messages still override older ones.)
        val otherIsNewer = other.updatedAt >= base.updatedAt
        // Deletion is an event: the latest deletion timestamp wins.
        val mergedDeletedIds = base.deletedFolderIds.toMutableMap()
        other.deletedFolderIds.forEach { (id, deletedAt) ->
            val existing = mergedDeletedIds[id]
            if (existing == null || deletedAt > existing) mergedDeletedIds[id] = deletedAt
        }
        val mergedFolders = base.virtualFolders.toMutableMap()
        other.virtualFolders.forEach { (id, vf) ->
            val deletedAt = mergedDeletedIds[id]
            if (deletedAt != null && vf.createdAt <= deletedAt) {
                mergedFolders.remove(id)
                return@forEach
            }
            if (otherIsNewer || !mergedFolders.containsKey(id)) mergedFolders[id] = vf
        }
        mergedFolders.keys.removeIf { id ->
            val deletedAt = mergedDeletedIds[id]
            deletedAt != null && (mergedFolders[id]?.createdAt ?: 0L) <= deletedAt
        }
        return base.copy(
            updatedAt = maxOf(base.updatedAt, other.updatedAt),
            virtualFolders = mergedFolders,
            fileMappings = mergeMapsByRecency(base.fileMappings, other.fileMappings, otherIsNewer)
                .filterValues { it != "0" && it.isNotEmpty() },
            secureFolderChatIds = base.secureFolderChatIds + other.secureFolderChatIds,
            splitFileMasters = mergeMapsByRecency(base.splitFileMasters, other.splitFileMasters, otherIsNewer),
            folderMetadataIds = mergeMapsByRecency(base.folderMetadataIds, other.folderMetadataIds, otherIsNewer),
            fileMetadataIds = mergeMapsByRecency(base.fileMetadataIds, other.fileMetadataIds, otherIsNewer),
            deletedFolderIds = mergedDeletedIds
        )
    }

    /**
     * M8: merge two maps so the newer manifest wins overlapping keys,
     * while keys only the older side has are still adopted (files added
     * from another device must not be lost).
     */
    private fun <K, V> mergeMapsByRecency(base: Map<K, V>, other: Map<K, V>, otherIsNewer: Boolean): Map<K, V> {
        if (otherIsNewer) return base + other
        val merged = base.toMutableMap()
        other.forEach { (key, value) -> merged.putIfAbsent(key, value) }
        return merged
    }

    fun setCloudItemsSafeState(itemIds: List<Long>, hideInSafeFolder: Boolean) {
        scope.launch {
            val targetVirtualParent = if (hideInSafeFolder) SAFE_SPACE_VIRTUAL_ID else "0"
            // M7: the whole read-modify-write runs under manifestLock.
            // Building the map outside the lock let a rename/move/folder
            // create slip in between the read and the write and be
            // silently overwritten.
            synchronized(manifestLock) {
                val updatedMappings = currentManifest.fileMappings.toMutableMap()
                itemIds.forEach { id ->
                    if (hideInSafeFolder) {
                        updatedMappings[id.toString()] = SAFE_SPACE_VIRTUAL_ID
                    } else {
                        updatedMappings.remove(id.toString())
                    }
                }
                currentManifest = compactManifest(currentManifest.copy(fileMappings = updatedMappings, updatedAt = System.currentTimeMillis() / 1000))
            }
            // The Room update is independent of the manifest lock; it
            // runs after the manifest is consistent again.
            itemIds.forEach { id -> driveDao.updateVirtualParent(id, targetVirtualParent) }
            saveCloudManifestDebounced()
        }
    }

    fun getSafeFolderFilesFlow(): Flow<List<DriveItem.File>> {
        return driveDao.getSafeFolderFiles().map { entities ->
            entities.map { entity ->
                    DriveItem.File(
                        entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType,
                        entity.telegramFileId, verifyOfflinePath(entity.thumbnailPath), verifyOfflinePath(entity.localPath), entity.isStarred,
                        entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted, entity.createdAt
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
                // H9 backup follow-up: one chunked backup is N messages,
                // so 50 slots could cut a generation in half; 100 is
                // TDLib's max for this search.
                this.limit = 100
            }
            telegramClient.send(searchRequest) { result ->
                if (result is TdApi.Messages) {
                    val backups = mutableListOf<CloudBackup>()
                    val partMessages = mutableListOf<Pair<Long, ManifestPart>>()
                    result.messages.forEach { msg ->
                        val content = msg.content
                        if (content is TdApi.MessageText) {
                            val text = content.text.text
                            if (text.startsWith(MANIFEST_BACKUP_PART_PREFIX)) {
                                // H9 backup follow-up: one message per
                                // chunk; grouped below into one entry.
                                parseManifestPart(text, msg.date)?.let { partMessages += msg.id to it }
                            } else if (text.startsWith(MANIFEST_BACKUP_PREFIX)) {
                                val jsonStartIndex = text.indexOf('{')
                                if (jsonStartIndex != -1) {
                                    try {
                                        val manifest = json.decodeFromString<TeleDriveManifest>(text.substring(jsonStartIndex))
                                        backups += CloudBackup(msg.id, msg.date.toLong() * 1000, manifest.virtualFolders.size)
                                    } catch (e: Exception) { /* corrupt single backup: skip */ }
                                }
                            }
                        }
                    }
                    // H9 backup follow-up: one chunked backup is ONE list
                    // entry (grouped by generation). Incomplete or corrupt
                    // generations are hidden, never listed, so the list
                    // never offers a backup that cannot be restored.
                    groupChunkedBackupParts(partMessages).forEach { (generation, byIndex) ->
                        val manifest = decodeChunkedBackupGeneration(generation, byIndex)
                        if (manifest == null) {
                            Log.w("DriveRepo", "Chunked backup generation $generation incomplete or corrupt; hidden from backup list")
                        } else {
                            backups += CloudBackup(byIndex[1]!!.first, generation, manifest.virtualFolders.size)
                        }
                    }
                    onResult(backups.sortedByDescending { it.date })
                } else onResult(emptyList())
            }
        }
    }

    fun restoreManifestFromMessage(messageId: Long, onResult: (Boolean) -> Unit) {
        ensureSavedMessagesChat { chatId ->
            telegramClient.send(TdApi.GetMessage(chatId, messageId)) { result ->
                if (result is TdApi.Message && result.content is TdApi.MessageText) {
                    val text = (result.content as TdApi.MessageText).text.text
                    if (text.startsWith(MANIFEST_BACKUP_PART_PREFIX)) {
                        // H9 backup follow-up: this is one part of a
                        // chunked backup. Resolve its generation and
                        // restore only a complete, decodable generation.
                        val firstPart = parseManifestPart(text, result.date)
                        if (firstPart == null) {
                            Log.e("DriveRepo", "Chunked backup restore failed: part message $messageId has a malformed header")
                            onResult(false)
                        } else {
                            restoreChunkedBackup(chatId, firstPart.generation, onResult)
                        }
                    } else {
                        val jsonStartIndex = text.indexOf('{')
                        if (jsonStartIndex != -1) {
                            try {
                                applyRestoredManifest(json.decodeFromString<TeleDriveManifest>(text.substring(jsonStartIndex)))
                                onResult(true)
                            } catch (e: Exception) { onResult(false) }
                        } else onResult(false)
                    }
                } else onResult(false)
            }
        }
    }

    /**
     * H9 backup follow-up: restore one chunked-backup generation. Every
     * part is fetched and validated first; a missing or corrupt part
     * fails LOUDLY (Log.e + onResult(false)) and nothing is applied —
     * a partial manifest is never restored. The Settings UI already
     * turns onResult(false) into a "restore failed" toast.
     */
    private fun restoreChunkedBackup(chatId: Long, generation: Long, onResult: (Boolean) -> Unit) {
        val searchRequest = TdApi.SearchChatMessages().apply {
            this.chatId = chatId
            this.query = MANIFEST_BACKUP_PART_PREFIX
            this.limit = 100
        }
        telegramClient.send(searchRequest) { result ->
            var restored = false
            if (result is TdApi.Messages) {
                val parts = result.messages.mapNotNull { msg ->
                    val content = msg.content
                    if (content is TdApi.MessageText && content.text.text.startsWith(MANIFEST_BACKUP_PART_PREFIX)) {
                        parseManifestPart(content.text.text, msg.date)?.let { msg.id to it }
                    } else null
                }
                val byIndex = groupChunkedBackupParts(parts)[generation]
                if (byIndex == null) {
                    Log.e("DriveRepo", "Chunked backup restore failed: no parts found for generation $generation")
                } else {
                    val total = byIndex.values.first().second.total
                    val missing = (1..total).filter { it !in byIndex.keys }
                    if (missing.isNotEmpty()) {
                        Log.e("DriveRepo", "Chunked backup restore failed: generation $generation is missing part(s) $missing of $total; refusing partial restore")
                    } else {
                        val manifest = decodeChunkedBackupGeneration(generation, byIndex)
                        if (manifest == null) {
                            Log.e("DriveRepo", "Chunked backup restore failed: generation $generation is corrupt; refusing partial restore")
                        } else {
                            applyRestoredManifest(manifest)
                            restored = true
                        }
                    }
                }
            } else {
                Log.e("DriveRepo", "Chunked backup restore failed: could not list backup parts for generation $generation")
            }
            onResult(restored)
        }
    }

    /**
     * H9 backup follow-up: the apply step shared by the legacy
     * single-message restore and the chunked restore.
     */
    private fun applyRestoredManifest(manifest: TeleDriveManifest) {
        synchronized(manifestLock) {
            currentManifest = compactManifest(manifest)
        }
        saveCloudManifestDebounced()
        syncVirtualFoldersToDb()
        scope.launch { driveDao.resetAllFileVirtualParents(); fetchFiles() }
    }

    fun createManualCloudBackup() {
        ensureSavedMessagesChat { chatId ->
            // H9 backup follow-up: same-millisecond guard as the live
            // manifest, so two taps can never share a generation.
            val backupTime = maxOf(System.currentTimeMillis(), lastManualBackupGeneration + 1)
            lastManualBackupGeneration = backupTime
            val jsonText = json.encodeToString(compactManifest(currentManifest))
            val text = "$MANIFEST_BACKUP_PREFIX$backupTime\n\n$jsonText"
            if (text.length <= MANIFEST_SINGLE_MESSAGE_LIMIT) {
                telegramClient.send(TdApi.SendMessage(chatId, null, null, null, null, TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true)))
            } else {
                // H9 backup follow-up: an oversized manifest is saved as
                // numbered backup parts (same chunk format as the live
                // manifest) instead of being skipped.
                saveChunkedBackup(chatId, backupTime, jsonText)
            }
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
            currentManifest = compactManifest(currentManifest.copy(virtualFolders = currentManifest.virtualFolders + (id to folder), updatedAt = System.currentTimeMillis() / 1000))
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
            val jsonText = json.encodeToString(clean)
            val text = "$MANIFEST_PREFIX\n\n$jsonText"
            if (text.length <= MANIFEST_SINGLE_MESSAGE_LIMIT) {
                saveSingleManifest(chatId, text)
            } else {
                // H9: the manifest no longer fits in one Telegram
                // message (4096 chars). Save it as numbered parts
                // instead of silently dropping every change from here on.
                saveChunkedManifest(chatId, jsonText)
            }
        }
    }

    private fun saveSingleManifest(chatId: Long, text: String) {
        val formatted = TdApi.FormattedText(text, emptyArray())
        if (manifestMessageId != 0L) {
            telegramClient.send(TdApi.EditMessageText(chatId, manifestMessageId, null, TdApi.InputMessageText(formatted, null, true))) { result ->
                if (result is TdApi.Error) {
                    // The stored id may be a stale temporary local id or
                    // the message may be gone: fall back to a fresh
                    // message next time instead of retrying a dead edit
                    // forever (H9).
                    Log.e("DriveRepo", "Manifest edit failed (${result.code} ${result.message}); will send a fresh manifest message")
                    manifestMessageId = 0L
                }
            }
        } else {
            telegramClient.send(TdApi.SendMessage(chatId, null, null, null, null, TdApi.InputMessageText(formatted, null, true))) { result ->
                if (result is TdApi.Message) {
                    manifestMessageId = result.id
                } else if (result is TdApi.Error) {
                    Log.e("DriveRepo", "Manifest send failed (${result.code} ${result.message})")
                }
            }
        }
        // A previous chunked save is now superseded: remove its parts
        // best-effort so the reader never merges stale chunks back in.
        deleteManifestPartMessages(chatId)
    }

    /**
     * H9: save an oversized manifest as numbered part messages. A fresh
     * generation is sent every time; the reader only trusts the newest
     * complete generation, so a partially-sent generation can never
     * corrupt the local state. The previous generation is deleted only
     * after the new one is fully sent.
     */
    private fun saveChunkedManifest(chatId: Long, jsonText: String) {
        val generation = maxOf(System.currentTimeMillis(), lastManifestPartGeneration + 1)
        lastManifestPartGeneration = generation
        val chunks = splitManifestJson(jsonText)
        val total = chunks.size
        val previousPartIds = manifestPartMessageIds.values.toList()
        val sentIds = ConcurrentHashMap<Int, Long>()
        val finishedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        chunks.forEachIndexed { index, chunk ->
            val header = "$MANIFEST_PART_PREFIX $generation ${index + 1}/$total\n"
            val formatted = TdApi.FormattedText(header + chunk, emptyArray())
            telegramClient.send(TdApi.SendMessage(chatId, null, null, null, null, TdApi.InputMessageText(formatted, null, true))) { result ->
                if (result is TdApi.Message) {
                    sentIds[index + 1] = result.id
                    pendingManifestPartTempIds[result.id] = generation to (index + 1)
                } else {
                    failedCount.incrementAndGet()
                    val error = (result as? TdApi.Error)?.let { " (${it.code} ${it.message})" } ?: ""
                    Log.e("DriveRepo", "Manifest part ${index + 1}/$total failed to send$error")
                }
                if (finishedCount.incrementAndGet() == total) {
                    if (failedCount.get() == 0) {
                        // New generation is complete: it replaces the old one.
                        // A part whose final id already arrived (stashed by
                        // UpdateMessageSendSucceeded) is adopted directly
                        // instead of its temporary local id.
                        manifestPartMessageIds.clear()
                        sentIds.forEach { (index, tempId) ->
                            manifestPartMessageIds[index] = manifestPartFinalIds.remove(tempId) ?: tempId
                        }
                        manifestPartGeneration = generation
                        if (manifestMessageId != 0L) {
                            val oldSingle = manifestMessageId
                            manifestMessageId = 0L
                            telegramClient.send(TdApi.DeleteMessages(chatId, longArrayOf(oldSingle), true)) {}
                        }
                        if (previousPartIds.isNotEmpty()) {
                            telegramClient.send(TdApi.DeleteMessages(chatId, previousPartIds.toLongArray(), true)) {}
                        }
                    } else {
                        // Incomplete generation: the reader ignores it, the
                        // previous generation stays live, and the next
                        // debounced save retries with a newer generation.
                        sentIds.values.forEach { manifestPartFinalIds.remove(it) }
                        Log.e("DriveRepo", "Chunked manifest generation $generation incomplete (${failedCount.get()}/$total parts failed); previous generation kept")
                    }
                }
            }
        }
    }

    /**
     * H9 backup follow-up: save an oversized manual backup as numbered
     * part messages, reusing F6's chunk format (header `<prefix>
     * <generation> <index>/<total>`) and splitManifestJson. The
     * generation is the backup timestamp, so the backup list shows one
     * entry per generation. A generation with a failed part is
     * incomplete: the list/restore ignore it, and the parts that did
     * send are deleted best-effort so no unrestorable fragment stays
     * behind.
     */
    private fun saveChunkedBackup(chatId: Long, generation: Long, jsonText: String) {
        val chunks = splitManifestJson(jsonText)
        val total = chunks.size
        val sentIds = ConcurrentHashMap<Int, Long>()
        val finishedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        chunks.forEachIndexed { index, chunk ->
            val header = "$MANIFEST_BACKUP_PART_PREFIX $generation ${index + 1}/$total\n"
            val formatted = TdApi.FormattedText(header + chunk, emptyArray())
            telegramClient.send(TdApi.SendMessage(chatId, null, null, null, null, TdApi.InputMessageText(formatted, null, true))) { result ->
                if (result is TdApi.Message) {
                    sentIds[index + 1] = result.id
                } else {
                    failedCount.incrementAndGet()
                    val error = (result as? TdApi.Error)?.let { " (${it.code} ${it.message})" } ?: ""
                    Log.e("DriveRepo", "Manual cloud backup part ${index + 1}/$total failed to send$error")
                }
                if (finishedCount.incrementAndGet() == total && failedCount.get() > 0) {
                    // Incomplete generation: delete the parts that did
                    // send instead of leaving an unrestorable fragment.
                    if (sentIds.isNotEmpty()) {
                        telegramClient.send(TdApi.DeleteMessages(chatId, sentIds.values.toLongArray(), true)) {}
                    }
                    Log.e("DriveRepo", "Manual cloud backup generation $generation incomplete (${failedCount.get()}/$total parts failed); backup not saved")
                }
            }
        }
    }

    private fun deleteManifestPartMessages(chatId: Long) {
        if (manifestPartMessageIds.isEmpty()) return
        val ids = manifestPartMessageIds.values.toList()
        manifestPartMessageIds.clear()
        manifestPartGeneration = 0L
        telegramClient.send(TdApi.DeleteMessages(chatId, ids.toLongArray(), true)) {}
    }

    /**
     * H9: split manifest JSON into chunks that fit in one Telegram
     * message each, without splitting a surrogate pair across chunks.
     */
    private fun splitManifestJson(jsonText: String): List<String> {
        if (jsonText.length <= MANIFEST_CHUNK_SIZE) return listOf(jsonText)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < jsonText.length) {
            var end = minOf(start + MANIFEST_CHUNK_SIZE, jsonText.length)
            if (end < jsonText.length && jsonText[end - 1].isHighSurrogate() && jsonText[end].isLowSurrogate()) end += 1
            chunks.add(jsonText.substring(start, end))
            start = end
        }
        return chunks
    }

    fun getAllFiles(): Flow<List<DriveItem.File>> {
        return driveDao.getAllFiles().map { entities ->
            entities.filter { it.virtualParentId != SAFE_SPACE_VIRTUAL_ID }
                .map { entity ->
                    DriveItem.File(
                        entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType,
                        entity.telegramFileId, verifyOfflinePath(entity.thumbnailPath), verifyOfflinePath(entity.localPath), entity.isStarred,
                        entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted, entity.createdAt
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
                        entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted, entity.createdAt
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
                    DriveItem.File(entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType, entity.telegramFileId, verifyOfflinePath(entity.thumbnailPath), verifyOfflinePath(entity.localPath), entity.isStarred, entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted, entity.createdAt)
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    fun getItems(chatId: Long?, virtualParentId: String = "0", searchQuery: String = ""): Flow<List<DriveItem>> {
        // L14: the caller's chatId used to be ignored (always the Saved
        // Messages chat), so previews opened from another chat looked the
        // file up in the wrong place.
        val targetChatId = if (chatId != null && chatId != 0L) chatId else savedMessagesChatId
        val flow = if (searchQuery.isNotEmpty()) driveDao.searchGlobal(escapeLike(searchQuery))
        else if (targetChatId != 0L) driveDao.getItemsFlow(targetChatId, virtualParentId)
        else kotlinx.coroutines.flow.flowOf(emptyList())

        return flow.map { entities ->
            entities.filter { (!it.isFolder || it.isVirtual) && (virtualParentId == SAFE_SPACE_VIRTUAL_ID || it.virtualParentId != SAFE_SPACE_VIRTUAL_ID) }
                .sortedByDescending { it.createdAt }
                .map { entity ->
                    if (entity.isFolder) DriveItem.Folder(entity.id, entity.parentChatId, entity.name, entity.id, entity.isStarred, entity.isVirtual, entity.virtualId, entity.virtualParentId, entity.isSecure)
                    else DriveItem.File(entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType, entity.telegramFileId, verifyOfflinePath(entity.thumbnailPath), verifyOfflinePath(entity.localPath), entity.isStarred, entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted, entity.createdAt)
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
                    else DriveItem.File(entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType, entity.telegramFileId, verifyOfflinePath(entity.thumbnailPath), verifyOfflinePath(entity.localPath), entity.isStarred, entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted, entity.createdAt)
                }
        }
    }

    fun searchGlobalPage(query: String, limit: Int, offset: Int): Flow<List<DriveItem>> {
        return driveDao.searchGlobalPage(escapeLike(query), limit, offset).map { entities ->
            entities.filter { (!it.isFolder || it.isVirtual) && it.virtualParentId != SAFE_SPACE_VIRTUAL_ID }
                .sortedByDescending { it.createdAt }
                .map { entity ->
                    if (entity.isFolder) DriveItem.Folder(entity.id, entity.parentChatId, entity.name, entity.id, entity.isStarred, entity.isVirtual, entity.virtualId, entity.virtualParentId, entity.isSecure)
                    else DriveItem.File(entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType, entity.telegramFileId, verifyOfflinePath(entity.thumbnailPath), verifyOfflinePath(entity.localPath), entity.isStarred, entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted, entity.createdAt)
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
        // M9: content search used to index only previewed files. Kick off a
        // one-time backfill of the rest the first time anyone searches.
        backfillContentIndex()
        return driveDao.smartSearchRaw(buildSmartSearchQuery(query, filters)).map { rows ->
            rows.filter { (!it.item.isFolder || it.item.isVirtual) && it.item.virtualParentId != SAFE_SPACE_VIRTUAL_ID }
                .map { row ->
                    val entity = row.item
                    val item = if (entity.isFolder) DriveItem.Folder(entity.id, entity.parentChatId, entity.name, entity.id, entity.isStarred, entity.isVirtual, entity.virtualId, entity.virtualParentId, entity.isSecure)
                    else DriveItem.File(entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType, entity.telegramFileId, verifyOfflinePath(entity.thumbnailPath), verifyOfflinePath(entity.localPath), entity.isStarred, entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted, entity.createdAt)
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

    // M9: backfill runs at most once per process, in the background.
    private val contentBackfillStarted = AtomicBoolean(false)

    /**
     * M9: index the text of files that were never previewed (content
     * indexing used to run only from PreviewViewModel). Capped so one
     * process never does unbounded work; the indexer filters by
     * extension/mime, skips encrypted files and never throws.
     */
    fun backfillContentIndex() {
        if (!contentBackfillStarted.compareAndSet(false, true)) return
        scope.launch {
            try {
                var indexed = 0
                repeat(10) {
                    if (indexed >= 500) return@repeat
                    val batch = driveDao.getUnindexedTextFiles(50)
                    if (batch.isEmpty()) return@repeat
                    contentSearchIndexer.backfill(batch.map { entityToFile(it) })
                    indexed += batch.size
                }
            } catch (_: Exception) {
                // Best-effort only: a failed backfill must never break search.
            }
        }
    }

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
                        scope.launch {
                            driveDao.deleteFilesByChatNotInList(chatId, allFetchedIds)
                            // M9: prune content-index rows whose file is
                            // gone, so deleted files stop matching search.
                            driveDao.deleteOrphanFileContent()
                        }
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
                } catch (e: Exception) { Log.w("DriveRepo", "processManifestMessages: manifest parse failed", e) }
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
                } catch (e: Exception) { Log.w("DriveRepo", "processManifestMessages: manifest parse failed", e) }
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
        // A caption literally saying "[VF:null]" (written by the old
        // rename bug, H10) must not conjure a "Folder_null" ghost folder.
        val tagId = parseVirtualFolderTag(caption)?.takeIf { it != "null" && it.isNotBlank() }
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
                // M25: like the missing-file branch above, a failed space
                // check must release the backup key and record the
                // failure — otherwise the file can never be retried until
                // the app restarts, and the user sees nothing.
                if (!FileUtils.hasEnoughSpace(context, sourceFile.length())) {
                    if (backupTrackingKey != null) {
                        inFlightBackupKeys.remove(backupTrackingKey)
                        recordBackupFailed(backupTrackingKey)
                    }
                    Log.w("DriveRepo", "Not enough space to split-upload $cleanName (${sourceFile.length()} bytes)")
                    return@ensureSavedMessagesChat
                }
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
                        // Parts upload concurrently (upload semaphore: 3),
                        // so the last-index part can finish first and the
                        // master must not depend on completion order. A
                        // SplitUploadState tracks per-part success and the
                        // final server-assigned message ids; the master is
                        // sent by trySendSplitMaster once every part has
                        // been attempted, all succeeded, and every final
                        // id is known.
                        val splitState = SplitUploadState(
                            groupId = groupId,
                            cleanName = cleanName,
                            totalSize = sourceFile.length(),
                            mimeType = FileUtils.getMimeType(cleanName),
                            totalParts = parts.size,
                            virtualFolderId = virtualFolderId,
                            isEncrypted = isChatSecure(targetChatId) || (virtualFolderId != null && isVirtualFolderSecure(virtualFolderId)),
                            backupTrackingKey = backupTrackingKey,
                            autoDeleteEnabled = autoDeleteEnabled,
                            sourceFile = sourceFile
                        )
                        splitUploads[groupId] = splitState
                        parts.forEachIndexed { index, part ->
                            val caption = "[TD_SPLIT|ID:$groupId|PART:$index/${parts.size}|NAME:$cleanName]$vfTag"
                            uploadSinglePart(
                                part.absolutePath, cleanName, targetChatId, caption, virtualFolderId,
                                deleteSourceAfter = true,
                                computeContentHash = false,
                                onMessageSent = { tempMessageId ->
                                    // The id in the send callback is a
                                    // temporary local id; the final id
                                    // arrives via UpdateMessageSendSucceeded.
                                    if (tempMessageId != 0L) pendingSplitPartTempIds[tempMessageId] = groupId to index
                                },
                                onUploadCompleted = { success ->
                                    synchronized(splitState) {
                                        splitState.partOk[index] = success
                                        splitState.completedCount++
                                    }
                                    trySendSplitMaster(splitState)
                                }
                            )
                        }
                    } catch (e: Exception) {
                        if (backupTrackingKey != null) {
                            inFlightBackupKeys.remove(backupTrackingKey)
                            recordBackupFailed(backupTrackingKey)
                        }
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
        computeContentHash: Boolean = true,
        onMessageSent: (Long) -> Unit = {},
        onUploadCompleted: (Boolean) -> Unit = {}
    ) {
        startTransferService()
        val isSecure = isChatSecure(targetChatId) || (virtualFolderId != null && isVirtualFolderSecure(virtualFolderId))
        val password = secureSessionManager.decryptedPassword.value
        if (isSecure && password == null) {
            if (backupTrackingKey != null) inFlightBackupKeys.remove(backupTrackingKey)
            scope.launch(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.unlock_to_upload), Toast.LENGTH_LONG).show() }
            // C11: a split part that never starts must still report its
            // completion, or the split-upload accounting waits forever.
            try { onUploadCompleted(false) } catch (e: Exception) { Log.w("DriveRepo", "onUploadCompleted callback failed", e) }
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
                } catch (e: Exception) { Log.w("DriveRepo", "uploadSinglePart encryption failed", e) }
            }

            // Duplicate finder: hash the file's content while it uploads, so
            // sending is never delayed by hashing. Secure files are skipped:
            // their ciphertext differs on every upload, so its hash could
            // never match anything. Split parts are skipped too (their
            // content is only a slice of the original file).
            val contentHashDeferred = if (computeContentHash && !isSecure) {
                scope.async { computeFileSha256(uploadReadyFile) }
            } else null

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
                    if (backupTrackingKey != null) {
                        inFlightBackupKeys.remove(backupTrackingKey)
                        recordBackupFailed(backupTrackingKey)
                    }
                    transferRepository.updateTransferManual(tempId, 0f, TransferRepository.Status.FAILED)
                    try { onUploadCompleted(false) } catch (e: Exception) { Log.w("DriveRepo", "onUploadCompleted callback failed", e) }
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
                                    fileMappings = currentManifest.fileMappings + (result.id.toString() to virtualFolderId),
                                    updatedAt = System.currentTimeMillis() / 1000
                                )
                            )
                        }
                        saveCloudManifestDebounced()
                    }

                    val file = extractTdFileFromMessage(result)
                    if (file != null) {
                        transferRepository.updateRemoteUniqueId(tempId, file.remote.uniqueId, file.id, finalPath)
                        // The content hash (computed concurrently) is stored
                        // under the file's Telegram unique id, so the
                        // duplicate finder can group by it after the next sync.
                        contentHashDeferred?.let { deferred ->
                            scope.launch {
                                try {
                                    val hash = deferred.await()
                                    if (hash != null) {
                                        driveDao.saveFileHash(FileHashEntity(file.remote.uniqueId, hash, originalSourceFile.length()))
                                        driveDao.updateContentHash(file.remote.uniqueId, hash)
                                    }
                                } catch (e: Exception) {}
                            }
                        }
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
                        if (backupTrackingKey != null) {
                            inFlightBackupKeys.remove(backupTrackingKey)
                            recordBackupFailed(backupTrackingKey)
                        }
                        transferRepository.completeAndCleanupTransfer(tempId, success = false)
                    }
                } else {
                    if (backupTrackingKey != null) {
                        inFlightBackupKeys.remove(backupTrackingKey)
                        recordBackupFailed(backupTrackingKey)
                    }
                    transferRepository.completeAndCleanupTransfer(tempId, success = false)
                }
            }
            
            // waitForCompletion can throw on cancellation; treat that as
            // a failed upload so the split-upload accounting (and the
            // caller's onUploadCompleted) still runs exactly once.
            val completedSuccess = try {
                transferRepository.waitForCompletion(tempId)
            } catch (e: Exception) {
                false
            }
            
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
                    recordBackupFailed(backupTrackingKey)
                }
            } catch (e: Exception) { Log.w("DriveRepo", "upload completion cleanup failed", e) }
            try { onUploadCompleted(completedSuccess) } catch (e: Exception) { Log.w("DriveRepo", "onUploadCompleted callback failed", e) }
        }
    }

    private fun deletePhoneFileCompletely(file: File) {
        val path = file.absolutePath
        try { if (file.exists()) file.delete() } catch (e: Exception) { Log.w("DriveRepo", "deletePhoneFileCompletely: file delete failed", e) }
        try {
            // The deprecated DATA column is gone: resolve the row
            // by DISPLAY_NAME, match it by reconstructing the path from
            // RELATIVE_PATH (null/empty = storage root), then delete by _ID.
            val externalUri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.RELATIVE_PATH
            )
            context.contentResolver.query(
                externalUri,
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(file.name),
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                if (idIdx != -1) {
                    while (cursor.moveToNext()) {
                        val relativePath = if (relIdx != -1) cursor.getString(relIdx).orEmpty() else ""
                        val relDir = if (relativePath.isEmpty() || relativePath.endsWith("/")) relativePath else "$relativePath/"
                        val storedPath = File(Environment.getExternalStorageDirectory(), relDir + file.name).absolutePath
                        if (storedPath == path) {
                            val itemUri = ContentUris.withAppendedId(externalUri, cursor.getLong(idIdx))
                            context.contentResolver.delete(itemUri, null, null)
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.w("DriveRepo", "deletePhoneFileCompletely: MediaStore delete failed", e) }
        try { MediaScannerConnection.scanFile(context, arrayOf(path), null, null) } catch (e: Exception) { Log.w("DriveRepo", "deletePhoneFileCompletely: media scan failed", e) }
    }

    /**
     * C11: send the split master record once — and only once — every
     * part has uploaded successfully and its final Telegram message id
     * is known. Called both from the last part's upload completion and
     * from UpdateMessageSendSucceeded (a part's final id can arrive
     * after its upload already reported success).
     */
    private fun trySendSplitMaster(state: SplitUploadState) {
        synchronized(state) {
            if (state.masterSent) return
            if (state.completedCount < state.totalParts) return
            if (!state.partOk.all { it }) {
                // At least one part failed: no master, the file stays
                // un-backed-up and the phone original is kept.
                state.masterSent = true
                splitUploads.remove(state.groupId)
                state.backupTrackingKey?.let { key ->
                    inFlightBackupKeys.remove(key)
                    recordBackupFailed(key)
                }
                Log.w("DriveRepo", "Split upload incomplete for ${state.cleanName}; phone original kept")
                return
            }
            val partIds = state.finalPartIds.map { it ?: return }
            state.masterSent = true
            splitUploads.remove(state.groupId)
            sendSplitMasterMetadata(
                state.groupId, state.cleanName, state.totalSize, state.mimeType,
                state.totalParts, state.virtualFolderId, state.isEncrypted, partIds
            )
            state.backupTrackingKey?.let { key ->
                inFlightBackupKeys.remove(key)
                markFileBackedUp(key)
            }
            if (state.autoDeleteEnabled) deletePhoneFileCompletely(state.sourceFile)
        }
    }

    /**
     * H9/C11: a split-master metadata message id recorded from a send
     * callback is a temporary local id. Replace it with the final server
     * id once UpdateMessageSendSucceeded arrives, so deleting a split
     * file can actually remove its master message.
     */
    private fun fixSplitMasterMetadataId(tempId: Long, finalId: Long) {
        if (tempId == 0L || tempId == finalId) return
        synchronized(manifestLock) {
            val entry = currentManifest.splitFileMasters.entries.firstOrNull { it.value.metadataMessageId == tempId } ?: return
            val updated = currentManifest.splitFileMasters.toMutableMap()
            updated[entry.key] = entry.value.copy(metadataMessageId = finalId)
            currentManifest = compactManifest(currentManifest.copy(splitFileMasters = updated))
        }
        saveCloudManifestDebounced()
    }

    private fun sendSplitMasterMetadata(groupId: String, name: String, size: Long, mime: String, parts: Int, vfId: String?, enc: Boolean, ids: List<Long>) {
        ensureSavedMessagesChat { chatId ->
            val master = SplitFileMaster(groupId, name, size, mime, parts, vfId, enc, ids)
            val text = "$FILE_META_PREFIX\n\n${json.encodeToString(master)}"
            telegramClient.send(TdApi.SendMessage(chatId, null, null, null, null, TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true))) { result ->
                val finalMaster = if (result is TdApi.Message) master.copy(metadataMessageId = result.id) else master
                synchronized(manifestLock) {
                    currentManifest = compactManifest(currentManifest.copy(splitFileMasters = currentManifest.splitFileMasters + (groupId to finalMaster), updatedAt = System.currentTimeMillis() / 1000))
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

            // C11: a split file is several part messages plus a master
            // record. Downloading just one part yields a useless slice,
            // so a split file downloads every part and reassembles the
            // original with mergeFiles.
            if (cachedEntity != null && cachedEntity.splitGroupId != null && cachedEntity.totalParts > 1) {
                downloadSplitFile(cachedEntity)
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

    /**
     * C11: download a whole split file. Every part is fetched (reusing
     * parts that are already on disk), encrypted parts are decrypted one
     * by one with the vault session password, and the parts are merged
     * into the original file. The merged file is recorded as the offline
     * copy of every part, so tapping any part afterwards opens it.
     */
    private suspend fun downloadSplitFile(entity: DriveItemEntity) {
        val groupId = entity.splitGroupId ?: return
        val master = synchronized(manifestLock) { currentManifest.splitFileMasters[groupId] }
        val totalParts = master?.totalParts ?: entity.totalParts
        if (totalParts <= 1) return
        val originalName = master?.originalName ?: entity.name
        val expectedSize = master?.totalSize ?: entity.size * totalParts.toLong()
        val isEncrypted = master?.isEncrypted ?: entity.isEncrypted

        val parts = try {
            driveDao.getSplitFileParts(groupId)
        } catch (e: Exception) {
            Log.w("DriveRepo", "Split download: could not list parts for $groupId", e)
            emptyList()
        }
        if (parts.size < totalParts) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "ফাইলের সব অংশ পাওয়া যায়নি (${parts.size}/$totalParts) — আবার sync করে দেখুন", Toast.LENGTH_LONG).show()
            }
            return
        }
        val orderedParts = parts.sortedBy { it.partIndex }.take(totalParts)

        val password = if (isEncrypted) secureSessionManager.decryptedPassword.value else null
        if (isEncrypted && password == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.unlock_to_upload), Toast.LENGTH_LONG).show()
            }
            return
        }
        if (password != null) secureSessionManager.touchSession()

        val targetDir = File(context.cacheDir, "split_downloads").apply { if (!exists()) mkdirs() }
        val targetFile = File(targetDir, sanitizeFileName(originalName))
        if (targetFile.exists() && expectedSize > 0 && targetFile.length() == expectedSize) {
            // Already reassembled earlier: just (re)record it as the
            // offline copy of every part.
            orderedParts.forEach { part ->
                driveDao.updateFileLocalPaths(part.telegramFileId, part.remoteUniqueId ?: "", targetFile.absolutePath)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "ফাইলটি ইতিমধ্যে অফলাইনে সংরক্ষিত আছে: $originalName", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (expectedSize > 0 && !FileUtils.hasEnoughSpace(context, expectedSize)) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "ফোনে পর্যাপ্ত জায়গা নেই", Toast.LENGTH_LONG).show()
            }
            return
        }

        startTransferService()
        val downloaded = mutableListOf<File>()
        val tempDecrypted = mutableListOf<File>()
        try {
            for (part in orderedParts) {
                val partFile = downloadSplitPart(part) ?: run {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "অংশ ${part.partIndex + 1}/$totalParts ডাউনলোড ব্যর্থ হয়েছে", Toast.LENGTH_LONG).show()
                    }
                    return
                }
                if (isEncrypted && password != null) {
                    // Each part was encrypted on its own (own salt/IV),
                    // so decrypt part by part before merging.
                    val plainPart = File(targetDir, "dec_${System.nanoTime()}_${part.partIndex}.tmp")
                    try {
                        encryptionManager.decryptFile(partFile, plainPart, password) {}
                    } catch (e: Exception) {
                        Log.w("DriveRepo", "Split download: decrypt failed for part ${part.partIndex}", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "ফাইলটি খোলা যায়নি — পাসওয়ার্ড যাচাই করুন", Toast.LENGTH_LONG).show()
                        }
                        return
                    }
                    tempDecrypted.add(plainPart)
                    downloaded.add(plainPart)
                } else {
                    downloaded.add(partFile)
                }
            }
            // mergeFiles truncates the target first (M35), so a stale
            // larger file from an earlier attempt cannot corrupt this one.
            FileUtils.mergeFiles(downloaded, targetFile)
            if (expectedSize > 0 && targetFile.length() != expectedSize) {
                Log.w("DriveRepo", "Split download: merged size ${targetFile.length()} != expected $expectedSize for $originalName")
                targetFile.delete()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "ফাইলটি জোড়া লাগাতে ব্যর্থ হয়েছে — আবার চেষ্টা করুন", Toast.LENGTH_LONG).show()
                }
                return
            }
            orderedParts.forEach { part ->
                driveDao.updateFileLocalPaths(part.telegramFileId, part.remoteUniqueId ?: "", targetFile.absolutePath)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "ডাউনলোড সম্পন্ন: $originalName", Toast.LENGTH_SHORT).show()
            }
        } finally {
            tempDecrypted.forEach { try { it.delete() } catch (_: Exception) {} }
        }
    }

    /**
     * C11: fetch one split part (reusing it if it is already on disk)
     * and return its local file, or null on failure.
     */
    private suspend fun downloadSplitPart(part: DriveItemEntity): File? {
        if (part.telegramFileId == 0) return null
        verifyOfflinePath(part.localPath)?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }?.let { return it }
        val key = part.remoteUniqueId?.takeIf { it.isNotEmpty() } ?: "dl_${part.telegramFileId}"
        transferRepository.enqueueTransfer(
            fileId = part.telegramFileId,
            remoteUniqueId = key,
            fileName = part.name,
            isDownload = true,
            totalSize = part.size,
            localPath = null
        ) {
            telegramClient.send(TdApi.DownloadFile(part.telegramFileId, 32, 0, 0, false))
        }
        // waitForCompletion caps at 30 minutes per call; a big part on a
        // slow connection can outlast that, so keep waiting while the
        // transfer is still alive.
        while (true) {
            val ok = transferRepository.waitForCompletion(key)
            val local = driveDao.getItemById(part.id, part.parentChatId)
                ?.localPath?.let { verifyOfflinePath(it) }?.let { File(it) }
                ?.takeIf { it.exists() && it.length() > 0 }
            if (local != null) return local
            if (!ok) {
                if (!transferRepository.transfers.value.containsKey(key)) return null
                // Still running past the 30-minute wait window: wait again.
            } else {
                // The transfer reported success but left no usable file.
                return null
            }
        }
    }

    private fun sanitizeFileName(name: String): String {
        val clean = name.replace('/', '_').replace('\\', '_').trim()
        return clean.ifEmpty { "download_${System.currentTimeMillis()}" }
    }

    fun downloadForPreview(messageId: Long, chatId: Long, fileName: String) {
        scope.launch {
            val cachedEntity = driveDao.getItemById(messageId, chatId)
            if (verifyOfflinePath(cachedEntity?.localPath) != null) return@launch

            // C11: a split file must be reassembled before it can be previewed.
            if (cachedEntity != null && cachedEntity.splitGroupId != null && cachedEntity.totalParts > 1) {
                downloadSplitFile(cachedEntity)
                return@launch
            }

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
                // M1: the old code wrote straight into the public Downloads
                // dir (deprecated API, EACCES on Android 10+), ignored the
                // user's chosen download folder, and toasted success even
                // when the vault session had expired (password null meant
                // the decrypt was skipped, but "Saved successfully" still
                // showed). Decrypt to a cache temp file, then route through
                // saveToPublicDownloads (MediaStore / user-folder aware,
                // honest toast), and delete the temp afterwards.
                val password = secureSessionManager.decryptedPassword.value
                if (password == null) {
                    scope.launch(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.save_vault_locked), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val tempFile = File(context.cacheDir, "decrypt_${System.currentTimeMillis()}_${file.name}")
                try {
                    secureSessionManager.touchSession()
                    encryptionManager.decryptFile(source, tempFile, password) { }
                    transferRepository.saveToPublicDownloads(tempFile.absolutePath, file.name) { tempFile.delete() }
                } catch (e: Exception) {
                    Log.w("DriveRepo", "saveToPublicStorage failed", e)
                    tempFile.delete()
                    scope.launch(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.save_failed), Toast.LENGTH_SHORT).show()
                    }
                }
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
                    currentManifest = compactManifest(currentManifest.copy(virtualFolders = currentManifest.virtualFolders + (virtualId to folder.copy(name = newName)), updatedAt = System.currentTimeMillis() / 1000))
                }
                saveCloudManifestDebounced()
                driveDao.renameItem(item.id, item.parentChatId, newName)
            } else if (item is DriveItem.File) {
                // H10: rebuild the caption so the [ENC] and
                // [TD_SPLIT|...] markers survive the rename, and never
                // write a literal "[VF:null]" (a null virtualParentId
                // used to pass the `!= "0"` check). For split files the
                // whole group is renamed: the master record and every
                // part caption carry the new name.
                if (item.splitGroupId != null && item.totalParts > 1) {
                    renameSplitFile(item, newName)
                } else {
                    val caption = buildFileCaption(newName, item.virtualParentId, item.isEncrypted, splitTag = null)
                    telegramClient.send(TdApi.EditMessageCaption(item.parentChatId, item.id, null, TdApi.FormattedText(caption, emptyArray()), false)) { editResult ->
                        if (editResult is TdApi.Message) {
                            scope.launch { driveDao.renameItem(item.id, item.parentChatId, newName) }
                        } else {
                            // Telegram rejected the rename: leave the local
                            // name unchanged so phone and Telegram stay in sync.
                            Log.w("DriveRepo", "Rename failed for message ${item.id}: $editResult")
                        }
                    }
                }
            } else {
                // Non-virtual folders have no Telegram caption to edit.
                driveDao.renameItem(item.id, item.parentChatId, newName)
            }
        }
    }

    /**
     * H10: build a file caption that preserves the markers a rename
     * used to drop. [splitTag] is the rebuilt [TD_SPLIT|...] tag for a
     * split part (null for ordinary files). A null/blank virtual-parent
     * id writes no [VF:] tag at all — never "[VF:null]".
     */
    private fun buildFileCaption(newName: String, virtualParentId: String?, isEncrypted: Boolean, splitTag: String?): String {
        val vfTag = if (!virtualParentId.isNullOrEmpty() && virtualParentId != "0") " [VF:$virtualParentId]" else ""
        val encTag = if (isEncrypted) " [ENC]" else ""
        return if (splitTag != null) "$splitTag$vfTag$encTag" else "$newName$vfTag$encTag"
    }

    /**
     * H10: rename a whole split file — the master record's original
     * name and every part caption's NAME field. Parts that fail their
     * caption edit keep their old name in Telegram and are left alone
     * locally, so the local DB never claims a rename Telegram refused.
     */
    private fun renameSplitFile(item: DriveItem.File, newName: String) {
        val groupId = item.splitGroupId ?: return
        scope.launch {
            val master = synchronized(manifestLock) { currentManifest.splitFileMasters[groupId] }
            val parts = try {
                driveDao.getSplitFileParts(groupId)
            } catch (e: Exception) {
                Log.w("DriveRepo", "Rename split file: could not list parts for $groupId", e)
                emptyList()
            }
            if (master != null) {
                synchronized(manifestLock) {
                    val updated = currentManifest.splitFileMasters.toMutableMap()
                    updated[groupId] = master.copy(originalName = newName)
                    currentManifest = compactManifest(currentManifest.copy(
                        splitFileMasters = updated,
                        updatedAt = System.currentTimeMillis() / 1000
                    ))
                }
                saveCloudManifestDebounced()
            }
            if (parts.isEmpty()) {
                // No part rows locally: fall back to renaming just this
                // message's caption.
                val caption = buildFileCaption(newName, item.virtualParentId, item.isEncrypted, splitTag = null)
                telegramClient.send(TdApi.EditMessageCaption(item.parentChatId, item.id, null, TdApi.FormattedText(caption, emptyArray()), false)) { editResult ->
                    if (editResult is TdApi.Message) {
                        scope.launch { driveDao.renameItem(item.id, item.parentChatId, newName) }
                    } else {
                        Log.w("DriveRepo", "Rename failed for message ${item.id}: $editResult")
                    }
                }
                return@launch
            }
            val total = master?.totalParts ?: item.totalParts
            parts.forEach { part ->
                val splitTag = "[TD_SPLIT|ID:$groupId|PART:${part.partIndex}/$total|NAME:$newName]"
                val caption = buildFileCaption(newName, part.virtualParentId, part.isEncrypted, splitTag)
                telegramClient.send(TdApi.EditMessageCaption(part.parentChatId, part.id, null, TdApi.FormattedText(caption, emptyArray()), false)) { editResult ->
                    if (editResult is TdApi.Message) {
                        scope.launch { driveDao.renameItem(part.id, part.parentChatId, newName) }
                    } else {
                        Log.w("DriveRepo", "Rename failed for split part ${part.id}: $editResult")
                    }
                }
            }
        }
    }

    /**
     * L26: the per-chat half of [permanentlyDeleteItems] as a suspend
     * function. It returns true only when Telegram confirmed the delete
     * (TdApi.Ok within 30 s) — the local rows, content index and manifest
     * mappings are cleaned up only on confirmation, exactly as before,
     * so a refused delete keeps the files visible and retryable.
     */
    private suspend fun deleteFilesFromChat(chatId: Long, files: List<DriveItem.File>): Boolean {
        val deleteResult = CompletableDeferred<Boolean>()
        val ids = files.map { it.id }.toLongArray()
        telegramClient.send(TdApi.DeleteMessages(chatId, ids, true)) { result ->
            if (result is TdApi.Ok) {
                scope.launch {
                    files.forEach { f ->
                        driveDao.deleteItemCompletely(f.id, chatId)
                        // M9: the content index used to keep rows
                        // for deleted files, so they stayed
                        // searchable after deletion.
                        if (f.remoteUniqueId.isNotBlank()) driveDao.deleteFileContent(f.remoteUniqueId)
                    }
                    // Only drop the manifest mappings once Telegram
                    // confirmed the delete; otherwise the files stay
                    // mapped and visible, and the delete can be retried.
                    val metadataIdsToDelete = mutableListOf<Long>()
                    val partIdsToDelete = mutableListOf<Long>()
                    synchronized(manifestLock) {
                        val updatedFileMappings = currentManifest.fileMappings.toMutableMap()
                        val updatedSplitMasters = currentManifest.splitFileMasters.toMutableMap()
                        files.forEach { file ->
                            updatedFileMappings.remove(file.id.toString())
                            file.splitGroupId?.let { gid ->
                                // C11: deleting a split file must
                                // remove every part message, not
                                // just the master record — the
                                // parts used to stay orphaned on
                                // Telegram forever.
                                updatedSplitMasters.remove(gid)?.let { master ->
                                    master.metadataMessageId.takeIf { it != 0L }?.let { metadataIdsToDelete.add(it) }
                                    partIdsToDelete.addAll(master.partMessageIds)
                                }
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
                    val alreadyDeletedIds = files.map { it.id }.toSet()
                    val remainingPartIds = partIdsToDelete.filter { it != 0L && it !in alreadyDeletedIds }
                    if (remainingPartIds.isNotEmpty()) {
                        // Parts live in the same chat as the file
                        // rows that reference their group.
                        files.filter { it.splitGroupId != null }.map { it.parentChatId }.toSet().forEach { partChatId ->
                            telegramClient.send(TdApi.DeleteMessages(partChatId, remainingPartIds.toLongArray(), true)) {}
                        }
                    }
                    saveCloudManifestDebounced()
                    deleteResult.complete(true)
                }
            } else {
                // Telegram refused the delete: keep the local rows
                // so the files stay visible and can be retried.
                Log.w("DriveRepo", "DeleteMessages failed for chat $chatId: $result")
                deleteResult.complete(false)
            }
        }
        return withTimeoutOrNull(30_000L) { deleteResult.await() } ?: false
    }

    fun permanentlyDeleteItems(items: List<DriveItem>) {
        scope.launch {
            val filesByChat = items.filterIsInstance<DriveItem.File>().groupBy { it.parentChatId }
            filesByChat.forEach { (chatId, files) -> deleteFilesFromChat(chatId, files) }

            val virtualFolders = items.filterIsInstance<DriveItem.Folder>().filter { it.isVirtual }
            if (virtualFolders.isNotEmpty()) {
                virtualFolders.forEach { folder -> folder.virtualId?.let { vid -> deleteVirtualFolderRecursive(vid) } }
                saveCloudManifestDebounced()
            }
        }
    }

    /**
     * H8: delete a virtual folder and its whole subtree. Phase 1 collects
     * every descendant file and sub-folder without deleting anything;
     * phase 2 deletes all files from Telegram; only when Telegram
     * confirms every delete are the local rows and the manifest updated
     * — the folder is removed from virtualFolders, its deletion is
     * recorded in deletedFolderIds (so a later manifest fetch cannot
     * resurrect it), and every descendant file mapping is dropped.
     */
    private suspend fun deleteVirtualFolderRecursive(virtualId: String) {
        val descendantFiles = mutableListOf<DriveItemEntity>()
        val descendantFolders = mutableListOf<String>()
        suspend fun collect(vId: String) {
            descendantFolders.add(vId)
            val children = driveDao.getItemsByVirtualParentSync(vId)
            for (child in children) {
                if (child.isFolder && child.isVirtual && child.virtualId != null) {
                    collect(child.virtualId)
                } else if (!child.isFolder) {
                    descendantFiles.add(child)
                }
            }
        }
        collect(virtualId)

        val filesByChat = descendantFiles.groupBy { it.parentChatId }
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
        // Only remove local rows and manifest entries when Telegram
        // confirmed every delete; otherwise the folder stays visible and
        // the delete can be retried.
        if (!allSucceeded) return
        descendantFiles.forEach { child ->
            driveDao.deleteItemCompletely(child.id, child.parentChatId)
            // M9: drop the content-index row too (see permanentlyDeleteItems).
            if (!child.remoteUniqueId.isNullOrBlank()) driveDao.deleteFileContent(child.remoteUniqueId)
        }
        descendantFolders.forEach { folderVirtualId ->
            driveDao.getVirtualFolderById(folderVirtualId)?.let { entity ->
                driveDao.deleteItemCompletely(entity.id, entity.parentChatId)
            }
        }
        val deletedAt = System.currentTimeMillis() / 1000
        synchronized(manifestLock) {
            val updatedFolders = currentManifest.virtualFolders.toMutableMap()
            val updatedDeletedIds = currentManifest.deletedFolderIds.toMutableMap()
            val updatedMappings = currentManifest.fileMappings.toMutableMap()
            descendantFolders.forEach { folderVirtualId ->
                updatedFolders.remove(folderVirtualId)
                updatedDeletedIds[folderVirtualId] = deletedAt
            }
            descendantFiles.forEach { child -> updatedMappings.remove(child.id.toString()) }
            currentManifest = compactManifest(currentManifest.copy(
                virtualFolders = updatedFolders,
                deletedFolderIds = updatedDeletedIds,
                fileMappings = updatedMappings,
                updatedAt = System.currentTimeMillis() / 1000
            ))
        }
        syncVirtualFoldersToDb()
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
        } catch (e: Exception) { Log.w("DriveRepo", "calculateDirectorySize failed", e) }
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

    // ---- Combo insights: storage analyzer / duplicate finder / backup health ----

    /** DriveItemEntity -> DriveItem.File, same mapping as the home/preview lists use. */
    fun entityToFile(entity: DriveItemEntity): DriveItem.File = DriveItem.File(
        entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType, entity.telegramFileId,
        verifyOfflinePath(entity.thumbnailPath), verifyOfflinePath(entity.localPath),
        entity.isStarred, entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts,
        entity.virtualParentId, entity.isEncrypted, entity.createdAt
    )

    /** SHA-256 hex of a file's content, streamed so large files never load into memory. Null on any read error. */
    private suspend fun computeFileSha256(file: File): String? = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(512 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        } catch (e: Exception) {
            null
        }
    }

    fun getLargestFiles(limit: Int = 20): Flow<List<DriveItemEntity>> = driveDao.getLargestFiles(limit)

    fun getDuplicateGroups(): Flow<List<DriveDao.DuplicateGroup>> = driveDao.getDuplicateGroups()

    suspend fun getDuplicateGroupItems(hash: String, size: Long): List<DriveItemEntity> =
        driveDao.getDuplicateGroupItems(hash, size)

    /**
     * Hashes every downloaded file that has no content hash yet, so the
     * duplicate finder can group files uploaded before hashing existed.
     * Returns how many files were hashed. Progress is reported as
     * (done, total) on each file.
     */
    suspend fun scanDownloadedFilesForHashes(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            val items = driveDao.getUnhashedDownloadedFiles()
            var hashed = 0
            items.forEachIndexed { index, entity ->
                val uniqueId = entity.remoteUniqueId
                val file = verifyOfflinePath(entity.localPath)?.let { File(it) }
                if (uniqueId != null && file != null && file.exists() && file.length() > 0L) {
                    computeFileSha256(file)?.let { hash ->
                        driveDao.saveFileHash(FileHashEntity(uniqueId, hash, entity.size))
                        driveDao.updateContentHash(uniqueId, hash)
                        hashed++
                    }
                }
                onProgress(index + 1, items.size)
            }
            hashed
        }

    /**
     * L26: deletes the given entities and returns how many Telegram
     * actually confirmed as deleted. The old version was fire-and-forget,
     * so the caller reported "Deleted N copies" before Telegram had
     * confirmed anything (or even when it refused).
     */
    suspend fun deleteDuplicateItems(entities: List<DriveItemEntity>): Int {
        if (entities.isEmpty()) return 0
        val files = entities.map { entityToFile(it) }.filterIsInstance<DriveItem.File>()
        var deleted = 0
        files.groupBy { it.parentChatId }.forEach { (chatId, chatFiles) ->
            if (deleteFilesFromChat(chatId, chatFiles)) deleted += chatFiles.size
        }
        return deleted
    }

    fun getBackupFolders(): List<String> = secureSettings.getStringList("backup_folders")

    /** Last time a file actually finished backing up (falls back to the last run's end time for older installs). */
    fun getLastSuccessfulBackupTime(): Long {
        val v = secureSettings.getLong("last_successful_backup_time", 0L)
        return if (v != 0L) v else secureSettings.getLong("last_backup_time", 0L)
    }

    /** Bytes uploaded in the current calendar month. */
    fun getMonthlyBackupBytes(): Long {
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(System.currentTimeMillis()))
        return secureSettings.getLong("backup_bytes_$month", 0L)
    }

    /** One-shot snapshot for the backup health dashboard (does a folder scan, so call it off the main thread). */
    suspend fun getBackupHealthSnapshot(): BackupHealthSnapshot = withContext(Dispatchers.IO) {
        val folders = secureSettings.getStringList("backup_folders")
        val customPrefs = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
        val backedUpSet = customPrefs.getStringSet("backed_up_paths_v3", emptySet()) ?: emptySet()
        val failedKeys = customPrefs.getStringSet("backup_failed_v1", emptySet()) ?: emptySet()

        // H14: health only counts pending files — pass
        // materializeSafCopies = false so opening Backup Health does not
        // copy GBs of SAF files into cacheDir. SAF targets have no local
        // file; their path for folder grouping is "sourceFolder/name".
        val pendingFiles = collectTargetBackupFiles(folders, materializeSafCopies = false)
        val pendingInfos = pendingFiles.map { t ->
            BackupFileInfo(t.key, t.file?.absolutePath ?: "${t.sourceFolder}/${t.name}", t.name, t.size)
        }

        val folderStatuses = (folders + "Other").distinct().map { folder ->
            val norm = if (folder == "Other") null else normalizeFolderPath(folder)
            val inFolder: (String) -> Boolean = { path ->
                if (norm == null) {
                    folders.none { f ->
                        val n = normalizeFolderPath(f)
                        path == n || path.startsWith("$n/")
                    }
                } else {
                    path == norm || path.startsWith("$norm/")
                }
            }
            val backedUp = backedUpSet.count { key -> inFolder(backupKeyPath(key)) }
            val pending = pendingInfos.count { inFolder(it.path) }
            val failed = failedKeys.count { key -> inFolder(backupKeyPath(key)) }
            BackupFolderStatus(folder, backedUp, pending, failed)
        }.filter { it.backedUpCount > 0 || it.pendingCount > 0 || it.failedCount > 0 || it.folder != "Other" }

        BackupHealthSnapshot(
            folders = folderStatuses,
            pendingFiles = pendingInfos,
            failedFiles = failedKeys.map { backupKeyToInfo(it) },
            lastSuccessfulBackupTime = getLastSuccessfulBackupTime(),
            monthlyBackupBytes = getMonthlyBackupBytes(),
            totalBackedUpCount = backedUpSet.size
        )
    }

    /** The file path part of a backup key ("path_length" or "doc_<uri>_length"). */
    private fun backupKeyPath(key: String): String {
        return if (key.startsWith("doc_")) key.removePrefix("doc_").substringBeforeLast("_") else key.substringBeforeLast("_")
    }

    private fun backupKeyToInfo(key: String): BackupFileInfo {
        val size = key.substringAfterLast("_").toLongOrNull() ?: 0L
        return if (key.startsWith("doc_")) {
            val uriPart = key.removePrefix("doc_").substringBeforeLast("_")
            val name = try {
                URLDecoder.decode(uriPart.substringAfterLast("%2F", uriPart.substringAfterLast("/")), "UTF-8")
            } catch (e: Exception) {
                uriPart.substringAfterLast("/")
            }
            BackupFileInfo(key, uriPart, name, size)
        } else {
            val path = key.substringBeforeLast("_")
            BackupFileInfo(key, path, File(path).name, size)
        }
    }

    /** Re-uploads one failed backup file (identified by its backup key). Runs in the caller's coroutine. */
    suspend fun retryFailedBackup(fileKey: String) {
        try {
            val deleteEnabled = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
                .getBoolean("delete_after_backup", false)
            if (fileKey.startsWith("doc_")) {
                val uriStr = fileKey.removePrefix("doc_").substringBeforeLast("_")
                val name = backupKeyToInfo(fileKey).name
                // H14: same unique temp name as the scan (a bare doc name
                // collided across subfolders).
                val tempFile = File(context.cacheDir, safTempName(Uri.parse(uriStr), name))
                context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output, bufferSize = 512 * 1024)
                    }
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    uploadFileAndWait(tempFile.absolutePath, name, forceDeleteSource = false, backupTrackingKey = fileKey)
                    cleanupSafTempFile(BackupTargetFile(fileKey, tempFile, name, tempFile.length(), tempFile.lastModified(), ""))
                }
            } else {
                val path = fileKey.substringBeforeLast("_")
                val f = File(path)
                if (f.exists() && f.length() > 0) {
                    uploadFileAndWait(f.absolutePath, f.name, forceDeleteSource = deleteEnabled, backupTrackingKey = fileKey)
                }
            }
        } catch (e: Exception) {
            Log.e("DriveRepo", "Retry backup failed: ${e.message}")
        }
    }

    /** Re-uploads every failed backup file, one after another, in the caller's coroutine. */
    suspend fun retryAllFailedBackups() {
        val keys = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
            .getStringSet("backup_failed_v1", emptySet())?.toList() ?: emptyList()
        keys.forEach { retryFailedBackup(it) }
    }

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
        } catch (e: Exception) { Log.w("DriveRepo", "startTransferService failed", e) }
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

/** One backup folder's health, as shown on the backup health dashboard. */
data class BackupFolderStatus(
    val folder: String,
    val backedUpCount: Int,
    val pendingCount: Int,
    val failedCount: Int
) {
    /** Short name shown in the UI ("DCIM" instead of the full path; "Other" as-is). */
    val displayName: String get() = if (folder == "Other") folder else File(folder).name.ifEmpty { folder }
}

/** A single pending/failed backup file. [key] is the "path_length" backup key used to retry it. */
data class BackupFileInfo(
    val key: String,
    val path: String,
    val name: String,
    val size: Long
)

/** One-shot snapshot for the backup health dashboard. */
data class BackupHealthSnapshot(
    val folders: List<BackupFolderStatus>,
    val pendingFiles: List<BackupFileInfo>,
    val failedFiles: List<BackupFileInfo>,
    val lastSuccessfulBackupTime: Long,
    val monthlyBackupBytes: Long,
    val totalBackedUpCount: Int
)
