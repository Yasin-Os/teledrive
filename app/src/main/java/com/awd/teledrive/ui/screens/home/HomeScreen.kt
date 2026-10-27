package com.awd.teledrive.ui.screens.home

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import com.awd.teledrive.R
import com.awd.teledrive.core.ConnectivityObserver
import com.awd.teledrive.core.FileSharingHelper
import com.awd.teledrive.data.model.TransferInfo
import com.awd.teledrive.data.repository.TransferRepository
import com.awd.teledrive.domain.model.DriveItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

private fun isMediaStreamOrImage(file: DriveItem.File): Boolean {
    return file.mimeType.startsWith("video/") ||
           file.mimeType.startsWith("audio/") ||
           file.mimeType.startsWith("image/")
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onNavigateToTransfers: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStarred: () -> Unit = {},
    onNavigateToSafeFolder: () -> Unit = {},
    onNavigateToCloudAnalysis: () -> Unit = {},
    onNavigateToPreview: (DriveItem.File) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsState()
    val allCloudFiles by viewModel.allCloudFiles.collectAsState()
    val virtualFolders by viewModel.virtualFolders.collectAsState()
    val recentFiles by viewModel.recentFiles.collectAsState()
    val transfers by viewModel.transfers.collectAsState()
    val totalStorageUsed by viewModel.totalStorageUsed.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val filterType by viewModel.filterType.collectAsState()
    val customCategory by viewModel.customCategory.collectAsState()
    val selectedCategoryFolderId by viewModel.selectedCategoryFolderId.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isInitialLoading by viewModel.isInitialLoading.collectAsState()
    val currentFolderId by viewModel.currentFolderId.collectAsState()
    val currentFolderName by viewModel.currentFolderName.collectAsState()
    val currentVirtualFolderId by viewModel.currentVirtualFolderId.collectAsState()
    val isGridView by viewModel.isGridView.collectAsState()
    val connectivityStatus by viewModel.connectivityStatus.collectAsState()

    HomeContent(
        items = items,
        allCloudFiles = allCloudFiles,
        virtualFolders = virtualFolders,
        recentFiles = recentFiles,
        transfers = transfers,
        totalStorageUsed = totalStorageUsed,
        searchQuery = searchQuery,
        sortOrder = sortOrder,
        filterType = filterType,
        customCategory = customCategory,
        selectedCategoryFolderId = selectedCategoryFolderId,
        isRefreshing = isRefreshing,
        isInitialLoading = isInitialLoading,
        currentFolderId = currentFolderId,
        currentVirtualFolderId = currentVirtualFolderId,
        currentFolderName = currentFolderName,
        isGridView = isGridView,
        connectivityStatus = connectivityStatus,
        onNavigateToStarred = onNavigateToStarred,
        onNavigateToSafeFolder = onNavigateToSafeFolder,
        onNavigateToCloudAnalysis = onNavigateToCloudAnalysis,
        onNavigateToPreview = { file ->
            viewModel.recordFileOpened(file.id)
            onNavigateToPreview(file)
        },
        onRemoveFromRecents = viewModel::removeFromRecents,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onToggleViewMode = viewModel::toggleViewMode,
        onSetSortOrder = viewModel::setSortOrder,
        onSetFilterType = viewModel::setFilterType,
        onSetCustomCategory = viewModel::setCustomCategory,
        onSetSelectedCategoryFolder = viewModel::setSelectedCategoryFolder,
        onCreateFolder = viewModel::createFolder,
        onUploadFile = viewModel::uploadFile,
        onDeleteItems = viewModel::deleteItems,
        onDownloadFile = viewModel::downloadFile,
        onDownloadFolderContents = viewModel::downloadFolderContents,
        onToggleStarred = viewModel::toggleStarred,
        onNavigateToFolder = { chatId, vId, name -> viewModel.navigateToFolder(chatId, vId, name) },
        onNavigateBack = viewModel::navigateBack,
        onRefresh = viewModel::fetchItems,
        onRenameItem = viewModel::renameItem,
        onPauseTransfer = viewModel::pauseTransfer,
        onResumeTransfer = viewModel::resumeTransfer,
        onCancelTransfer = viewModel::cancelTransfer,
        onRemoveTransfer = viewModel::removeTransfer,
        onClearCompletedTransfers = viewModel::clearCompletedTransfers,
        onSaveAsPhone = viewModel::saveAsPhone,
        onGetInviteLink = viewModel::getFolderInviteLink,
        onGetMembers = viewModel::getFolderMembers,
        onShareFile = viewModel::shareFileToPhone
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeContent(
    items: List<DriveItem>,
    allCloudFiles: List<DriveItem.File> = emptyList(),
    virtualFolders: List<DriveItem.Folder> = emptyList(),
    recentFiles: List<DriveItem.File> = emptyList(),
    transfers: Map<String, TransferInfo> = emptyMap(),
    totalStorageUsed: Long,
    searchQuery: String,
    sortOrder: SortOrder,
    filterType: FilterType,
    customCategory: CustomCategory = CustomCategory.NONE,
    selectedCategoryFolderId: String = "ALL",
    isRefreshing: Boolean,
    isInitialLoading: Boolean,
    currentFolderId: Long?,
    currentVirtualFolderId: String,
    currentFolderName: String?,
    isGridView: Boolean,
    connectivityStatus: ConnectivityObserver.Status,
    onNavigateToStarred: () -> Unit = {},
    onNavigateToSafeFolder: () -> Unit = {},
    onNavigateToCloudAnalysis: () -> Unit = {},
    onNavigateToPreview: (DriveItem.File) -> Unit,
    onRemoveFromRecents: (Long) -> Unit = {},
    onSearchQueryChange: (String) -> Unit,
    onToggleViewMode: () -> Unit,
    onSetSortOrder: (SortOrder) -> Unit,
    onSetFilterType: (FilterType) -> Unit,
    onSetCustomCategory: (CustomCategory) -> Unit = {},
    onSetSelectedCategoryFolder: (String) -> Unit = {},
    onCreateFolder: (String, Boolean, Boolean) -> Unit,
    onUploadFile: (String, String) -> Unit,
    onDeleteItems: (List<DriveItem>) -> Unit,
    onDownloadFile: (Long, Long, String) -> Unit,
    onDownloadFolderContents: (DriveItem.Folder) -> Unit,
    onToggleStarred: (DriveItem) -> Unit,
    onNavigateToFolder: (Long?, String?, String?) -> Unit,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    onRenameItem: (DriveItem, String) -> Unit,
    onPauseTransfer: (String) -> Unit = {},
    onResumeTransfer: (String) -> Unit = {},
    onCancelTransfer: (String) -> Unit = {},
    onRemoveTransfer: (String) -> Unit = {},
    onClearCompletedTransfers: () -> Unit = {},
    onSaveAsPhone: (String, String) -> Unit = { _, _ -> },
    onGetInviteLink: (Long, (String?) -> Unit) -> Unit,
    onGetMembers: (Long, (List<TdApi.User>) -> Unit) -> Unit,
    onShareFile: (String, Long, (Boolean, String?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var showNewSheet by remember { mutableStateOf(false) }

    var showUploadPopup by remember { mutableStateOf(false) }
    var showDownloadPopup by remember { mutableStateOf(false) }

    val uploadTransfers = remember(transfers) { transfers.values.filter { !it.isDownload } }
    val downloadTransfers = remember(transfers) { transfers.values.filter { it.isDownload } }

    val activeUploads = remember(uploadTransfers) {
        uploadTransfers.filter {
            it.status != TransferRepository.Status.COMPLETED &&
            it.status != TransferRepository.Status.CANCELLED &&
            !it.status.startsWith(TransferRepository.Status.FAILED)
        }
    }
    val activeDownloads = remember(downloadTransfers) {
        downloadTransfers.filter {
            it.status != TransferRepository.Status.COMPLETED &&
            it.status != TransferRepository.Status.CANCELLED &&
            !it.status.startsWith(TransferRepository.Status.FAILED)
        }
    }

    val uploadProgress = remember(activeUploads) {
        if (activeUploads.isEmpty()) null
        else (activeUploads.map { it.progress }.average().toFloat() * 100).toInt().coerceIn(0, 100)
    }
    val downloadProgress = remember(activeDownloads) {
        if (activeDownloads.isEmpty()) null
        else (activeDownloads.map { it.progress }.average().toFloat() * 100).toInt().coerceIn(0, 100)
    }

    val isOffline = connectivityStatus == ConnectivityObserver.Status.Unavailable || 
                    connectivityStatus == ConnectivityObserver.Status.Lost

    var selectedItems by remember { mutableStateOf(setOf<Long>()) }
    val isSelectionMode = selectedItems.isNotEmpty()
    var showFolderDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<List<DriveItem>?>(null) }
    var itemToRename by remember { mutableStateOf<DriveItem?>(null) }
    var renameValue by remember { mutableStateOf("") }

    // মিডিয়া ছাড়া বাকি সব ফাইল আগে ডাউনলোড হবে এবং মেইন ইন্টারফেসে পার্সেন্টেজ দেখাবে; ডাউনলোড হয়ে গেলে সরাসরি ওপেন হবে
    val handleFileItemClick: (DriveItem.File) -> Unit = { file ->
        if (isMediaStreamOrImage(file)) {
            onNavigateToPreview(file)
        } else {
            val localFile = file.localPath?.let { File(it) }
            if (localFile != null && localFile.exists()) {
                val isPdfOrTxt = file.mimeType == "application/pdf" || file.mimeType.startsWith("text/") || file.name.endsWith(".txt", true)
                if (isPdfOrTxt) {
                    onNavigateToPreview(file)
                } else {
                    FileSharingHelper.openFileExternally(context, localFile.absolutePath, file.mimeType)
                }
            } else {
                onDownloadFile(file.id, file.parentChatId, file.name)
                Toast.makeText(context, "Downloading ${file.name}...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showUploadPopup) {
        TransferPositionedPopup(
            title = "Uploads",
            isDownloadPopup = false,
            transferList = uploadTransfers,
            allCloudFiles = allCloudFiles,
            onDismiss = { showUploadPopup = false },
            onPauseTransfer = onPauseTransfer,
            onResumeTransfer = onResumeTransfer,
            onCancelTransfer = onCancelTransfer,
            onRemoveTransfer = onRemoveTransfer,
            onClearCompleted = onClearCompletedTransfers,
            onSaveAsPhone = onSaveAsPhone
        )
    }

    if (showDownloadPopup) {
        TransferPositionedPopup(
            title = "Downloads",
            isDownloadPopup = true,
            transferList = downloadTransfers,
            allCloudFiles = allCloudFiles,
            onDismiss = { showDownloadPopup = false },
            onPauseTransfer = onPauseTransfer,
            onResumeTransfer = onResumeTransfer,
            onCancelTransfer = onCancelTransfer,
            onRemoveTransfer = onRemoveTransfer,
            onClearCompleted = onClearCompletedTransfers,
            onSaveAsPhone = onSaveAsPhone
        )
    }

    if (itemToRename != null) {
        AlertDialog(
            onDismissRequest = { itemToRename = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    itemToRename?.let { onRenameItem(it, renameValue) }
                    itemToRename = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { itemToRename = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    var shareItem by remember { mutableStateOf<DriveItem?>(null) }
    var folderToDownload by remember { mutableStateOf<DriveItem.Folder?>(null) }
    var largeFileToUpload by remember { mutableStateOf<Pair<File, String>?>(null) }
    var isPreparingFile by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    val multiFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            isPreparingFile = true
            val customPrefs = context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
            val shouldDeleteOriginal = customPrefs.getBoolean("delete_after_backup", false)

            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    val fileName = cursor?.use { c ->
                        val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && nameIndex >= 0) c.getString(nameIndex) else null
                    } ?: "File_${System.currentTimeMillis()}"

                    // ফোনের আসল স্টোরেজ পাথ পাওয়া গেলে ডাবল-কপি ছাড়াই সরাসরি ফাস্ট আপলোড হবে
                    val directPhoneFile = resolveDirectFileFromUri(uri)
                    val uploadTargetFile = if (directPhoneFile != null && directPhoneFile.exists() && directPhoneFile.canRead()) {
                        directPhoneFile
                    } else {
                        val tempFile = File(context.cacheDir, fileName)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output, bufferSize = 128 * 1024)
                            }
                        }
                        if (shouldDeleteOriginal) {
                            deleteOriginalUriFromPhone(context, uri)
                        }
                        tempFile
                    }

                    withContext(Dispatchers.Main) {
                        if (uploadTargetFile.length() > 2000 * 1024 * 1024L) {
                            largeFileToUpload = uploadTargetFile to fileName
                        } else {
                            onUploadFile(uploadTargetFile.absolutePath, fileName)
                        }
                    }
                }
            }
            isPreparingFile = false
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        bitmap?.let {
            scope.launch {
                isPreparingFile = true
                withContext(Dispatchers.IO) {
                    val fileName = "IMG_${System.currentTimeMillis()}.jpg"
                    val file = File(context.cacheDir, fileName)
                    FileOutputStream(file).use { out ->
                        it.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                    }
                    withContext(Dispatchers.Main) {
                        onUploadFile(file.absolutePath, fileName)
                    }
                }
                isPreparingFile = false
            }
        }
    }

    val pressBackMsg = stringResource(R.string.press_back_again)
    var backPressedOnce by remember { mutableStateOf(false) }
    val isCategoryActive = filterType != FilterType.ALL || customCategory != CustomCategory.NONE

    BackHandler(enabled = true) {
        when {
            showNewSheet -> showNewSheet = false
            isSearchActive -> {
                isSearchActive = false
                onSearchQueryChange("")
            }
            isSelectionMode -> selectedItems = emptySet()
            (currentFolderId != null || currentVirtualFolderId != "0" || isCategoryActive) -> onNavigateBack()
            else -> {
                if (backPressedOnce) {
                    (context as? android.app.Activity)?.finish()
                } else {
                    backPressedOnce = true
                    Toast.makeText(context, pressBackMsg, Toast.LENGTH_SHORT).show()
                    scope.launch {
                        delay(2.seconds)
                        backPressedOnce = false
                    }
                }
            }
        }
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.delete_item_title)) },
            text = { Text(stringResource(R.string.delete_item_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm?.let { onDeleteItems(it) }
                    showDeleteConfirm = null
                    selectedItems = emptySet()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (shareItem != null) {
        ShareDialog(
            item = shareItem!!,
            onDismiss = { shareItem = null },
            onGetInviteLink = onGetInviteLink,
            onGetMembers = onGetMembers,
            onShareFile = onShareFile
        )
    }

    if (folderToDownload != null) {
        AlertDialog(
            onDismissRequest = { folderToDownload = null },
            title = { Text(stringResource(R.string.folder_download_warning_title)) },
            text = { Text(stringResource(R.string.folder_download_warning_message, folderToDownload?.name ?: "")) },
            confirmButton = {
                Button(onClick = {
                    folderToDownload?.let { onDownloadFolderContents(it) }
                    folderToDownload = null
                }) { Text(stringResource(R.string.download_contents)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToDownload = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (largeFileToUpload != null) {
        AlertDialog(
            onDismissRequest = { largeFileToUpload = null },
            title = { Text(stringResource(R.string.large_file_title)) },
            text = { Text(stringResource(R.string.large_file_message, largeFileToUpload?.second ?: "")) },
            confirmButton = {
                Button(onClick = {
                    largeFileToUpload?.let { (file, name) -> onUploadFile(file.absolutePath, name) }
                    largeFileToUpload = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { largeFileToUpload = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (isPreparingFile) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.preparing_file)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(stringResource(R.string.copying_to_temp))
                }
            },
            confirmButton = {}
        )
    }

    if (showNewSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
            ) {
                Text(stringResource(R.string.create_new), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    NewActionItem(Icons.Default.CreateNewFolder, stringResource(R.string.folder)) {
                        showNewSheet = false
                        showFolderDialog = true
                    }
                    NewActionItem(Icons.Default.FileUpload, stringResource(R.string.upload)) {
                        showNewSheet = false
                        multiFilePickerLauncher.launch("*/*")
                    }
                    NewActionItem(Icons.Default.CameraAlt, stringResource(R.string.camera)) {
                        showNewSheet = false
                        cameraLauncher.launch(null)
                    }
                }
            }
        }
    }

    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text(stringResource(R.string.create_folder)) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            onCreateFolder(newFolderName, true, false)
                            newFolderName = ""
                            showFolderDialog = false
                        }
                    }
                ) { Text(stringResource(R.string.create)) }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    val activeTitle = when {
        currentFolderName != null -> currentFolderName
        customCategory == CustomCategory.ALL_STORAGE -> "Telegram Cloud"
        customCategory == CustomCategory.RECENTS -> "Recents"
        customCategory == CustomCategory.APPS -> "Apps (APK)"
        customCategory == CustomCategory.ARCHIVES -> "ZIP & RAR"
        filterType == FilterType.PHOTOS -> "Images"
        filterType == FilterType.VIDEOS -> "Videos"
        filterType == FilterType.AUDIO -> "Audio"
        filterType == FilterType.DOCUMENTS -> "Documents"
        else -> stringResource(R.string.my_drive)
    }

    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = if (isSelectionMode) "selection" else if ((currentFolderId != null || currentVirtualFolderId != "0" || isCategoryActive) && !isSearchActive) "folder" else "main",
                transitionSpec = {
                    (fadeIn() + slideInVertically { -it / 2 }).togetherWith(fadeOut() + slideOutVertically { -it / 2 })
                },
                label = "TopBarTransition"
            ) { state ->
                when (state) {
                    "selection" -> {
                        TopAppBar(
                            title = { Text(stringResource(R.string.selected_count, selectedItems.size)) },
                            modifier = Modifier.statusBarsPadding(),
                            navigationIcon = {
                                IconButton(onClick = { selectedItems = emptySet() }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                                }
                            },
                            actions = {
                                IconButton(
                                    onClick = {
                                        val files = items.filterIsInstance<DriveItem.File>()
                                        selectedItems = if (selectedItems.size >= files.size && files.isNotEmpty()) emptySet() else files.map { it.id }.toSet()
                                    }
                                ) {
                                    val files = items.filterIsInstance<DriveItem.File>()
                                    Icon(
                                        if (selectedItems.size >= files.size && files.isNotEmpty()) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                        contentDescription = stringResource(R.string.select_all)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (isOffline) {
                                            Toast.makeText(context, context.getString(R.string.offline_msg), Toast.LENGTH_SHORT).show()
                                            return@IconButton
                                        }
                                        val selectedFiles = items.filter { it.id in selectedItems }.filterIsInstance<DriveItem.File>()
                                        val selectedFolders = items.filter { it.id in selectedItems }.filterIsInstance<DriveItem.Folder>()
                                        selectedFiles.forEach { onDownloadFile(it.id, it.parentChatId, it.name) }
                                        selectedFolders.forEach { onDownloadFolderContents(it) }
                                        selectedItems = emptySet()
                                    }
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = stringResource(R.string.download))
                                }
                                IconButton(
                                    onClick = {
                                        if (isOffline) {
                                            Toast.makeText(context, context.getString(R.string.offline_msg), Toast.LENGTH_SHORT).show()
                                            return@IconButton
                                        }
                                        showDeleteConfirm = items.filter { it.id in selectedItems }
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                                }
                            }
                        )
                    }
                    "folder" -> {
                        Column {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = activeTitle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { onNavigateBack() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                    }
                                },
                                actions = {
                                    IconButton(onClick = { isSearchActive = true }) {
                                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_teledrive))
                                    }
                                    IconButton(onClick = { onToggleViewMode() }) {
                                        Icon(if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, contentDescription = null)
                                    }
                                },
                                modifier = Modifier.statusBarsPadding()
                            )
                            if (isCategoryActive && customCategory != CustomCategory.ALL_STORAGE && currentVirtualFolderId == "0") {
                                CategoryFolderTabsRow(
                                    virtualFolders = virtualFolders,
                                    selectedFolderId = selectedCategoryFolderId,
                                    sortOrder = sortOrder,
                                    onSelectFolder = onSetSelectedCategoryFolder,
                                    onSetSortOrder = onSetSortOrder,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    "main" -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            if (!isSearchActive) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // বাম পাশে ⬆️ ডাউনলোড বার
                                    SplitTransferBarItem(
                                        icon = Icons.Default.ArrowUpward,
                                        percentage = downloadProgress,
                                        hasActiveOrHistory = downloadTransfers.isNotEmpty(),
                                        onClick = { showDownloadPopup = true }
                                    )

                                    // মাঝখানে বড় গ্লাসি সার্চ বার
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(26.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                                )
                                            )
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp)
                                            .clickable { isSearchActive = true }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 16.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Search,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = "Search",
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    // ডান পাশে ⬇️ আপলোড বার
                                    SplitTransferBarItem(
                                        icon = Icons.Default.ArrowDownward,
                                        percentage = uploadProgress,
                                        hasActiveOrHistory = uploadTransfers.isNotEmpty(),
                                        onClick = { showUploadPopup = true }
                                    )
                                }
                            } else {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(26.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    ) {
                                        IconButton(onClick = {
                                            isSearchActive = false
                                            onSearchQueryChange("")
                                        }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                            BasicTextField(
                                                value = searchQuery,
                                                onValueChange = onSearchQueryChange,
                                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            if (searchQuery.isEmpty()) {
                                                Text("Search", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { onSearchQueryChange("") }) {
                                                Icon(Icons.Default.Close, contentDescription = null)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isOffline) {
                ExtendedFloatingActionButton(
                    onClick = { showNewSheet = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(R.string.new_label), fontWeight = FontWeight.SemiBold) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(8.dp)
                )
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (isInitialLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    if (isRefreshing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                
                    if (isOffline) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.offline_msg), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    val showDashboard = currentVirtualFolderId == "0" && searchQuery.isEmpty() && !isCategoryActive

                    if (showDashboard) {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item {
                                GoogleFilesDashboardSection(
                                    totalStorageUsed = totalStorageUsed,
                                    allFiles = allCloudFiles.ifEmpty { items.filterIsInstance<DriveItem.File>() },
                                    recentFiles = recentFiles,
                                    transfers = transfers,
                                    onSelectFilter = onSetFilterType,
                                    onSelectCustom = onSetCustomCategory,
                                    onOpenFile = handleFileItemClick,
                                    onShareFile = { shareItem = it },
                                    onDownloadFile = { onDownloadFile(it.id, it.parentChatId, it.name) },
                                    onSaveAsPhone = onSaveAsPhone,
                                    onStarFile = onToggleStarred,
                                    onRemoveRecent = onRemoveFromRecents,
                                    onNavigateToStarred = onNavigateToStarred,
                                    onNavigateToSafeFolder = onNavigateToSafeFolder,
                                    onNavigateToCloudAnalysis = onNavigateToCloudAnalysis
                                )
                                Spacer(modifier = Modifier.height(80.dp))
                            }
                        }
                    } else if (items.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = if (searchQuery.isEmpty()) Icons.Default.CloudQueue else Icons.Default.SearchOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(80.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = if (searchQuery.isEmpty()) stringResource(R.string.drive_empty) else stringResource(R.string.no_results),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        if (isGridView) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(items, key = { it.id }) { item ->
                                    val isSelected = selectedItems.contains(item.id)
                                    val itemTransfer = if (item is DriveItem.File) {
                                        transfers[item.remoteUniqueId] ?: transfers.values.find { it.fileId == item.telegramFileId || it.fileName == item.name }
                                    } else null

                                    DriveGridItem(
                                        item = item,
                                        isSelected = isSelected,
                                        activeTransfer = itemTransfer,
                                        onClick = {
                                            if (isSelectionMode) {
                                                selectedItems = if (isSelected) selectedItems - item.id else selectedItems + item.id
                                            } else {
                                                if (item is DriveItem.File) handleFileItemClick(item)
                                                else if (item is DriveItem.Folder) onNavigateToFolder(null, item.virtualId, item.name)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectionMode) selectedItems = setOf(item.id)
                                        },
                                        onStarClick = { onToggleStarred(item) },
                                        onShareClick = { shareItem = item },
                                        onDownloadClick = {
                                            if (item is DriveItem.File) onDownloadFile(item.id, item.parentChatId, item.name)
                                            else if (item is DriveItem.Folder) folderToDownload = item
                                        },
                                        onSaveAsPhoneClick = {
                                            if (item is DriveItem.File && item.localPath != null) {
                                                onSaveAsPhone(item.localPath, item.name)
                                            }
                                        },
                                        onMoveClick = null,
                                        onRenameClick = {
                                            itemToRename = item
                                            renameValue = item.name
                                        },
                                        onDeleteClick = { showDeleteConfirm = listOf(item) }
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(bottom = 88.dp, top = 4.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(items, key = { it.id }) { item ->
                                    val isSelected = selectedItems.contains(item.id)
                                    val itemTransfer = if (item is DriveItem.File) {
                                        transfers[item.remoteUniqueId] ?: transfers.values.find { it.fileId == item.telegramFileId || it.fileName == item.name }
                                    } else null

                                    DriveListItem(
                                        item = item,
                                        isSelected = isSelected,
                                        activeTransfer = itemTransfer,
                                        onClick = {
                                            if (isSelectionMode) {
                                                selectedItems = if (isSelected) selectedItems - item.id else selectedItems + item.id
                                            } else {
                                                if (item is DriveItem.File) handleFileItemClick(item)
                                                else if (item is DriveItem.Folder) onNavigateToFolder(null, item.virtualId, item.name)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectionMode) selectedItems = setOf(item.id)
                                        },
                                        onStarClick = { onToggleStarred(item) },
                                        onShareClick = { shareItem = item },
                                        onDownloadClick = {
                                            if (item is DriveItem.File) onDownloadFile(item.id, item.parentChatId, item.name)
                                            else if (item is DriveItem.Folder) folderToDownload = item
                                        },
                                        onSaveAsPhoneClick = {
                                            if (item is DriveItem.File && item.localPath != null) {
                                                onSaveAsPhone(item.localPath, item.name)
                                            }
                                        },
                                        onMoveClick = null,
                                        onRenameClick = {
                                            itemToRename = item
                                            renameValue = item.name
                                        },
                                        onDeleteClick = { showDeleteConfirm = listOf(item) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SplitTransferBarItem(
    icon: ImageVector,
    percentage: Int?,
    hasActiveOrHistory: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(26.dp),
        color = if (percentage != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            1.dp,
            if (percentage != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier.height(52.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = if (percentage != null) 14.dp else 16.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (percentage != null) {
                    CircularProgressIndicator(
                        progress = { percentage / 100f },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (percentage != null || hasActiveOrHistory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (percentage != null) 14.dp else 20.dp)
                )
            }
            if (percentage != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$percentage%",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
fun TransferPositionedPopup(
    title: String,
    isDownloadPopup: Boolean,
    transferList: List<TransferInfo>,
    allCloudFiles: List<DriveItem.File>,
    onDismiss: () -> Unit,
    onPauseTransfer: (String) -> Unit,
    onResumeTransfer: (String) -> Unit,
    onCancelTransfer: (String) -> Unit,
    onRemoveTransfer: (String) -> Unit,
    onClearCompleted: () -> Unit,
    onSaveAsPhone: (String, String) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .padding(horizontal = 16.dp, vertical = 28.dp),
            contentAlignment = if (isDownloadPopup) Alignment.TopCenter else Alignment.BottomCenter
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                tonalElevation = 10.dp,
                shadowElevation = 16.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isDownloadPopup) Icons.Default.Download else Icons.Default.Upload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        }
                        if (transferList.isNotEmpty()) {
                            IconButton(onClick = onClearCompleted) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (transferList.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(90.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isDownloadPopup) "No active downloads" else "No active uploads",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(transferList, key = { it.remoteUniqueId }) { transfer ->
                                val isPaused = transfer.status == TransferRepository.Status.PAUSED
                                val percent = (transfer.progress * 100).toInt().coerceIn(0, 100)

                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = transfer.fileName,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${transfer.status} • ${formatSize(transfer.downloadedSize)} / ${formatSize(transfer.totalSize)} ($percent%)",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }

                                            IconButton(
                                                onClick = {
                                                    if (isPaused) onResumeTransfer(transfer.remoteUniqueId)
                                                    else onPauseTransfer(transfer.remoteUniqueId)
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                                    contentDescription = if (isPaused) "Resume" else "Pause",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            IconButton(onClick = { onCancelTransfer(transfer.remoteUniqueId) }) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Cancel",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        LinearProgressIndicator(
                                            progress = { transfer.progress },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryFolderTabsRow(
    virtualFolders: List<DriveItem.Folder>,
    selectedFolderId: String,
    sortOrder: SortOrder,
    onSelectFolder: (String) -> Unit,
    onSetSortOrder: (SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.weight(1f)
        ) {
            item {
                val isAll = selectedFolderId == "ALL"
                FilterChip(
                    selected = isAll,
                    onClick = { onSelectFolder("ALL") },
                    label = { Text("All") },
                    leadingIcon = if (isAll) {
                        { Icon(Icons.Default.Check, null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null
                )
            }
            items(virtualFolders, key = { it.virtualId ?: it.id.toString() }) { folder ->
                val vId = folder.virtualId ?: "0"
                val isSelected = selectedFolderId == vId
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectFolder(vId) },
                    label = { Text(folder.name) },
                    leadingIcon = {
                        Icon(
                            if (isSelected) Icons.Default.Check else Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    }
                )
            }
        }

        var showSortMenu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { showSortMenu = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
            }
            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_name)) },
                    onClick = { onSetSortOrder(SortOrder.NAME); showSortMenu = false },
                    leadingIcon = { if (sortOrder == SortOrder.NAME) Icon(Icons.Default.Check, null) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_date)) },
                    onClick = { onSetSortOrder(SortOrder.DATE); showSortMenu = false },
                    leadingIcon = { if (sortOrder == SortOrder.DATE) Icon(Icons.Default.Check, null) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_size)) },
                    onClick = { onSetSortOrder(SortOrder.SIZE); showSortMenu = false },
                    leadingIcon = { if (sortOrder == SortOrder.SIZE) Icon(Icons.Default.Check, null) }
                )
            }
        }
    }
}

@Composable
fun GoogleFilesDashboardSection(
    totalStorageUsed: Long,
    allFiles: List<DriveItem.File>,
    recentFiles: List<DriveItem.File>,
    transfers: Map<String, TransferInfo>,
    onSelectFilter: (FilterType) -> Unit,
    onSelectCustom: (CustomCategory) -> Unit,
    onOpenFile: (DriveItem.File) -> Unit,
    onShareFile: (DriveItem.File) -> Unit,
    onDownloadFile: (DriveItem.File) -> Unit,
    onSaveAsPhone: (String, String) -> Unit,
    onStarFile: (DriveItem.File) -> Unit,
    onRemoveRecent: (Long) -> Unit,
    onNavigateToStarred: () -> Unit,
    onNavigateToSafeFolder: () -> Unit,
    onNavigateToCloudAnalysis: () -> Unit
) {
    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }

    val sectionScale by animateFloatAsState(
        targetValue = if (appear) 1f else 0.94f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "DashboardScale"
    )

    val videoSize = allFiles.filter { it.mimeType.startsWith("video/") }.sumOf { it.size }
    val imageSize = allFiles.filter { it.mimeType.startsWith("image/") }.sumOf { it.size }
    val audioSize = allFiles.filter { it.mimeType.startsWith("audio/") }.sumOf { it.size }
    val appSize = allFiles.filter { it.mimeType.contains("android.package-archive") || it.name.lowercase().endsWith(".apk") }.sumOf { it.size }
    val archiveSize = allFiles.filter {
        val n = it.name.lowercase()
        it.mimeType.contains("zip") || it.mimeType.contains("rar") || it.mimeType.contains("7z") || n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z")
    }.sumOf { it.size }
    val docSize = (allFiles.sumOf { it.size } - (videoSize + imageSize + audioSize + appSize + archiveSize)).coerceAtLeast(0L)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .scale(sectionScale)
    ) {
        // 1. Recents Section
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recents",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (recentFiles.isNotEmpty()) {
                Text(
                    text = "See all",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectCustom(CustomCategory.RECENTS) }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        if (recentFiles.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().height(110.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Recently opened files will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(recentFiles.take(10), key = { it.id }) { file ->
                    val activeTransfer = transfers[file.remoteUniqueId] ?: transfers.values.find { it.fileId == file.telegramFileId || it.fileName == file.name }
                    RecentOpenedFileCard(
                        file = file,
                        activeTransfer = activeTransfer,
                        onClick = { onOpenFile(file) },
                        onShareClick = { onShareFile(file) },
                        onDownloadClick = { onDownloadFile(file) },
                        onSaveAsPhoneClick = {
                            if (file.localPath != null) onSaveAsPhone(file.localPath, file.name)
                        },
                        onStarClick = { onStarFile(file) },
                        onRemoveRecentClick = { onRemoveRecent(file.id) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(26.dp))

        // 2. Categories Section with Glassmorphic Cards & Accent Badges
        Text(
            text = "Categories",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                GoogleFilesCategoryCard(
                    title = "ZIP & RAR",
                    subtitle = formatSize(archiveSize),
                    icon = Icons.Default.FolderZip,
                    accentColor = Color(0xFFA855F7),
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectCustom(CustomCategory.ARCHIVES) }
                )
                GoogleFilesCategoryCard(
                    title = "Images",
                    subtitle = formatSize(imageSize),
                    icon = Icons.Outlined.Image,
                    accentColor = Color(0xFF10B981),
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectFilter(FilterType.PHOTOS) }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                GoogleFilesCategoryCard(
                    title = "Videos",
                    subtitle = formatSize(videoSize),
                    icon = Icons.Outlined.Movie,
                    accentColor = Color(0xFFF43F5E),
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectFilter(FilterType.VIDEOS) }
                )
                GoogleFilesCategoryCard(
                    title = "Audio",
                    subtitle = formatSize(audioSize),
                    icon = Icons.Default.MusicNote,
                    accentColor = Color(0xFFF59E0B),
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectFilter(FilterType.AUDIO) }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                GoogleFilesCategoryCard(
                    title = "Documents",
                    subtitle = formatSize(docSize),
                    icon = Icons.Default.Description,
                    accentColor = Color(0xFF0EA5E9),
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectFilter(FilterType.DOCUMENTS) }
                )
                GoogleFilesCategoryCard(
                    title = "Apps",
                    subtitle = formatSize(appSize),
                    icon = Icons.Default.Apps,
                    accentColor = Color(0xFF22C55E),
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectCustom(CustomCategory.APPS) }
                )
            }
        }

        Spacer(modifier = Modifier.height(26.dp))

        // 3. Collections Section
        Text(
            text = "Collections",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            GoogleFilesCollectionCard(
                title = "Starred",
                icon = Icons.Outlined.StarOutline,
                accentColor = Color(0xFFEAB308),
                modifier = Modifier.weight(1f),
                onClick = onNavigateToStarred
            )
            GoogleFilesCollectionCard(
                title = "Safe folder",
                icon = Icons.Outlined.Lock,
                accentColor = Color(0xFF38BDF8),
                modifier = Modifier.weight(1f),
                onClick = onNavigateToSafeFolder
            )
        }

        Spacer(modifier = Modifier.height(26.dp))

        // 4. All storage Section
        Text(
            text = "All storage",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            GoogleFilesStorageCard(
                title = "Telegram Cloud",
                subtitle = "${formatSize(totalStorageUsed)} used • Unlimited",
                icon = Icons.Default.CloudQueue,
                accentColor = Color(0xFF00B4DB),
                modifier = Modifier.weight(1f),
                onClick = { onSelectCustom(CustomCategory.ALL_STORAGE) }
            )
            GoogleFilesStorageCard(
                title = "Cloud Analysis",
                subtitle = "${allFiles.size} files stored",
                icon = Icons.Default.Storage,
                accentColor = Color(0xFF6366F1),
                modifier = Modifier.weight(1f),
                onClick = onNavigateToCloudAnalysis
            )
        }
    }
}

@Composable
fun RecentOpenedFileCard(
    file: DriveItem.File,
    activeTransfer: TransferInfo? = null,
    onClick: () -> Unit,
    onShareClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onSaveAsPhoneClick: () -> Unit = {},
    onStarClick: () -> Unit,
    onRemoveRecentClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val (icon, accent) = getFileIconAndColor(file)
    val thumbnailModel = file.thumbnailPath?.takeIf { File(it).exists() } ?: file.localPath?.takeIf { File(it).exists() }
    val isDownloaded = file.localPath != null && File(file.localPath).exists()

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                colors = listOf(accent.copy(alpha = 0.35f), MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            )
        ),
        modifier = Modifier.width(172.dp).height(210.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (thumbnailModel != null) {
                AsyncImage(
                    model = thumbnailModel,
                    contentDescription = file.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.72f))
                            )
                        )
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = accent.copy(alpha = 0.16f),
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                }
            }

            if (file.mimeType.startsWith("video/") && activeTransfer == null) {
                Icon(
                    imageVector = Icons.Default.PlayCircleFilled,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(38.dp).align(Alignment.Center)
                )
            }

            // মেইন ইন্টারফেসে লাইভ ডাউনলোড পার্সেন্টেজ ওভারলে
            if (activeTransfer != null) {
                val pct = (activeTransfer.progress * 100).toInt().coerceIn(0, 100)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            progress = { activeTransfer.progress },
                            color = Color(0xFF00E5FF),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "$pct%",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Box(modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                    modifier = Modifier.size(34.dp)
                ) {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (file.isStarred) stringResource(R.string.remove_star) else stringResource(R.string.add_star)) },
                        onClick = { showMenu = false; onStarClick() },
                        leadingIcon = { Icon(if (file.isStarred) Icons.Default.Star else Icons.Outlined.StarOutline, null) }
                    )
                    if (isDownloaded) {
                        DropdownMenuItem(
                            text = { Text("Save as Phone") },
                            onClick = { showMenu = false; onSaveAsPhoneClick() },
                            leadingIcon = { Icon(Icons.Default.PhoneAndroid, null) }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.download)) },
                            onClick = { showMenu = false; onDownloadClick() },
                            leadingIcon = { Icon(Icons.Default.Download, null) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        onClick = { showMenu = false; onShareClick() },
                        leadingIcon = { Icon(Icons.Default.Share, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Remove from Recents") },
                        onClick = { showMenu = false; onRemoveRecentClick() },
                        leadingIcon = { Icon(Icons.Default.Close, null) }
                    )
                }
            }

            Column(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp)
            ) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (thumbnailModel != null) Color.White else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatSize(file.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (thumbnailModel != null) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun GoogleFilesCategoryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                colors = listOf(accentColor.copy(alpha = 0.35f), MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
            )
        ),
        modifier = modifier.height(72.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = accentColor.copy(alpha = 0.16f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun GoogleFilesCollectionCard(
    title: String,
    icon: ImageVector,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                colors = listOf(accentColor.copy(alpha = 0.35f), MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
            )
        ),
        modifier = modifier.height(64.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = accentColor.copy(alpha = 0.16f),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun GoogleFilesStorageCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                colors = listOf(accentColor.copy(alpha = 0.4f), MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
            )
        ),
        modifier = modifier.height(76.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = accentColor.copy(alpha = 0.16f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun NewActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(16.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = CircleShape,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun FilterSortRow(
    filterType: FilterType,
    customCategory: CustomCategory = CustomCategory.NONE,
    sortOrder: SortOrder,
    onSetFilterType: (FilterType) -> Unit,
    onSetCustomCategory: (CustomCategory) -> Unit = {},
    onSetSortOrder: (SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.weight(1f)
        ) {
            item {
                val isAllSelected = filterType == FilterType.ALL && customCategory == CustomCategory.NONE
                FilterChip(
                    selected = isAllSelected,
                    onClick = { onSetFilterType(FilterType.ALL) },
                    label = { Text(stringResource(R.string.filter_all)) },
                    leadingIcon = if (isAllSelected) {
                        { Icon(Icons.Default.Check, null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null
                )
            }
            val standardCategories = listOf(FilterType.VIDEOS, FilterType.AUDIO, FilterType.PHOTOS, FilterType.DOCUMENTS)
            items(standardCategories) { category ->
                val isSelected = customCategory == CustomCategory.NONE && filterType == category
                FilterChip(
                    selected = isSelected,
                    onClick = { onSetFilterType(category) },
                    label = {
                        Text(
                            when (category) {
                                FilterType.PHOTOS -> stringResource(R.string.filter_photos)
                                FilterType.VIDEOS -> stringResource(R.string.filter_videos)
                                FilterType.AUDIO -> stringResource(R.string.filter_audio)
                                FilterType.DOCUMENTS -> stringResource(R.string.filter_documents)
                                else -> ""
                            }
                        )
                    },
                    leadingIcon = if (isSelected) {
                        { Icon(Icons.Default.Check, null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null
                )
            }
        }

        var showSortMenu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { showSortMenu = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
            }
            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_name)) },
                    onClick = { onSetSortOrder(SortOrder.NAME); showSortMenu = false },
                    leadingIcon = { if (sortOrder == SortOrder.NAME) Icon(Icons.Default.Check, null) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_date)) },
                    onClick = { onSetSortOrder(SortOrder.DATE); showSortMenu = false },
                    leadingIcon = { if (sortOrder == SortOrder.DATE) Icon(Icons.Default.Check, null) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_size)) },
                    onClick = { onSetSortOrder(SortOrder.SIZE); showSortMenu = false },
                    leadingIcon = { if (sortOrder == SortOrder.SIZE) Icon(Icons.Default.Check, null) }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DriveListItem(
    item: DriveItem,
    isSelected: Boolean,
    activeTransfer: TransferInfo? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStarClick: () -> Unit,
    onShareClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onSaveAsPhoneClick: (() -> Unit)? = null,
    onRenameClick: (() -> Unit)? = null,
    onMoveClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null
) {
    val (icon, color) = getFileIconAndColor(item)
    val isSecure = (item is DriveItem.Folder && item.isSecure) || (item is DriveItem.File && item.isEncrypted)
    val isDownloaded = item is DriveItem.File && item.localPath != null && File(item.localPath).exists()

    ListItem(
        headlineContent = { 
            Text(
                text = item.name,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            if (activeTransfer != null) {
                val pct = (activeTransfer.progress * 100).toInt().coerceIn(0, 100)
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        text = "${activeTransfer.status} • $pct% (${formatSize(activeTransfer.downloadedSize)} / ${formatSize(activeTransfer.totalSize)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { activeTransfer.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                    )
                }
            } else if (item is DriveItem.File) {
                Text(
                    text = if (isDownloaded) "${formatSize(item.size)} • Ready" else formatSize(item.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(stringResource(R.string.folder), style = MaterialTheme.typography.bodySmall)
            }
        },
        leadingContent = {
            Surface(
                color = if (item is DriveItem.Folder) MaterialTheme.colorScheme.primaryContainer else color.copy(alpha = 0.14f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val thumbnailModel = if (item is DriveItem.File) {
                        item.thumbnailPath?.takeIf { File(it).exists() } ?: item.localPath?.takeIf { File(it).exists() }
                    } else null

                    if (thumbnailModel != null) {
                        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                        AsyncImage(
                            model = thumbnailModel,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(imageVector = icon, contentDescription = null, tint = color)
                    }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSelected) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                var showItemMenu by remember { mutableStateOf(false) }
                var showInfoDialog by remember { mutableStateOf(false) }
                val isVirtual = item is DriveItem.Folder && item.isVirtual

                IconButton(onClick = { showItemMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }

                DropdownMenu(expanded = showItemMenu, onDismissRequest = { showItemMenu = false }) {
                    if (!isVirtual && !isSecure) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share)) },
                            onClick = { onShareClick(); showItemMenu = false },
                            leadingIcon = { Icon(Icons.Default.Share, null) }
                        )
                    }
                    if (!isSecure) {
                        DropdownMenuItem(
                            text = { Text(if (item.isStarred) stringResource(R.string.remove_star) else stringResource(R.string.add_star)) },
                            onClick = { onStarClick(); showItemMenu = false },
                            leadingIcon = { Icon(if (item.isStarred) Icons.Default.Star else Icons.Outlined.StarOutline, null) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.info)) },
                        onClick = { showInfoDialog = true; showItemMenu = false },
                        leadingIcon = { Icon(Icons.Default.Info, null) }
                    )
                    onRenameClick?.let {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            onClick = { it(); showItemMenu = false },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                    }
                    if (!isSecure) {
                        if (isDownloaded && onSaveAsPhoneClick != null) {
                            DropdownMenuItem(
                                text = { Text("Save as Phone") },
                                onClick = { onSaveAsPhoneClick(); showItemMenu = false },
                                leadingIcon = { Icon(Icons.Default.PhoneAndroid, null) }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(if (item is DriveItem.File) stringResource(R.string.download) else stringResource(R.string.download_contents)) },
                                onClick = { onDownloadClick(); showItemMenu = false },
                                leadingIcon = { Icon(Icons.Default.Download, null) }
                            )
                        }
                    }
                    onDeleteClick?.let {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = { it(); showItemMenu = false },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
                if (showInfoDialog) InfoDialog(item = item, onDismiss = { showInfoDialog = false })
            }
        },
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface)
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DriveGridItem(
    item: DriveItem,
    isSelected: Boolean,
    activeTransfer: TransferInfo? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStarClick: () -> Unit,
    onShareClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onSaveAsPhoneClick: (() -> Unit)? = null,
    onRenameClick: (() -> Unit)? = null,
    onMoveClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null
) {
    var showItemMenu by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    val (icon, color) = getFileIconAndColor(item)
    val isSecure = (item is DriveItem.Folder && item.isSecure) || (item is DriveItem.File && item.isEncrypted)
    val isDownloaded = item is DriveItem.File && item.localPath != null && File(item.localPath).exists()

    OutlinedCard(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Box {
            Column(
                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(68.dp).background(color.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                ) {
                    val thumbnailModel = if (item is DriveItem.File) {
                        item.thumbnailPath?.takeIf { File(it).exists() } ?: item.localPath?.takeIf { File(it).exists() }
                    } else null

                    if (thumbnailModel != null) {
                        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = color)
                        AsyncImage(
                            model = thumbnailModel,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(34.dp), tint = color)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (activeTransfer != null) {
                    val pct = (activeTransfer.progress * 100).toInt().coerceIn(0, 100)
                    LinearProgressIndicator(
                        progress = { activeTransfer.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("$pct%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { showItemMenu = true }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        val isVirtual = item is DriveItem.Folder && item.isVirtual
                        DropdownMenu(expanded = showItemMenu, onDismissRequest = { showItemMenu = false }) {
                            if (!isVirtual && !isSecure) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share)) },
                                    onClick = { onShareClick(); showItemMenu = false },
                                    leadingIcon = { Icon(Icons.Default.Share, null) }
                                )
                            }
                            if (!isSecure) {
                                DropdownMenuItem(
                                    text = { Text(if (item.isStarred) stringResource(R.string.remove_star) else stringResource(R.string.add_star)) },
                                    onClick = { onStarClick(); showItemMenu = false },
                                    leadingIcon = { Icon(if (item.isStarred) Icons.Default.Star else Icons.Outlined.StarOutline, null) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.info)) },
                                onClick = { showInfoDialog = true; showItemMenu = false },
                                leadingIcon = { Icon(Icons.Default.Info, null) }
                            )
                            onRenameClick?.let {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rename)) },
                                    onClick = { it(); showItemMenu = false },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) }
                                )
                            }
                            if (!isSecure) {
                                if (isDownloaded && onSaveAsPhoneClick != null) {
                                    DropdownMenuItem(
                                        text = { Text("Save as Phone") },
                                        onClick = { onSaveAsPhoneClick(); showItemMenu = false },
                                        leadingIcon = { Icon(Icons.Default.PhoneAndroid, null) }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text(if (item is DriveItem.File) stringResource(R.string.download) else stringResource(R.string.download_contents)) },
                                        onClick = { onDownloadClick(); showItemMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Download, null) }
                                    )
                                }
                            }
                            onDeleteClick?.let {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    onClick = { it(); showItemMenu = false },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                                )
                            }
                        }
                    }
                }
            }
            if (isSelected) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
        }
    }
    if (showInfoDialog) InfoDialog(item = item, onDismiss = { showInfoDialog = false })
}

@Composable
fun InfoDialog(item: DriveItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.item_info)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.name_label, item.name), style = MaterialTheme.typography.bodyMedium)
                if (item is DriveItem.File) {
                    Text(stringResource(R.string.size_label, formatSize(item.size)), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.type_label, item.mimeType), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(
                            R.string.status_label, 
                            if (item.localPath != null) stringResource(R.string.available_offline) else stringResource(R.string.cloud_only)
                        ), 
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(stringResource(R.string.type_label, stringResource(R.string.folder)), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun ShareDialog(
    item: DriveItem,
    onDismiss: () -> Unit,
    onGetInviteLink: (Long, (String?) -> Unit) -> Unit,
    onGetMembers: (Long, (List<TdApi.User>) -> Unit) -> Unit,
    onShareFile: (String, Long, (Boolean, String?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var shareToPhone by remember { mutableStateOf("") }
    var isSharingFile by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (item is DriveItem.Folder) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.share_item, item.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (item is DriveItem.File) {
                    Text(stringResource(R.string.share_to_telegram), style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = shareToPhone,
                        onValueChange = { shareToPhone = it },
                        placeholder = { Text("+880...") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Phone, null) }
                    )
                    if (isSharingFile) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (item is DriveItem.File) {
                Button(
                    onClick = {
                        if (shareToPhone.isNotBlank()) {
                            isSharingFile = true
                            onShareFile(shareToPhone, item.id) { success, error ->
                                isSharingFile = false
                                if (success) {
                                    Toast.makeText(context, context.getString(R.string.share_success), Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.failed_with_error, error ?: ""), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = !isSharingFile && shareToPhone.isNotBlank()
                ) { Text(stringResource(R.string.send)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

private fun resolveDirectFileFromUri(uri: Uri): File? {
    return try {
        val path = uri.path ?: return null
        when {
            path.contains("primary:") -> File("/storage/emulated/0/${path.substringAfter("primary:")}")
            path.startsWith("/storage/") -> File(path)
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

private fun deleteOriginalUriFromPhone(context: Context, uri: Uri) {
    try {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
            return
        }
    } catch (e: Exception) {}
    try {
        val deleted = context.contentResolver.delete(uri, null, null)
        if (deleted > 0) return
    } catch (e: Exception) {}
    try {
        resolveDirectFileFromUri(uri)?.let { if (it.exists()) it.delete() }
    } catch (e: Exception) {}
}

@Composable
private fun getFileIconAndColor(item: DriveItem): Pair<ImageVector, Color> {
    if (item is DriveItem.Folder) {
        return if (item.isSecure) Icons.Default.Folder to Color(0xFF2196F3)
        else Icons.Default.Folder to MaterialTheme.colorScheme.primary
    } else {
        val file = item as DriveItem.File
        val lowerName = file.name.lowercase()
        return when {
            file.mimeType.contains("android.package-archive") || lowerName.endsWith(".apk") -> Icons.Default.Android to Color(0xFF22C55E)
            file.mimeType.contains("zip") || file.mimeType.contains("rar") || file.mimeType.contains("7z") || lowerName.endsWith(".zip") || lowerName.endsWith(".rar") || lowerName.endsWith(".7z") -> Icons.Default.FolderZip to Color(0xFFA855F7)
            file.mimeType.startsWith("image/") -> Icons.Default.Image to Color(0xFF10B981)
            file.mimeType.startsWith("video/") -> Icons.Default.VideoFile to Color(0xFFF43F5E)
            file.mimeType.startsWith("audio/") -> Icons.Default.AudioFile to Color(0xFFF59E0B)
            file.mimeType == "application/pdf" || lowerName.endsWith(".pdf") -> Icons.Default.PictureAsPdf to Color(0xFFEF4444)
            file.mimeType.contains("text/") || lowerName.endsWith(".txt") -> Icons.AutoMirrored.Filled.Article to Color(0xFF0EA5E9)
            else -> Icons.AutoMirrored.Filled.InsertDriveFile to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
}

private fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
}
