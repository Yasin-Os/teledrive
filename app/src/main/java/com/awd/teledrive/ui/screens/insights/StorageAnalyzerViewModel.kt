package com.awd.teledrive.ui.screens.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.local.DriveDao
import com.awd.teledrive.data.local.DriveItemEntity
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.domain.model.DriveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class StorageAnalyzerViewModel @Inject constructor(
    private val driveRepository: DriveRepository
) : ViewModel() {

    val cloudStats: StateFlow<List<DriveDao.FileTypeStat>> =
        driveRepository.getCloudFileTypeStats()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val largestFiles: StateFlow<List<DriveItemEntity>> =
        driveRepository.getLargestFiles(20)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val duplicateGroups: StateFlow<List<DriveDao.DuplicateGroup>> =
        driveRepository.getDuplicateGroups()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val wastedBytes: StateFlow<Long> = duplicateGroups
        .map { groups -> groups.sumOf { it.wastedBytes } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val internalCacheSize: StateFlow<Long> = driveRepository.getInternalCacheSize()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun toFile(entity: DriveItemEntity): DriveItem.File = driveRepository.entityToFile(entity)
}
