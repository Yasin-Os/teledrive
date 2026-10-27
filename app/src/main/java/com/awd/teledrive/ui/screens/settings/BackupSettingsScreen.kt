package com.awd.teledrive.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.awd.teledrive.R
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val selectedFolders by viewModel.backupFolders.collectAsState()
    val isAutoBackupEnabled by viewModel.isAutoBackupEnabled.collectAsState()
    val availableFolders = remember { mutableStateListOf<String>() }

    var hasAllFilesAccess by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val workInfoList by viewModel.backupWorkInfo.collectAsState(initial = emptyList())
    val activeWorkInfo = workInfoList.find { it.state == androidx.work.WorkInfo.State.RUNNING } ?: workInfoList.firstOrNull()
    val backupStartedMsg = stringResource(R.string.backup_started)

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {}

            val rawPath = it.path ?: ""
            val resolvedPath = when {
                rawPath.contains("primary:") -> "/storage/emulated/0/${rawPath.substringAfter("primary:")}"
                rawPath.contains(":") -> "/storage/emulated/0/${rawPath.substringAfterLast(":")}"
                else -> null
            }

            if (resolvedPath != null) {
                viewModel.addCustomBackupFolder(resolvedPath)
                if (!availableFolders.contains(resolvedPath)) {
                    availableFolders.add(0, resolvedPath)
                }
                if (!isAutoBackupEnabled) {
                    viewModel.setAutoBackupEnabled(true)
                }
            } else {
                Toast.makeText(context, context.getString(R.string.err_folder_path), Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(hasAllFilesAccess, selectedFolders) {
        val folders = linkedSetOf<String>()
        val rootStorage = Environment.getExternalStorageDirectory()

        // ফোনের প্রধান স্টোরেজ ফোল্ডারগুলো
        val commonDirs = listOf(
            File(rootStorage, "DCIM/Camera"),
            File(rootStorage, "DCIM/Screenshots"),
            File(rootStorage, "Pictures/Screenshots"),
            File(rootStorage, "Download"),
            File(rootStorage, "Pictures"),
            File(rootStorage, "Movies"),
            File(rootStorage, "Music"),
            File(rootStorage, "Documents"),
            File(rootStorage, "WhatsApp/Media/WhatsApp Images"),
            File(rootStorage, "WhatsApp/Media/WhatsApp Video"),
            File(rootStorage, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"),
            File(rootStorage, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video")
        )
        commonDirs.forEach { dir ->
            if (dir.exists() && dir.isDirectory) folders.add(dir.absolutePath)
        }

        // ফোন মেমোরির সব মেইন ফোল্ডার স্ক্যান
        rootStorage.listFiles()?.forEach { dir ->
            if (dir.isDirectory && !dir.name.startsWith(".") && dir.name != "Android") {
                folders.add(dir.absolutePath)
            }
        }

        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).forEach { uri ->
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(dataIndex) ?: continue
                        File(path).parent?.let { folders.add(it) }
                    }
                }
            } catch (e: Exception) {}
        }

        folders.addAll(selectedFolders)
        availableFolders.clear()
        availableFolders.addAll(folders)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_folders)) },
                modifier = Modifier.statusBarsPadding(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!hasAllFilesAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Full Phone Storage Access",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Required for automatic folder backup and auto-delete",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    context.startActivity(intent)
                                }
                            }
                        ) {
                            Text("Allow")
                        }
                    }
                }
            }

            workInfoList.find { it.state == androidx.work.WorkInfo.State.RUNNING }?.let { info ->
                val progress = info.progress.getFloat("progress", 0f)
                val fileName = info.progress.getString("fileName") ?: ""
                val current = info.progress.getInt("current", 0)
                val total = info.progress.getInt("total", 0)
                
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (total > 0) stringResource(R.string.backing_up_files, current, total) else stringResource(R.string.preparing),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                    if (fileName.isNotEmpty()) {
                        Text(fileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            HorizontalDivider()
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Select Phone Storage Folders",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { folderPickerLauncher.launch(null) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.add_folder), style = MaterialTheme.typography.labelMedium)
                }
            }
            
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(availableFolders) { folderPath ->
                    val isSelected = selectedFolders.contains(folderPath)
                    val folderName = folderPath.substringAfterLast('/')
                    
                    ListItem(
                        headlineContent = { Text(folderName) },
                        supportingContent = { Text(folderPath, maxLines = 1) },
                        leadingContent = {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        },
                        trailingContent = {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    viewModel.toggleBackupFolder(folderPath)
                                    if (!isSelected && !isAutoBackupEnabled) {
                                        viewModel.setAutoBackupEnabled(true)
                                    }
                                }
                            )
                        },
                        modifier = Modifier.clickable {
                            viewModel.toggleBackupFolder(folderPath)
                            if (!isSelected && !isAutoBackupEnabled) {
                                viewModel.setAutoBackupEnabled(true)
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
            
            if (selectedFolders.isNotEmpty()) {
                Button(
                    onClick = {
                        if (!isAutoBackupEnabled) {
                            viewModel.setAutoBackupEnabled(true)
                        }
                        viewModel.triggerManualBackup()
                        Toast.makeText(context, backupStartedMsg, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(stringResource(R.string.backup_now))
                }
            }
        }
    }
}
