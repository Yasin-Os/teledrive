package com.awd.teledrive.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.core.ConnectivityObserver
import com.awd.teledrive.data.model.TransferInfo
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.repository.ShareRepository
import com.awd.teledrive.data.repository.TransferRepository
import com.awd.teledrive.data.secure.MasterPasswordService
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.data.secure.SecureSettings
import com.awd.teledrive.domain.model.DriveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

enum class SortOrder {
    NAME, DATE, SIZE
}

enum class FilterType {
    ALL, PHOTOS, VIDEOS, AUDIO, DOCUMENTS
}

enum class CustomCategory {
    NONE, APPS, ARCHIVES, DOWNLOADS, RECENTS, ALL_STORAGE
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val driveRepository: DriveRepository,
    private val shareRepository: ShareRepository,
    private val transferRepository: TransferRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val masterPasswordService: MasterPasswordService,
    private val secureSessionManager: SecureSessionManager,
    private val secureSettings: SecureSettings
) : ViewModel() {

    companion object {
        @Volatile
        private var hasInitialSynced = false
    }

    val connectivityStatus = connectivityObserver.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectivityObserver.Status.Available)

    val transfers: StateFlow<Map<String, TransferInfo>> = transferRepository.transfers

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.DATE)
    val sortOrder = _sortOrder.asStateFlow()

    private val _filterType = MutableStateFlow(FilterType.ALL)
    val filterType = _filterType.asStateFlow()

    private val _customCategory = MutableStateFlow(CustomCategory.NONE)
    val customCategory = _customCategory.asStateFlow()

    private val _selectedCategoryFolderId = MutableStateFlow("ALL")
    val selectedCategoryFolderId = _selectedCategoryFolderId.asStateFlow()

    private val _currentFolderId = MutableStateFlow<Long?>(null)
    val currentFolderId = _currentFolderId.asStateFlow()

    private val _currentVirtualFolderId = MutableStateFlow("0")
    val currentVirtualFolderId = _currentVirtualFolderId.asStateFlow()

    private val _currentFolderName = MutableStateFlow<String?>(null)
    val currentFolderName = _currentFolderName.asStateFlow()

    private val _isGridView = MutableStateFlow(value = false)
    val isGridView = _isGridView.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _isInitialLoading = MutableStateFlow(false)
    val isInitialLoading = _isInitialLoading.asStateFlow()

    private val _isSyncingMetadata = MutableStateFlow(false)
    val isSyncingMetadata = _isSyncingMetadata.asStateFlow()

    private val _recentOpenedIds = MutableStateFlow<List<Long>>(
        secureSettings.getStringList("recent_opened_files").mapNotNull { it.toLongOrNull() }
    )

    // র‍্যামে (RAM) প্রি-লোডেড ফাস্ট ফাইল ক্যাশ
    val allCloudFiles: StateFlow<List<DriveItem.File>> = driveRepository.getAllFiles()
        .map { list -> list.filter { !it.isEncrypted } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val virtualFolders: StateFlow<List<DriveItem.Folder>> = driveRepository.getSavedMessagesChatIdFlow()
        .flatMapLatest { driveRepository.getItems(null, "0", "") }
        .map { list -> list.filterIsInstance<DriveItem.Folder>().filter { it.isVirtual && !it.isSecure } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // রিসেন্টের মধ্যে শুধুমাত্র প্লে করা, ডাউনলোড করা বা আপলোড করা ভিডিও এবং ফটো
    val recentFiles: StateFlow<List<DriveItem.File>> = combine(
        allCloudFiles,
        _recentOpenedIds
    ) { files, recentIds ->
        val mediaOnly = files.filter {
            it.mimeType.startsWith("video/") || it.mimeType.startsWith("image/")
        }
        val mediaMap = mediaOnly.associateBy { it.id }

        val playedMedia = recentIds.mapNotNull { mediaMap[it] }
        val downloadedMedia = mediaOnly.filter { !it.localPath.isNullOrEmpty() }
        val recentUploadedMedia = mediaOnly.take(15)

        (playedMedia + downloadedMedia + recentUploadedMedia).distinctBy { it.id }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalStorageUsed: StateFlow<Long> = driveRepository.getTotalStorageUsed()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    fun recordFileOpened(fileId: Long) {
        val updated = listOf(fileId) + _recentOpenedIds.value.filter { it != fileId }
        val trimmed = updated.take(30)
        _recentOpenedIds.value = trimmed
        secureSettings.saveStringList("recent_opened_files", trimmed.map { it.toString() })
    }

    fun removeFromRecents(fileId: Long) {
        val updated = _recentOpenedIds.value.filter { it != fileId }
        _recentOpenedIds.value = updated
        secureSettings.saveStringList("recent_opened_files", updated.map { it.toString() })
    }

    private fun isAppFile(file: DriveItem.File): Boolean {
        val ext = file.name.lowercase()
        return file.mimeType.contains("android.package-archive") || ext.endsWith(".apk") || ext.endsWith(".apks") || ext.endsWith(".xapk")
    }

    // ইন-মেমোরি ০ মিলিসেকেন্ড ফিল্টারিং ইঞ্জিন (ব্যাকে আসলে বা ক্যাটাগরিতে ঢুকলে কখনোই আটকে যাবে না)
    val items: StateFlow<List<DriveItem>> = combine(
        listOf(
            allCloudFiles,
            virtualFolders,
            _currentVirtualFolderId,
            _searchQuery,
            _sortOrder,
            _filterType,
            _customCategory,
            _recentOpenedIds,
            _selectedCategoryFolderId
        )
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        val allFiles = arr[0] as List<DriveItem.File>
        @Suppress("UNCHECKED_CAST")
        val allFolders = arr[1] as List<DriveItem.Folder>
        val virtualId = arr[2] as String
        val query = (arr[3] as String).trim()
        val order = arr[4] as SortOrder
        val filter = arr[5] as FilterType
        val customCat = arr[6] as CustomCategory
        @Suppress("UNCHECKED_CAST")
        val recentIds = arr[7] as List<Long>
        val selectedCatFolder = arr[8] as String

        if (query.isNotEmpty()) {
            val matchedFolders = allFolders.filter { it.name.contains(query, ignoreCase = true) }
            val matchedFiles = allFiles.filter { it.name.contains(query, ignoreCase = true) }
            return@combine matchedFolders + matchedFiles
        }

        if (customCat == CustomCategory.RECENTS) {
            val mediaOnly = allFiles.filter { it.mimeType.startsWith("video/") || it.mimeType.startsWith("image/") }
            val fileMap = mediaOnly.associateBy { it.id }
            val played = recentIds.mapNotNull { fileMap[it] }
            val downloaded = mediaOnly.filter { !it.localPath.isNullOrEmpty() }
            val combined = (played + downloaded + mediaOnly.take(15)).distinctBy { it.id }
            return@combine if (selectedCatFolder == "ALL") combined else combined.filter { (it.virtualParentId ?: "0") == selectedCatFolder }
        }

        val isFileCategoryActive = (filter != FilterType.ALL || (customCat != CustomCategory.NONE && customCat != CustomCategory.ALL_STORAGE)) && virtualId == "0"

        val baseFolders = if (isFileCategoryActive) emptyList() else allFolders.filter { (it.virtualParentId ?: "0") == virtualId }
        val baseFiles = if (isFileCategoryActive) {
            if (selectedCatFolder == "ALL") allFiles else allFiles.filter { (it.virtualParentId ?: "0") == selectedCatFolder }
        } else {
            allFiles.filter { (it.virtualParentId ?: "0") == virtualId }
        }

        val filteredFiles = when {
            customCat == CustomCategory.APPS -> baseFiles.filter { isAppFile(it) }
            customCat == CustomCategory.DOWNLOADS || customCat == CustomCategory.ARCHIVES -> baseFiles.filter { !it.localPath.isNullOrEmpty() }
            filter == FilterType.PHOTOS -> baseFiles.filter { it.mimeType.startsWith("image/") }
            filter == FilterType.VIDEOS -> baseFiles.filter { it.mimeType.startsWith("video/") }
            filter == FilterType.AUDIO -> baseFiles.filter { it.mimeType.startsWith("audio/") }
            filter == FilterType.DOCUMENTS -> baseFiles.filter {
                !it.mimeType.startsWith("image/") &&
                !it.mimeType.startsWith("video/") &&
                !it.mimeType.startsWith("audio/") &&
                !isAppFile(it)
            }
            else -> baseFiles
        }

        val sortedFolders = when (order) {
            SortOrder.NAME -> baseFolders.sortedBy { it.name.lowercase() }
            else -> baseFolders.sortedByDescending { it.id }
        }

        val sortedFiles = when (order) {
            SortOrder.NAME -> filteredFiles.sortedBy { it.name.lowercase() }
            SortOrder.DATE -> filteredFiles
            SortOrder.SIZE -> filteredFiles.sortedByDescending { it.size }
        }

        sortedFolders + sortedFiles
    }
    .distinctUntilChanged()
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        if (!hasInitialSynced) {
            hasInitialSynced = true
            fetchItems()
        }
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun setFilterType(type: FilterType) {
        _customCategory.value = CustomCategory.NONE
        _selectedCategoryFolderId.value = "ALL"
        _filterType.value = type
    }

    fun setCustomCategory(category: CustomCategory) {
        _filterType.value = FilterType.ALL
        _selectedCategoryFolderId.value = "ALL"
        _customCategory.value = category
    }

    fun setSelectedCategoryFolder(folderVirtualId: String) {
        _selectedCategoryFolderId.value = folderVirtualId
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun toggleViewMode() {
        _isGridView.value = !_isGridView.value
    }

    fun navigateToFolder(folderId: Long?, virtualId: String?, folderName: String?) {
        _currentFolderId.value = null
        _currentVirtualFolderId.value = virtualId ?: "0"
        _currentFolderName.value = folderName
    }

    fun navigateBack() {
        if (_currentVirtualFolderId.value != "0") {
            val parentId = driveRepository.getParentVirtualId(_currentVirtualFolderId.value)
            val parentName = if (parentId == "0") null else driveRepository.getVirtualFolderName(parentId)
            navigateToFolder(null, parentId, parentName)
            return
        }
        if (_customCategory.value != CustomCategory.NONE || _filterType.value != FilterType.ALL) {
            _customCategory.value = CustomCategory.NONE
            _filterType.value = FilterType.ALL
            _selectedCategoryFolderId.value = "ALL"
        }
    }

    fun createFolder(name: String, isVirtual: Boolean = true, isSecure: Boolean = false) {
        viewModelScope.launch {
            val parentId = _currentVirtualFolderId.value
            val isParentSecure = if (parentId.startsWith("vf_")) {
                driveRepository.isVirtualFolderSecure(parentId)
            } else {
                false
            }
            driveRepository.createVirtualFolder(name, parentId, isSecure = isSecure || isParentSecure)
        }
    }

    fun isPasswordSet() = masterPasswordService.isPasswordSet()

    fun fetchItems() {
        viewModelScope.launch {
            _isRefreshing.value = true
            driveRepository.fetchFiles(null)
            delay(250)
            _isSyncingMetadata.value = false
            _isInitialLoading.value = false
            _isRefreshing.value = false
        }
    }

    fun uploadFile(filePath: String, fileName: String) {
        viewModelScope.launch {
            driveRepository.uploadFile(filePath, fileName, null, _currentVirtualFolderId.value)
        }
    }

    fun downloadFile(messageId: Long, chatId: Long, fileName: String) {
        viewModelScope.launch {
            driveRepository.downloadFile(messageId, chatId, fileName)
        }
    }

    fun downloadFolderContents(folder: DriveItem.Folder) {
        viewModelScope.launch {
            driveRepository.downloadFolderContents(folder)
        }
    }

    fun deleteItems(itemsToDelete: List<DriveItem>) {
        viewModelScope.launch {
            driveRepository.permanentlyDeleteItems(itemsToDelete)
            itemsToDelete.forEach { removeFromRecents(it.id) }
        }
    }

    fun moveItems(ids: Set<Long>, targetChatId: Long) { }

    fun moveFolderContentsAndDelete(folderChatId: Long, targetChatId: Long) { }

    fun toggleStarred(item: DriveItem) {
        viewModelScope.launch {
            driveRepository.toggleStarred(item)
        }
    }

    fun renameItem(item: DriveItem, newName: String) {
        viewModelScope.launch {
            driveRepository.renameItem(item, newName)
        }
    }

    fun pauseTransfer(uniqueId: String) {
        transferRepository.pauseTransfer(uniqueId)
    }

    fun resumeTransfer(uniqueId: String) {
        transferRepository.resumeTransfer(uniqueId)
    }

    fun cancelTransfer(uniqueId: String) {
        transferRepository.cancelTransfer(uniqueId)
    }

    fun removeTransfer(uniqueId: String) {
        transferRepository.removeTransfer(uniqueId)
    }

    fun clearCompletedTransfers() {
        transferRepository.clearCompleted()
    }

    fun saveAsPhone(localPath: String, fileName: String) {
        transferRepository.saveToPublicDownloads(localPath, fileName)
    }

    fun getFolderInviteLink(chatId: Long, onResult: (String?) -> Unit) {
        shareRepository.getFolderInviteLink(chatId, onResult)
    }

    fun getFolderMembers(chatId: Long, onResult: (List<TdApi.User>) -> Unit) {
        shareRepository.getChatMembers(chatId, onResult)
    }

    fun shareFileToPhone(phoneNumber: String, messageId: Long, onResult: (Boolean, String?) -> Unit) {
        val chatId = driveRepository.getSavedMessagesChatId()
        shareRepository.shareFileToPhone(phoneNumber, messageId, chatId, onResult)
    }
}
