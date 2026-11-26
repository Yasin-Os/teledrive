package com.awd.teledrive.ui.screens.preview

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.R
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.repository.TransferRepository
import com.awd.teledrive.data.secure.EncryptionManager
import com.awd.teledrive.data.secure.LegacyEncryptedFileException
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.domain.model.DriveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val driveRepository: DriveRepository,
    private val encryptionManager: EncryptionManager,
    private val secureSessionManager: SecureSessionManager,
    private val transferRepository: TransferRepository,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context
) : ViewModel() {
    companion object {
        private const val TAG = "PreviewViewModel"
        private const val PREVIEW_TEMP_DIR = "vault_preview_tmp"
    }

    private val chatId: Long = savedStateHandle.get<Long>("chatId") ?: 0L
    private val initialFileId: Long = savedStateHandle.get<Long>("fileId") ?: 0L
    private val isMediaOnly: Boolean = savedStateHandle.get<Boolean>("isMediaOnly") ?: false
    private val virtualParentId: String = savedStateHandle.get<String>("virtualParentId") ?: "0"

    val items: StateFlow<List<DriveItem.File>> = (if (isMediaOnly) {
        driveRepository.getAllFiles().map { list ->
            // L27: the tapped file must stay in the list even when it is
            // not media — otherwise getInitialIndex() fell back to 0 and
            // the preview opened the first photo/video instead.
            list.filter { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") || it.id == initialFileId }
        }
    } else {
        driveRepository.getItems(if (chatId != 0L) chatId else null, virtualParentId)
            .map { list -> list.filterIsInstance<DriveItem.File>() }
    }).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val folders: StateFlow<List<DriveItem.Folder>> = driveRepository.getItems(null)
        .map { list -> list.filterIsInstance<DriveItem.Folder>() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Tracks download progress of the files in the list
    val transfers = transferRepository.transfers

    private val _activeFileId = MutableStateFlow(initialFileId)

    private val _decryptedPaths = MutableStateFlow<Map<Long, String>>(emptyMap())
    val decryptedPaths = _decryptedPaths.asStateFlow()

    private val _isDecrypting = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val isDecrypting = _isDecrypting.asStateFlow()

    private val _isOpening = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val isOpening = _isOpening.asStateFlow()

    init {
        // H11: decrypt lazily — only the single currently viewed item is ever
        // decrypted, never every encrypted item in the folder/list. Immediately
        // purge all plaintext previews if the vault locks or times out, and
        // delete previously viewed plaintext files when moving to another page.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                items,
                _activeFileId,
                secureSessionManager.isSessionUnlocked
            ) { itemList, activeId, unlocked ->
                Triple(itemList, activeId, unlocked)
            }.collect { (itemList, activeId, unlocked) ->
                if (!unlocked) {
                    cleanupDecryptedPreviews(deleteDirectoryContents = true)
                    return@collect
                }
                val currentFile = itemList.firstOrNull { it.id == activeId } ?: itemList.firstOrNull()
                val keepId = currentFile?.id
                pruneDecryptedPreviewsExcept(keepId)
                if (currentFile != null &&
                    currentFile.isEncrypted &&
                    currentFile.localPath != null &&
                    !_decryptedPaths.value.containsKey(currentFile.id)
                ) {
                    decryptForPreview(currentFile)
                }
            }
        }
    }

    fun setActiveFile(file: DriveItem.File) {
        if (_activeFileId.value != file.id) {
            _activeFileId.value = file.id
        }
    }

    fun getInitialIndex(): Int {
        val index = items.value.indexOfFirst { it.id == initialFileId }
        return if (index >= 0) index else 0
    }

    fun downloadForPreview(file: DriveItem.File) {
        val localFile = file.localPath?.let { File(it) }
        if (file.localPath == null || localFile?.exists() != true) {
            driveRepository.downloadForPreview(file.id, file.parentChatId, file.name)
        }
    }

    fun saveToDevice(file: DriveItem.File) {
        driveRepository.saveToPublicStorage(file)
    }

    fun toggleStarred(item: DriveItem) {
        viewModelScope.launch {
            driveRepository.toggleStarred(item)
        }
    }

    fun autoDownloadForPreview(file: DriveItem.File) {
        val localFile = file.localPath?.let { File(it) }
        if (file.localPath == null || localFile?.exists() != true) {
            driveRepository.downloadForPreview(file.id, file.parentChatId, file.name)
        } else if (file.isEncrypted) {
            // H11: never pre-decrypt off-screen pages; only decrypt the active page.
            if (file.id == _activeFileId.value && !_decryptedPaths.value.containsKey(file.id)) {
                decryptForPreview(file)
            }
        } else {
            // Batch 7b content search: a local copy is now available for
            // preview — index its text (best-effort, never throws).
            // Encrypted files are skipped inside the indexer: their local
            // bytes are ciphertext, and indexing a decrypted copy would
            // put secure plaintext into the unencrypted FTS table.
            driveRepository.indexFileContentForSearch(file)
        }
    }

    private fun decryptForPreview(file: DriveItem.File) {
        val dek = secureSessionManager.getSessionKeyCopy() ?: return
        val localPath = file.localPath ?: run {
            dek.fill(0)
            return
        }
        if (_isDecrypting.value[file.id] == true) {
            dek.fill(0)
            return
        }

        viewModelScope.launch {
            _isDecrypting.value = _isDecrypting.value + (file.id to true)
            _isOpening.value = _isOpening.value + (file.id to true)
            try {
                withContext(Dispatchers.IO) {
                    val encryptedFile = File(localPath)
                    val tempDir = File(context.cacheDir, PREVIEW_TEMP_DIR).apply { if (!exists()) mkdirs() }
                    val safeName = sanitizeTempName(file.name)
                    val decryptedFile = File(tempDir, "prev_dec_${file.id}_$safeName")
                    // H11: belt-and-suspenders canonical path containment guard so a
                    // crafted file.name can never escape PREVIEW_TEMP_DIR.
                    val canonicalDir = tempDir.canonicalPath
                    val canonicalTarget = decryptedFile.canonicalPath
                    if (!canonicalTarget.startsWith(canonicalDir + File.separator)) {
                        Log.e(TAG, "Refusing to decrypt preview outside temp directory for file ${file.id}")
                        return@withContext
                    }
                    try {
                        // Ensure decryption is tracked in the Transfer menu
                        transferRepository.addTransfer(
                            fileId = file.telegramFileId,
                            remoteUniqueId = file.remoteUniqueId,
                            fileName = file.name,
                            isDownload = true,
                            totalSize = file.size,
                            status = context.getString(R.string.decrypting)
                        )

                        encryptionManager.decryptFile(encryptedFile, decryptedFile, dek) { progress ->
                            transferRepository.updateTransferManual(
                                file.remoteUniqueId,
                                progress,
                                context.getString(R.string.decrypting_perc, (progress * 100).toInt()),
                                throttled = true
                            )
                        }

                        // If the vault locked or user swiped away while decrypting,
                        // discard the plaintext immediately.
                        if (!secureSessionManager.isUnlocked() || _activeFileId.value != file.id) {
                            decryptedFile.delete()
                            return@withContext
                        }

                        // Mark as Completed when done
                        transferRepository.updateTransferManual(
                            file.remoteUniqueId,
                            1.0f,
                            TransferRepository.Status.COMPLETED
                        )

                        _decryptedPaths.value = _decryptedPaths.value + (file.id to decryptedFile.absolutePath)
                    } catch (e: LegacyEncryptedFileException) {
                        decryptedFile.delete()
                        Log.w(TAG, "Legacy encrypted file requires migration before preview: ${file.id}", e)
                        transferRepository.updateTransferManual(
                            file.remoteUniqueId,
                            0f,
                            context.getString(
                                R.string.failed_with_error,
                                "Legacy encrypted file — unlock with your Safe Folder PIN to migrate"
                            )
                        )
                    } catch (e: Exception) {
                        decryptedFile.delete()
                        Log.w(TAG, "Decryption failed for file ${file.id}", e)
                        transferRepository.updateTransferManual(
                            file.remoteUniqueId,
                            0f,
                            context.getString(R.string.failed_with_error, "Decryption failed")
                        )
                    }
                }
            } finally {
                dek.fill(0)
                _isDecrypting.value = _isDecrypting.value - file.id
                _isOpening.value = _isOpening.value - file.id
            }
        }
    }

    private fun sanitizeTempName(rawName: String): String {
        val base = rawName.substringAfterLast('/').substringAfterLast('\\')
            .replace("..", "_")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trimStart('.')
            .take(80)
        return base.ifEmpty { "file" }
    }

    private fun pruneDecryptedPreviewsExcept(keepFileId: Long?) {
        val current = _decryptedPaths.value
        val toRemove = current.filterKeys { it != keepFileId }
        if (toRemove.isEmpty()) return
        _decryptedPaths.value = current.filterKeys { it == keepFileId }
        toRemove.values.forEach { path ->
            try {
                File(path).delete()
            } catch (e: Exception) {
                Log.w(TAG, "pruneDecryptedPreviewsExcept: temp file delete failed", e)
            }
        }
    }

    private fun cleanupDecryptedPreviews(deleteDirectoryContents: Boolean = false) {
        val paths = _decryptedPaths.value.values.toList()
        _decryptedPaths.value = emptyMap()
        paths.forEach { path ->
            try {
                File(path).delete()
            } catch (e: Exception) {
                Log.w(TAG, "cleanupDecryptedPreviews: temp file delete failed", e)
            }
        }
        if (deleteDirectoryContents) {
            try {
                File(context.cacheDir, PREVIEW_TEMP_DIR).deleteRecursively()
            } catch (e: Exception) {
                Log.w(TAG, "cleanupDecryptedPreviews: temp dir cleanup failed", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleanupDecryptedPreviews(deleteDirectoryContents = true)
    }

    fun deleteItem(file: DriveItem.File) {
        viewModelScope.launch {
            driveRepository.permanentlyDeleteItems(listOf(file))
        }
    }

    // M29: the old move forwarded the file to another Telegram chat —
    // but every user folder is a virtual folder (a synthetic local id),
    // not a Telegram chat id, so the move went to the wrong place (and
    // nothing in the UI ever called it). It now moves into a virtual folder.
    fun moveItem(file: DriveItem.File, targetVirtualFolderId: String) {
        viewModelScope.launch {
            driveRepository.moveItemsToVirtualFolder(listOf(file.id), file.parentChatId, targetVirtualFolderId)
        }
    }
}
