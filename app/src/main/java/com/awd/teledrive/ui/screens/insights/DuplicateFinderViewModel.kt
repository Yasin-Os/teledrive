package com.awd.teledrive.ui.screens.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.local.DriveDao
import com.awd.teledrive.data.local.DriveItemEntity
import com.awd.teledrive.data.repository.DriveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DuplicateFinderViewModel @Inject constructor(
    private val driveRepository: DriveRepository
) : ViewModel() {

    val groups: StateFlow<List<DriveDao.DuplicateGroup>> =
        driveRepository.getDuplicateGroups()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalWasted: StateFlow<Long> = groups
        .map { list -> list.sumOf { it.wastedBytes } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private val _expandedGroups = MutableStateFlow<Set<String>>(emptySet())
    val expandedGroups: StateFlow<Set<String>> = _expandedGroups.asStateFlow()

    private val _groupItems = MutableStateFlow<Map<String, List<DriveItemEntity>>>(emptyMap())
    val groupItems: StateFlow<Map<String, List<DriveItemEntity>>> = _groupItems.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val scanProgress: StateFlow<Pair<Int, Int>?> = _scanProgress.asStateFlow()

    private val _lastScanHashed = MutableStateFlow<Int?>(null)
    val lastScanHashed: StateFlow<Int?> = _lastScanHashed.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    /** Expands a group (loading its files on first expand) or collapses it. */
    fun toggleGroup(group: DriveDao.DuplicateGroup) {
        val key = group.contentHash
        if (_expandedGroups.value.contains(key)) {
            _expandedGroups.value = _expandedGroups.value - key
            return
        }
        _expandedGroups.value = _expandedGroups.value + key
        if (!_groupItems.value.containsKey(key)) {
            viewModelScope.launch {
                val items = driveRepository.getDuplicateGroupItems(group.contentHash, group.size)
                _groupItems.value = _groupItems.value + (key to items)
            }
        }
    }

    /** Hashes downloaded files that have no content hash yet, then the group list refreshes itself. */
    fun scan() {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            _lastScanHashed.value = null
            val hashed = driveRepository.scanDownloadedFilesForHashes { done, total ->
                _scanProgress.value = done to total
            }
            _lastScanHashed.value = hashed
            _scanProgress.value = null
            _isScanning.value = false
        }
    }

    /**
     * Deletes every copy in the group except the newest one (the group
     * items are ordered newest-first). Returns the number of deleted copies.
     */
    fun deleteGroupCopies(group: DriveDao.DuplicateGroup, onDone: (Int) -> Unit = {}) {
        if (_isDeleting.value) return
        viewModelScope.launch {
            _isDeleting.value = true
            val items = driveRepository.getDuplicateGroupItems(group.contentHash, group.size)
            val toDelete = if (items.size > 1) items.drop(1) else emptyList()
            if (toDelete.isNotEmpty()) {
                driveRepository.deleteDuplicateItems(toDelete)
            }
            _groupItems.value = _groupItems.value - group.contentHash
            _expandedGroups.value = _expandedGroups.value - group.contentHash
            _isDeleting.value = false
            onDone(toDelete.size)
        }
    }

    /** Deletes all duplicate copies across every group, keeping the newest copy of each file. */
    fun deleteAllDuplicates(onDone: (Int) -> Unit = {}) {
        if (_isDeleting.value) return
        viewModelScope.launch {
            _isDeleting.value = true
            var deleted = 0
            groups.value.forEach { group ->
                val items = driveRepository.getDuplicateGroupItems(group.contentHash, group.size)
                val toDelete = if (items.size > 1) items.drop(1) else emptyList()
                if (toDelete.isNotEmpty()) {
                    driveRepository.deleteDuplicateItems(toDelete)
                    deleted += toDelete.size
                }
            }
            _groupItems.value = emptyMap()
            _expandedGroups.value = emptySet()
            _isDeleting.value = false
            onDone(deleted)
        }
    }
}
