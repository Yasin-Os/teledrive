package com.awd.teledrive.ui.screens.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.domain.model.DriveItem
import com.awd.teledrive.ui.screens.home.FilterType
import com.awd.teledrive.ui.screens.home.SortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SecureStorageViewModel @Inject constructor(
    private val driveRepository: DriveRepository
) : ViewModel() {

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

    fun hideCloudFilesInSafeFolder(fileIds: List<Long>) {
        driveRepository.setCloudItemsSafeState(fileIds, hideInSafeFolder = true)
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
