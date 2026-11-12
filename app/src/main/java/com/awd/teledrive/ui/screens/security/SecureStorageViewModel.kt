package com.awd.teledrive.ui.screens.security

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.secure.EncryptionManager
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.data.secure.SecureSettings
import com.awd.teledrive.domain.model.DriveItem
import com.awd.teledrive.ui.screens.home.FilterType
import com.awd.teledrive.ui.screens.home.SortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SecureStorageViewModel @Inject constructor(
    private val driveRepository: DriveRepository,
    private val encryptionManager: EncryptionManager,
    private val secureSessionManager: SecureSessionManager,
    private val secureSettings: SecureSettings
) : ViewModel() {

    companion object {
        private const val TAG = "SecureStorageViewModel"

        // Vault 2.0: when ON, hiding a file also encrypts the copy saved on
        // this device (AES-GCM, safe-folder PIN). Default OFF: hiding only
        // removes the file from the main list. The copy stored in Telegram
        // is NEVER encrypted by this — see the note on the vault screen.
        private const val KEY_ENCRYPT_ON_HIDE = "safe_folder_encrypt_on_hide"
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.DATE)
    val sortOrder = _sortOrder.asStateFlow()

    private val _filterType = MutableStateFlow(FilterType.ALL)
    val filterType = _filterType.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _isInitialLoading = MutableStateFlow(false)
    val isInitialLoading = _isInitialLoading.asStateFlow()

    // Vault 2.0: encrypt the local copy when hiding. Default OFF.
    private val _isEncryptOnHideEnabled = MutableStateFlow(
        secureSettings.getBoolean(KEY_ENCRYPT_ON_HIDE, false)
    )
    val isEncryptOnHideEnabled = _isEncryptOnHideEnabled.asStateFlow()

    fun setEncryptOnHideEnabled(enabled: Boolean) {
        secureSettings.saveBoolean(KEY_ENCRYPT_ON_HIDE, enabled)
        _isEncryptOnHideEnabled.value = enabled
    }

    // ক্লাউডের যে ফাইলগুলো এখনো সাধারণ ক্লাউডে আছে (Safe Folder-এ হাইড করার জন্য পিকারে দেখাবে)
    val availableCloudFiles: StateFlow<List<DriveItem.File>> = driveRepository.getAllFiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Safe Folder-এ হাইড করা ক্লাউড ফাইলসমূহ
    val secureItems: StateFlow<List<DriveItem.File>> = combine(
        driveRepository.getSafeFolderFilesFlow(),
        _searchQuery,
        _sortOrder,
        _filterType
    ) { files, query, order, filter ->
        val searched = if (query.isBlank()) files else files.filter { it.name.contains(query, ignoreCase = true) }

        val filtered = when (filter) {
            FilterType.ALL -> searched
            FilterType.PHOTOS -> searched.filter { it.mimeType.startsWith("image/") }
            FilterType.VIDEOS -> searched.filter { it.mimeType.startsWith("video/") }
            FilterType.AUDIO -> searched.filter { it.mimeType.startsWith("audio/") }
            FilterType.DOCUMENTS -> searched.filter {
                !it.mimeType.startsWith("image/") &&
                !it.mimeType.startsWith("video/") &&
                !it.mimeType.startsWith("audio/")
            }
        }

        when (order) {
            SortOrder.NAME -> filtered.sortedBy { it.name }
            SortOrder.DATE -> filtered
            SortOrder.SIZE -> filtered.sortedByDescending { it.size }
        }
    }.distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun setFilterType(type: FilterType) {
        _filterType.value = type
    }

    fun fetchItems() {
        viewModelScope.launch {
            _isRefreshing.value = true
            driveRepository.fetchFiles(null)
            delay(500)
            _isRefreshing.value = false
        }
    }

    fun hideCloudFilesInSafeFolder(files: List<DriveItem.File>) {
        viewModelScope.launch {
            if (_isEncryptOnHideEnabled.value) {
                encryptLocalCopies(files)
            }
            driveRepository.setCloudItemsSafeState(files.map { it.id }, hideInSafeFolder = true)
        }
    }

    /**
     * Vault 2.0, encrypt-on-hide: for each file that has a local copy on
     * this device, encrypt that copy with the safe-folder PIN (the session
     * password — the same credential PreviewViewModel decrypts with on open)
     * into a ".enc" file next to the original. The plaintext local copy is
     * deleted only once the new path is recorded in the database, so a
     * failure can never silently lose the file. Files with no local copy
     * are hidden as before; the copy stored in Telegram is untouched.
     * Unhide is unaffected: it only restores the main-list visibility, and
     * an encrypted local copy still opens through the existing decrypt
     * path (PreviewViewModel.decryptForPreview).
     */
    private suspend fun encryptLocalCopies(files: List<DriveItem.File>) {
        val password = secureSessionManager.decryptedPassword.value ?: run {
            Log.w(TAG, "Encrypt-on-hide skipped: no vault session (safe PIN) available")
            return
        }
        withContext(Dispatchers.IO) {
            for (file in files) {
                if (file.isEncrypted) continue
                val localPath = file.localPath ?: continue
                val localFile = File(localPath)
                if (!localFile.exists() || !localFile.isFile) continue
                val encFile = File(localFile.parentFile, localFile.name + ".enc")
                try {
                    if (encFile.exists()) encFile.delete()
                    encryptionManager.encryptFile(localFile, encFile, password)
                    if (persistEncryptedLocalCopy(file, encFile.absolutePath)) {
                        localFile.delete()
                    } else {
                        // The new path could not be recorded yet: roll back
                        // so the plaintext local copy is never silently lost.
                        encFile.delete()
                        Log.w(TAG, "Encrypt-on-hide rolled back for ${file.name}: encrypted path not recorded")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Encrypt-on-hide failed for ${file.name}", e)
                    if (encFile.exists()) encFile.delete()
                }
            }
        }
    }

    /**
     * Records an encrypted local copy in the database (localPath -> the
     * ".enc" path, isEncrypted = true) and returns true on success.
     *
     * TODO(Vault 2.0): no public DriveRepository API can do this yet — the
     * DAO has updateLocalPath (by telegramFileId) but no isEncrypted update
     * and the repository exposes neither. Needs:
     *   fun markLocalCopyEncrypted(itemId: Long, chatId: Long, encryptedPath: String)
     * (suspend, updates localPath + isEncrypted = 1 for that row). Until it
     * lands this returns false and encryptLocalCopies() rolls back, so the
     * toggle is inert and hiding behaves exactly as before.
     */
    private fun persistEncryptedLocalCopy(file: DriveItem.File, encryptedPath: String): Boolean {
        Log.w(TAG, "persistEncryptedLocalCopy not wired yet for ${file.name}: needs DriveRepository.markLocalCopyEncrypted")
        return false
    }

    fun unhideFilesToCloud(fileIds: List<Long>) {
        driveRepository.setCloudItemsSafeState(fileIds, hideInSafeFolder = false)
    }

    fun deleteItems(items: List<DriveItem>) {
        viewModelScope.launch {
            driveRepository.permanentlyDeleteItems(items)
        }
    }

    fun renameItem(item: DriveItem, newName: String) {
        viewModelScope.launch {
            driveRepository.renameItem(item, newName)
        }
    }

    fun downloadFile(messageId: Long, chatId: Long, fileName: String) {
        viewModelScope.launch {
            driveRepository.downloadFile(messageId, chatId, fileName)
        }
    }
}
