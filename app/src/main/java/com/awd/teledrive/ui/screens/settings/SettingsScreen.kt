package com.awd.teledrive.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.awd.teledrive.R
import com.awd.teledrive.core.utils.FileUtils
import com.awd.teledrive.data.model.GitHubRelease
import com.awd.teledrive.data.repository.SettingsRepository
import com.awd.teledrive.data.repository.UpdateState
import com.awd.teledrive.domain.model.CloudBackup
import com.awd.teledrive.ui.screens.home.HomeViewModel
import com.awd.teledrive.ui.screens.security.SecurityViewModel
import com.awd.teledrive.ui.theme.AccentCyanLight
import com.awd.teledrive.ui.theme.AccentPink
import com.awd.teledrive.ui.theme.AmberAccent
import com.awd.teledrive.ui.theme.BlueAccent
import com.awd.teledrive.ui.theme.EmeraldAccent
import com.awd.teledrive.ui.theme.SkyAccent
import com.awd.teledrive.ui.theme.ThemeViewModel
import com.awd.teledrive.ui.theme.VioletAccent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToBackupFolders: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToCacheDetails: () -> Unit,
    onNavigateToCloudAnalysis: () -> Unit,
    onNavigateToStorageAnalyzer: () -> Unit,
    onNavigateToDuplicateFinder: () -> Unit,
    onNavigateToBackupHealth: () -> Unit,
    onNavigateToAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    themeViewModel: ThemeViewModel = hiltViewModel(),
    securityViewModel: SecurityViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
    backupViewModel: BackupViewModel = hiltViewModel()
) {
    val isDarkMode by themeViewModel.isDarkMode.collectAsState()
    val themeColor by themeViewModel.themeColor.collectAsState()
    val isSecurityEnabled by securityViewModel.isSecurityEnabled.collectAsState()
    val isAppPinSet by securityViewModel.isPasswordSet.collectAsState()
    val isBiometricEnabled by securityViewModel.isBiometricEnabled.collectAsState()
    val totalStorageUsed by homeViewModel.totalStorageUsed.collectAsState()
    val internalCacheSize by viewModel.internalCacheSize.collectAsState()
    val cacheSizeLimit by viewModel.cacheSizeLimit.collectAsState()
    val cacheAgeLimit by viewModel.cacheAgeLimit.collectAsState()
    val isAutoBackupEnabled by backupViewModel.isAutoBackupEnabled.collectAsState()
    val isBackupWifiOnly by backupViewModel.isBackupWifiOnly.collectAsState()
    val lastBackupTime by backupViewModel.lastBackupTime.collectAsState()
    val downloadUri by viewModel.downloadUri.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val availableRelease by viewModel.availableRelease.collectAsState()
    val lastCheckStatus by viewModel.lastCheckStatus.collectAsState()
    val isThumbnailAutoDownloadEnabled by viewModel.isThumbnailAutoDownloadEnabled.collectAsState()
    val currentLanguage by viewModel.currentLanguage.collectAsState()
    val cloudBackups by viewModel.cloudBackups.collectAsState()

    val context = LocalContext.current
    val customPrefs = remember { context.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE) }
    var deleteAfterBackup by remember { mutableStateOf(customPrefs.getBoolean("delete_after_backup", false)) }

    LaunchedEffect(Unit) {
        backupViewModel.feedback.collect { feedback ->
            val msg = when (feedback) {
                BackupFeedback.NoFolders -> context.getString(R.string.no_backup_folders)
                BackupFeedback.Starting -> context.getString(R.string.backup_starting)
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    SettingsContent(
        isDarkMode = isDarkMode ?: isSystemInDarkTheme(),
        themeColor = themeColor,
        isSecurityEnabled = isSecurityEnabled,
        isAppPinSet = isAppPinSet,
        isBiometricEnabled = isBiometricEnabled,
        totalStorageUsed = totalStorageUsed,
        internalCacheSize = internalCacheSize,
        cacheSizeLimit = cacheSizeLimit,
        cacheAgeLimit = cacheAgeLimit,
        isAutoBackupEnabled = isAutoBackupEnabled,
        lastBackupTime = lastBackupTime,
        downloadUri = downloadUri,
        versionName = viewModel.versionName,
        updateState = updateState,
        availableRelease = availableRelease,
        lastCheckStatus = lastCheckStatus,
        isThumbnailAutoDownloadEnabled = isThumbnailAutoDownloadEnabled,
        isBackupWifiOnly = isBackupWifiOnly,
        deleteAfterBackup = deleteAfterBackup,
        onSetDeleteAfterBackup = { enabled ->
            deleteAfterBackup = enabled
            customPrefs.edit().putBoolean("delete_after_backup", enabled).apply()
        },
        onLogout = {
            viewModel.logout()
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            (context as? android.app.Activity)?.finish()
        },
        onNavigateToBackupFolders = onNavigateToBackupFolders,
        onNavigateToLogs = onNavigateToLogs,
        onNavigateToCacheDetails = onNavigateToCacheDetails,
        onNavigateToCloudAnalysis = onNavigateToCloudAnalysis,
        onNavigateToStorageAnalyzer = onNavigateToStorageAnalyzer,
        onNavigateToDuplicateFinder = onNavigateToDuplicateFinder,
        onNavigateToBackupHealth = onNavigateToBackupHealth,
        onNavigateToAbout = onNavigateToAbout,
        currentLanguage = currentLanguage,
        cloudBackups = cloudBackups,
        onSetLanguage = viewModel::setLanguage,
        onLoadCloudBackups = viewModel::loadCloudBackups,
        onRestoreCloudBackup = viewModel::restoreCloudBackup,
        onTriggerCloudBackup = viewModel::triggerCloudMetadataBackup,
        onExportMetadata = viewModel::exportMetadata,
        onImportMetadata = viewModel::importMetadata,
        onSetDarkMode = { themeViewModel.setDarkMode(it) },
        onSetThemeColor = { themeViewModel.setThemeColor(it) },
        onSetSecurityEnabled = { securityViewModel.setSecurityEnabled(it) },
        onCreateAppPin = { pin -> securityViewModel.setPassword(pin) },
        onChangeAppPin = { oldPin, newPin -> securityViewModel.changeAppLockPin(oldPin, newPin) },
        onSetBiometricEnabled = { securityViewModel.setBiometricEnabled(it) },
        onSetAutoBackupEnabled = { backupViewModel.setAutoBackupEnabled(it) },
        onSetBackupWifiOnly = { backupViewModel.setBackupWifiOnly(it) },
        onTriggerManualBackup = { backupViewModel.triggerManualBackup() },
        onSetDownloadUri = viewModel::setDownloadUri,
        onSetCacheSizeLimit = viewModel::setCacheSizeLimit,
        onSetCacheAgeLimit = viewModel::setCacheAgeLimit,
        onSetThumbnailAutoDownload = viewModel::setThumbnailAutoDownloadEnabled,
        onCheckForUpdates = viewModel::checkForUpdates
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    isDarkMode: Boolean,
    themeColor: Color,
    isSecurityEnabled: Boolean,
    isAppPinSet: Boolean,
    isBiometricEnabled: Boolean,
    totalStorageUsed: Long,
    internalCacheSize: Long,
    cacheSizeLimit: Long,
    cacheAgeLimit: Int,
    isAutoBackupEnabled: Boolean,
    lastBackupTime: Long,
    downloadUri: String?,
    versionName: String,
    updateState: UpdateState,
    availableRelease: GitHubRelease?,
    lastCheckStatus: String?,
    isThumbnailAutoDownloadEnabled: Boolean,
    isBackupWifiOnly: Boolean,
    deleteAfterBackup: Boolean,
    onSetDeleteAfterBackup: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onNavigateToBackupFolders: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToCacheDetails: () -> Unit,
    onNavigateToCloudAnalysis: () -> Unit,
    onNavigateToStorageAnalyzer: () -> Unit,
    onNavigateToDuplicateFinder: () -> Unit,
    onNavigateToBackupHealth: () -> Unit,
    onNavigateToAbout: () -> Unit,
    currentLanguage: String,
    cloudBackups: List<CloudBackup>,
    onSetLanguage: (String) -> Unit,
    onLoadCloudBackups: () -> Unit,
    onRestoreCloudBackup: (Long, (Boolean) -> Unit) -> Unit,
    onTriggerCloudBackup: () -> Unit,
    onExportMetadata: () -> String,
    onImportMetadata: (String) -> Boolean,
    onSetDarkMode: (Boolean) -> Unit,
    onSetThemeColor: (Color) -> Unit,
    onSetSecurityEnabled: (Boolean) -> Unit,
    onCreateAppPin: (String) -> Unit,
    onChangeAppPin: (String, String) -> Boolean,
    onSetBiometricEnabled: (Boolean) -> Unit,
    onSetAutoBackupEnabled: (Boolean) -> Unit,
    onSetBackupWifiOnly: (Boolean) -> Unit,
    onTriggerManualBackup: () -> Unit,
    onSetDownloadUri: (String?) -> Unit,
    onSetCacheSizeLimit: (Long) -> Unit,
    onSetCacheAgeLimit: (Int) -> Unit,
    onSetThumbnailAutoDownload: (Boolean) -> Unit,
    onCheckForUpdates: () -> Unit
) {
    var showCreateAppPinDialog by remember { mutableStateOf(false) }
    var showChangeAppPinDialog by remember { mutableStateOf(false) }
    var showCacheSizeDialog by remember { mutableStateOf(false) }
    var showCacheAgeDialog by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showThemeColorDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showLanguageChangedDialog by remember { mutableStateOf(false) }
    var showCloudBackupsDialog by remember { mutableStateOf(false) }
    var pendingRestoreBackup by remember { mutableStateOf<CloudBackup?>(null) }

    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val infiniteTransition = rememberInfiniteTransition(label = "SettingsGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    val folderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)
            onSetDownloadUri(it.toString())
        }
    }

    // P1-11: export the local manifest (folders + file mappings) as a JSON file.
    val exportMetadataLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    out.write(onExportMetadata().toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Metadata exported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // P1-11: import a previously exported manifest JSON file.
    val importMetadataLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val text = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> reader.readText() } ?: ""
                val ok = onImportMetadata(text)
                Toast.makeText(
                    context,
                    if (ok) "Metadata imported" else "Import failed: invalid file",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Import failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showCreateAppPinDialog) {
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showCreateAppPinDialog = false },
            title = { Text(stringResource(R.string.set_app_pin)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newPin, onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(4); err = null },
                        label = { Text(stringResource(R.string.pin_4_digit)) }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPin, onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(4); err = null },
                        label = { Text(stringResource(R.string.confirm_pin)) }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = err != null, supportingText = err?.let { { Text(it) } },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newPin.length != 4) err = context.getString(R.string.err_pin_4_digits)
                    else if (newPin != confirmPin) err = context.getString(R.string.err_pin_mismatch)
                    else {
                        onCreateAppPin(newPin)
                        showCreateAppPinDialog = false
                        Toast.makeText(context, context.getString(R.string.pin_lock_enabled), Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateAppPinDialog = false; onSetSecurityEnabled(false) }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showChangeAppPinDialog) {
        var oldPin by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showChangeAppPinDialog = false },
            title = { Text(stringResource(R.string.change_app_pin)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isAppPinSet) {
                        OutlinedTextField(
                            value = oldPin, onValueChange = { oldPin = it.filter { c -> c.isDigit() }.take(4); err = null },
                            label = { Text(stringResource(R.string.current_pin)) }, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedTextField(
                        value = newPin, onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(4); err = null },
                        label = { Text(stringResource(R.string.new_pin_4_digit)) }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPin, onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(4); err = null },
                        label = { Text(stringResource(R.string.confirm_new_pin)) }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = err != null, supportingText = err?.let { { Text(it) } },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newPin.length != 4) err = context.getString(R.string.err_new_pin_4_digits)
                    else if (newPin != confirmPin) err = context.getString(R.string.err_new_pin_mismatch)
                    else if (onChangeAppPin(oldPin, newPin)) {
                        showChangeAppPinDialog = false
                        Toast.makeText(context, context.getString(R.string.pin_updated), Toast.LENGTH_SHORT).show()
                    } else err = context.getString(R.string.err_current_pin_incorrect)
                }) { Text(stringResource(R.string.update)) }
            },
            dismissButton = {
                TextButton(onClick = { showChangeAppPinDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.logout_confirm_title)) },
            text = { Text(stringResource(R.string.logout_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { showLogoutConfirm = false; onLogout() }) {
                    Text(stringResource(R.string.logout), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showCacheSizeDialog) {
        AlertDialog(
            onDismissRequest = { showCacheSizeDialog = false },
            title = { Text(stringResource(R.string.cache_size_limit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsRepository.CACHE_SIZE_OPTIONS.forEach { option ->
                        ModernSelectionItem(
                            title = if (option == 0L) stringResource(R.string.no_limit) else FileUtils.formatSize(option),
                            isSelected = cacheSizeLimit == option,
                            onClick = { onSetCacheSizeLimit(option); showCacheSizeDialog = false }
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showCacheAgeDialog) {
        AlertDialog(
            onDismissRequest = { showCacheAgeDialog = false },
            title = { Text(stringResource(R.string.media_age_limit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsRepository.CACHE_AGE_OPTIONS.forEach { option ->
                        ModernSelectionItem(
                            title = formatCacheAge(option, context),
                            isSelected = cacheAgeLimit == option,
                            onClick = { onSetCacheAgeLimit(option); showCacheAgeDialog = false }
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showThemeColorDialog) {
        AlertDialog(
            onDismissRequest = { showThemeColorDialog = false },
            title = { Text(stringResource(R.string.theme_color)) },
            text = {
                Box(modifier = Modifier.height(260.dp)) {
                    val colors = listOf(
                        Color(0xFF24A1DE), Color(0xFF673AB7), Color(0xFF3F51B5),
                        Color(0xFF2196F3), Color(0xFF00BCD4), Color(0xFF009688),
                        Color(0xFF4CAF50), Color(0xFF8BC34A), Color(0xFFFFC107),
                        Color(0xFFFF9800), Color(0xFFFF5722), Color(0xFFE91E63),
                        Color(0xFF9C27B0), Color(0xFF607D8B)
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(colors) { color ->
                            Surface(
                                onClick = {
                                    onSetThemeColor(color)
                                    showThemeColorDialog = false
                                },
                                color = color, shape = CircleShape, modifier = Modifier.size(48.dp),
                                border = if (themeColor == color) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null
                            ) {
                                if (themeColor == color) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(22.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeColorDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // P1-11: language picker. Only locales with a translated strings.xml exist
    // (English = default values/, Indonesian = values-in/), so only those two
    // are offered. setLanguage() persists the choice and applies it via
    // AppCompatDelegate; a restart is offered so every screen picks it up.
    if (showLanguageDialog) {
        val languageOptions = listOf("en" to "English", "id" to "Bahasa Indonesia")
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    languageOptions.forEach { (code, label) ->
                        ModernSelectionItem(
                            title = label,
                            isSelected = currentLanguage == code,
                            onClick = {
                                onSetLanguage(code)
                                showLanguageDialog = false
                                showLanguageChangedDialog = true
                            }
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showLanguageChangedDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageChangedDialog = false },
            title = { Text(stringResource(R.string.language_changed_title)) },
            text = { Text(stringResource(R.string.language_changed_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLanguageChangedDialog = false
                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    (context as? android.app.Activity)?.finish()
                }) { Text(stringResource(R.string.restart_app)) }
            },
            dismissButton = {
                TextButton(onClick = { showLanguageChangedDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // P1-11: list of manifest backups stored in Saved Messages; tapping one
    // asks for confirmation, then restores it (replaces local folders).
    if (showCloudBackupsDialog) {
        AlertDialog(
            onDismissRequest = { showCloudBackupsDialog = false },
            title = { Text("Restore from Cloud") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cloudBackups.isEmpty()) {
                        Text(
                            text = "No cloud backups found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val format = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                        cloudBackups.forEach { backup ->
                            ModernSelectionItem(
                                title = "${format.format(Date(backup.date))} • ${backup.folderCount} folders",
                                isSelected = false,
                                onClick = { pendingRestoreBackup = backup }
                            )
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (pendingRestoreBackup != null) {
        val backup = pendingRestoreBackup!!
        val format = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        AlertDialog(
            onDismissRequest = { pendingRestoreBackup = null },
            title = { Text("Restore backup?") },
            text = { Text("Restore the backup from ${format.format(Date(backup.date))}? Your current folders will be replaced.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestoreBackup = null
                    showCloudBackupsDialog = false
                    onRestoreCloudBackup(backup.messageId) { ok ->
                        Toast.makeText(
                            context,
                            if (ok) "Backup restored" else "Restore failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreBackup = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp)
                    )
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 1. Backup Bar Section (Emerald Accent)
            SettingsSectionBar(title = stringResource(R.string.settings_section_backup), accentColor = EmeraldAccent, glowAlpha = glowAlpha) {
                ModernToggleBarItem(
                    icon = Icons.Default.CloudUpload, title = stringResource(R.string.auto_backup),
                    accentColor = EmeraldAccent, checked = isAutoBackupEnabled,
                    onCheckedChange = onSetAutoBackupEnabled
                )
                ModernToggleBarItem(
                    icon = Icons.Default.Delete, title = stringResource(R.string.auto_delete_from_phone),
                    accentColor = EmeraldAccent, checked = deleteAfterBackup,
                    onCheckedChange = onSetDeleteAfterBackup
                )
                ModernToggleBarItem(
                    icon = Icons.Default.Wifi, title = stringResource(R.string.wifi_only),
                    accentColor = EmeraldAccent, checked = isBackupWifiOnly,
                    enabled = isAutoBackupEnabled, onCheckedChange = onSetBackupWifiOnly
                )
                ModernActionBarItem(
                    icon = Icons.Default.Folder, title = stringResource(R.string.target_folders),
                    accentColor = EmeraldAccent, badgeText = stringResource(R.string.select),
                    onClick = onNavigateToBackupFolders
                )
                if (lastBackupTime > 0) {
                    val date = Date(lastBackupTime)
                    val format = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
                    ModernActionBarItem(
                        icon = Icons.Default.History, title = stringResource(R.string.last_sync),
                        accentColor = EmeraldAccent, badgeText = format.format(date),
                        showArrow = false, onClick = {}
                    )
                }
                ModernActionBarItem(
                    icon = Icons.Default.CloudUpload, title = stringResource(R.string.sync_now),
                    accentColor = EmeraldAccent, onClick = onTriggerManualBackup
                )
                ModernActionBarItem(
                    icon = Icons.Default.MonitorHeart, title = stringResource(R.string.backup_health),
                    accentColor = Color(0xFF10B981), onClick = onNavigateToBackupHealth
                )
            }

            // 2. Storage & Cache Bar Section (Cyan Accent)
            SettingsSectionBar(title = stringResource(R.string.storage), accentColor = SkyAccent, glowAlpha = glowAlpha) {
                ModernActionBarItem(
                    icon = Icons.Default.Cloud, title = stringResource(R.string.cloud_usage),
                    accentColor = SkyAccent, badgeText = FileUtils.formatSize(totalStorageUsed),
                    onClick = onNavigateToCloudAnalysis
                )
                ModernActionBarItem(
                    icon = Icons.Default.Analytics, title = stringResource(R.string.storage_analyzer),
                    accentColor = Color(0xFF0EA5E9), onClick = onNavigateToStorageAnalyzer
                )
                ModernActionBarItem(
                    icon = Icons.Default.ContentCopy, title = stringResource(R.string.duplicate_finder),
                    accentColor = Color(0xFF0EA5E9), onClick = onNavigateToDuplicateFinder
                )
                ModernActionBarItem(
                    icon = Icons.Default.Storage, title = stringResource(R.string.local_cache),
                    accentColor = SkyAccent, badgeText = FileUtils.formatSize(internalCacheSize),
                    onClick = onNavigateToCacheDetails
                )
                ModernActionBarItem(
                    icon = Icons.Default.Storage, title = stringResource(R.string.cache_limit),
                    accentColor = SkyAccent,
                    badgeText = if (cacheSizeLimit == 0L) stringResource(R.string.unlimited) else FileUtils.formatSize(cacheSizeLimit),
                    onClick = { showCacheSizeDialog = true }
                )
                ModernActionBarItem(
                    icon = Icons.Default.History, title = stringResource(R.string.cache_duration),
                    accentColor = SkyAccent, badgeText = formatCacheAge(cacheAgeLimit, context),
                    onClick = { showCacheAgeDialog = true }
                )
            }

            // 3. Security Bar Section (Violet Accent)
            SettingsSectionBar(title = stringResource(R.string.security), accentColor = VioletAccent, glowAlpha = glowAlpha) {
                ModernToggleBarItem(
                    icon = Icons.Default.Lock, title = stringResource(R.string.app_pin_lock),
                    accentColor = VioletAccent, checked = isSecurityEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !isAppPinSet) showCreateAppPinDialog = true
                        else onSetSecurityEnabled(enabled)
                    }
                )
                ModernActionBarItem(
                    icon = Icons.Default.Edit, title = stringResource(R.string.change_app_pin),
                    accentColor = VioletAccent, badgeText = if (isAppPinSet) stringResource(R.string.pin_4_digit_short) else stringResource(R.string.not_set),
                    onClick = {
                        if (!isAppPinSet) showCreateAppPinDialog = true
                        else showChangeAppPinDialog = true
                    }
                )
                ModernToggleBarItem(
                    icon = Icons.Default.Fingerprint, title = stringResource(R.string.biometric_unlock),
                    accentColor = VioletAccent, checked = isBiometricEnabled,
                    enabled = isSecurityEnabled, onCheckedChange = onSetBiometricEnabled
                )
            }

            // 4. Appearance & Downloads Bar Section (Amber Accent)
            SettingsSectionBar(title = stringResource(R.string.appearance_files), accentColor = AmberAccent, glowAlpha = glowAlpha) {
                ModernToggleBarItem(
                    icon = Icons.Default.DarkMode, title = stringResource(R.string.dark_theme),
                    accentColor = AmberAccent, checked = isDarkMode,
                    onCheckedChange = onSetDarkMode
                )
                ModernToggleBarItem(
                    icon = Icons.Default.PhotoLibrary, title = stringResource(R.string.auto_load_thumbnails),
                    accentColor = AmberAccent, checked = isThumbnailAutoDownloadEnabled,
                    onCheckedChange = onSetThumbnailAutoDownload
                )
                ModernActionBarItem(
                    icon = Icons.Default.Palette, title = stringResource(R.string.accent_color),
                    accentColor = themeColor, onClick = { showThemeColorDialog = true }
                )
                val downloadPath = if (downloadUri.isNullOrEmpty()) stringResource(R.string.downloads)
                else Uri.parse(downloadUri).path?.substringAfterLast(':') ?: stringResource(R.string.custom)
                ModernActionBarItem(
                    icon = Icons.Default.FolderOpen, title = stringResource(R.string.save_location),
                    accentColor = BlueAccent, badgeText = downloadPath,
                    onClick = { folderLauncher.launch(null) }
                )
            }

            // 5. System Update & Account Bar Section (Dynamic Update Highlight)
            val hasUpdateOrReady = availableRelease != null ||
                    updateState is UpdateState.Downloading ||
                    updateState is UpdateState.ReadyToInstall

            val updateAccent = when (updateState) {
                is UpdateState.ReadyToInstall -> EmeraldAccent
                is UpdateState.Downloading -> AccentCyanLight
                else -> if (availableRelease != null) EmeraldAccent else AccentPink
            }

            val updateTitle = when (updateState) {
                is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready, updateState.release.tag_name)
                is UpdateState.Downloading -> stringResource(R.string.updating_app)
                else -> if (availableRelease != null) stringResource(R.string.new_update, availableRelease.tag_name) else stringResource(R.string.app_update)
            }

            val updateBadge = when (updateState) {
                is UpdateState.Checking -> stringResource(R.string.checking)
                is UpdateState.Downloading -> "${(updateState.progress * 100).toInt()}%"
                is UpdateState.ReadyToInstall -> stringResource(R.string.install_now)
                else -> if (availableRelease != null) stringResource(R.string.update_now) else "v${versionName.removePrefix("v")}"
            }

            SettingsSectionBar(
                title = stringResource(R.string.system),
                accentColor = if (hasUpdateOrReady) updateAccent else AccentPink,
                glowAlpha = glowAlpha
            ) {
                ModernActionBarItem(
                    icon = Icons.Default.SystemUpdate,
                    title = updateTitle,
                    accentColor = updateAccent,
                    titleColor = if (hasUpdateOrReady) updateAccent else MaterialTheme.colorScheme.onSurface,
                    badgeText = updateBadge,
                    onClick = onCheckForUpdates
                )
                if (lastCheckStatus != null) {
                    Text(
                        text = lastCheckStatus,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 68.dp, bottom = 6.dp)
                    )
                }
                // P1-11: previously dead navigation targets — the routes and the
                // onNavigateTo* callbacks already existed, but no row led to them.
                ModernActionBarItem(
                    icon = Icons.Default.Language, title = stringResource(R.string.language),
                    accentColor = Color(0xFFEC4899),
                    badgeText = when (currentLanguage) {
                        "id" -> "Bahasa Indonesia"
                        "en" -> "English"
                        else -> currentLanguage
                    },
                    onClick = { showLanguageDialog = true }
                )
                ModernActionBarItem(
                    icon = Icons.Default.Article, title = "View Logs",
                    accentColor = Color(0xFFEC4899),
                    onClick = onNavigateToLogs
                )
                ModernActionBarItem(
                    icon = Icons.Default.Info, title = "About",
                    accentColor = Color(0xFFEC4899),
                    onClick = onNavigateToAbout
                )
                ModernActionBarItem(
                    icon = Icons.AutoMirrored.Filled.Logout, title = stringResource(R.string.log_out),
                    accentColor = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { showLogoutConfirm = true }
                )
            }

            // 6. Data & Backup Bar Section (Teal Accent) — P1-11: wires the
            // manifest backup / restore / export / import functions that already
            // existed in SettingsViewModel but had no UI at all.
            SettingsSectionBar(title = "Data & Backup", accentColor = Color(0xFF14B8A6), glowAlpha = glowAlpha) {
                ModernActionBarItem(
                    icon = Icons.Default.CloudUpload, title = "Back Up to Cloud",
                    accentColor = Color(0xFF14B8A6),
                    badgeText = "Saved Messages",
                    onClick = {
                        onTriggerCloudBackup()
                        Toast.makeText(context, "Cloud backup started", Toast.LENGTH_SHORT).show()
                    }
                )
                ModernActionBarItem(
                    icon = Icons.Default.Restore, title = "Restore from Cloud",
                    accentColor = Color(0xFF14B8A6),
                    onClick = {
                        onLoadCloudBackups()
                        showCloudBackupsDialog = true
                    }
                )
                ModernActionBarItem(
                    icon = Icons.Default.FileDownload, title = "Export Metadata",
                    accentColor = Color(0xFF14B8A6), badgeText = "JSON",
                    onClick = { exportMetadataLauncher.launch("mycloud-metadata.json") }
                )
                ModernActionBarItem(
                    icon = Icons.Default.FileUpload, title = "Import Metadata",
                    accentColor = Color(0xFF14B8A6), badgeText = "JSON",
                    onClick = { importMetadataLauncher.launch(arrayOf("application/json")) }
                )
            }

            // 7. Copyright Footer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.copyright_footer),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.developed_by),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionBar(
    title: String,
    accentColor: Color,
    glowAlpha: Float,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accentColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title.uppercase(Locale.US),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.2.sp
                ),
                color = accentColor
            )
        }

        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
            border = BorderStroke(
                1.dp,
                Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = glowAlpha),
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
                    )
                )
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                content = content
            )
        }
    }
}

@Composable
private fun ModernActionBarItem(
    icon: ImageVector,
    title: String,
    accentColor: Color,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    badgeText: String? = null,
    showArrow: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = accentColor.copy(alpha = 0.15f),
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = titleColor,
            modifier = Modifier.weight(1f)
        )
        if (badgeText != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = accentColor.copy(alpha = 0.14f)
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = accentColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
        }
        if (showArrow) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ModernToggleBarItem(
    icon: ImageVector,
    title: String,
    accentColor: Color,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = accentColor.copy(alpha = if (enabled) 0.15f else 0.06f),
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) accentColor else accentColor.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
fun ModernSelectionItem(title: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                ),
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
            if (isSelected) {
                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}


private fun formatCacheAge(seconds: Int, context: Context): String {
    return when (seconds) {
        0 -> context.getString(R.string.no_limit)
        7 * 24 * 60 * 60 -> "1 " + context.getString(R.string.week)
        30 * 24 * 60 * 60 -> "1 " + context.getString(R.string.month)
        90 * 24 * 60 * 60 -> "3 " + context.getString(R.string.months)
        365 * 24 * 60 * 60 -> "1 " + context.getString(R.string.year)
        else -> context.getString(R.string.seconds_label, seconds)
    }
}
