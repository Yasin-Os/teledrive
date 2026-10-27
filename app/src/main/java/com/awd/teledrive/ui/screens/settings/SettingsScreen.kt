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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import com.awd.teledrive.data.repository.SettingsRepository
import com.awd.teledrive.data.repository.UpdateState
import com.awd.teledrive.ui.screens.home.HomeViewModel
import com.awd.teledrive.ui.screens.security.SecurityViewModel
import com.awd.teledrive.ui.theme.ThemeViewModel
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

data class SettingsUiState(
    val isDarkMode: Boolean,
    val themeColor: Color,
    val isSecurityEnabled: Boolean,
    val isAppPinSet: Boolean,
    val isBiometricEnabled: Boolean,
    val totalStorageUsed: Long,
    val internalCacheSize: Long,
    val cacheSizeLimit: Long,
    val cacheAgeLimit: Int,
    val isAutoBackupEnabled: Boolean,
    val lastBackupTime: Long,
    val currentLanguage: String,
    val downloadUri: String?,
    val versionName: String,
    val versionCode: Long,
    val updateState: UpdateState
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToBackupFolders: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToCacheDetails: () -> Unit,
    onNavigateToCloudAnalysis: () -> Unit,
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
    val currentLanguage by viewModel.currentLanguage.collectAsState()
    val downloadUri by viewModel.downloadUri.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val isThumbnailAutoDownloadEnabled by viewModel.isThumbnailAutoDownloadEnabled.collectAsState()

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

    val uiState = SettingsUiState(
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
        currentLanguage = currentLanguage,
        downloadUri = downloadUri,
        versionName = viewModel.versionName,
        versionCode = viewModel.versionCode,
        updateState = updateState
    )

    SettingsContent(
        uiState = uiState,
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
        onNavigateToCacheDetails = onNavigateToCacheDetails,
        onNavigateToCloudAnalysis = onNavigateToCloudAnalysis,
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
    uiState: SettingsUiState,
    isThumbnailAutoDownloadEnabled: Boolean,
    isBackupWifiOnly: Boolean,
    deleteAfterBackup: Boolean,
    onSetDeleteAfterBackup: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onNavigateToBackupFolders: () -> Unit,
    onNavigateToCacheDetails: () -> Unit,
    onNavigateToCloudAnalysis: () -> Unit,
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
    var showThemeRestartConfirm by remember { mutableStateOf(false) }
    var showThemeColorDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val infiniteTransition = rememberInfiniteTransition(label = "SettingsGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
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

    if (showCreateAppPinDialog) {
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showCreateAppPinDialog = false },
            title = { Text("Set App PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newPin, onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(4); err = null },
                        label = { Text("4-Digit PIN") }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPin, onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(4); err = null },
                        label = { Text("Confirm PIN") }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = err != null, supportingText = err?.let { { Text(it) } },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newPin.length != 4) err = "Enter 4 digits"
                    else if (newPin != confirmPin) err = "PIN mismatch"
                    else {
                        onCreateAppPin(newPin)
                        showCreateAppPinDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateAppPinDialog = false }) { Text(stringResource(R.string.cancel)) }
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
            title = { Text("Change App PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (uiState.isAppPinSet) {
                        OutlinedTextField(
                            value = oldPin, onValueChange = { oldPin = it.filter { c -> c.isDigit() }.take(4); err = null },
                            label = { Text("Current PIN") }, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedTextField(
                        value = newPin, onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(4); err = null },
                        label = { Text("New 4-Digit PIN") }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPin, onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(4); err = null },
                        label = { Text("Confirm New PIN") }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = err != null, supportingText = err?.let { { Text(it) } },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newPin.length != 4) err = "Enter 4 digits"
                    else if (newPin != confirmPin) err = "PIN mismatch"
                    else if (onChangeAppPin(oldPin, newPin)) {
                        showChangeAppPinDialog = false
                        Toast.makeText(context, "PIN Updated", Toast.LENGTH_SHORT).show()
                    } else err = "Wrong current PIN"
                }) { Text("Update") }
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

    if (showThemeRestartConfirm) {
        AlertDialog(
            onDismissRequest = { showThemeRestartConfirm = false },
            title = { Text(stringResource(R.string.theme_changed_title)) },
            text = { Text(stringResource(R.string.theme_changed_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showThemeRestartConfirm = false
                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    (context as? android.app.Activity)?.finish()
                }) { Text(stringResource(R.string.restart_app)) }
            },
            dismissButton = {
                TextButton(onClick = { showThemeRestartConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showCacheSizeDialog) {
        AlertDialog(
            onDismissRequest = { showCacheSizeDialog = false },
            title = { Text(stringResource(R.string.cache_size_limit)) },
            text = {
                Column {
                    SettingsRepository.CACHE_SIZE_OPTIONS.forEach { option ->
                        ListItem(
                            headlineContent = { Text(if (option == 0L) stringResource(R.string.no_limit) else formatSize(option)) },
                            modifier = Modifier.clickable { onSetCacheSizeLimit(option); showCacheSizeDialog = false },
                            trailingContent = { if (uiState.cacheSizeLimit == option) Icon(Icons.Default.Check, null) }
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
                Column {
                    SettingsRepository.CACHE_AGE_OPTIONS.forEach { option ->
                        ListItem(
                            headlineContent = { Text(formatCacheAge(option, context)) },
                            modifier = Modifier.clickable { onSetCacheAgeLimit(option); showCacheAgeDialog = false },
                            trailingContent = { if (uiState.cacheAgeLimit == option) Icon(Icons.Default.Check, null) }
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
                                    showThemeRestartConfirm = true
                                },
                                color = color, shape = CircleShape, modifier = Modifier.size(48.dp),
                                border = if (uiState.themeColor == color) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null
                            ) {
                                if (uiState.themeColor == color) {
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 1. Backup Bar Section (Emerald Accent)
            SettingsSectionBar(title = "Backup", accentColor = Color(0xFF10B981), glowAlpha = glowAlpha) {
                ModernToggleBarItem(
                    icon = Icons.Default.CloudUpload,
                    title = "Auto Backup",
                    accentColor = Color(0xFF10B981),
                    checked = uiState.isAutoBackupEnabled,
                    onCheckedChange = onSetAutoBackupEnabled
                )
                ModernToggleBarItem(
                    icon = Icons.Default.Delete,
                    title = "Auto-Delete Phone Copy",
                    accentColor = Color(0xFF10B981),
                    checked = deleteAfterBackup,
                    onCheckedChange = onSetDeleteAfterBackup
                )
                ModernToggleBarItem(
                    icon = Icons.Default.Wifi,
                    title = "Wi-Fi Only",
                    accentColor = Color(0xFF10B981),
                    checked = isBackupWifiOnly,
                    enabled = uiState.isAutoBackupEnabled,
                    onCheckedChange = onSetBackupWifiOnly
                )
                ModernActionBarItem(
                    icon = Icons.Default.Folder,
                    title = "Target Folders",
                    accentColor = Color(0xFF10B981),
                    badgeText = "Select",
                    onClick = onNavigateToBackupFolders
                )
                ModernActionBarItem(
                    icon = Icons.Default.CloudUpload,
                    title = "Sync Now",
                    accentColor = Color(0xFF10B981),
                    onClick = onTriggerManualBackup
                )
            }

            // 2. Storage & Cache Bar Section (Cyan Accent)
            SettingsSectionBar(title = "Storage", accentColor = Color(0xFF0EA5E9), glowAlpha = glowAlpha) {
                ModernActionBarItem(
                    icon = Icons.Default.Cloud,
                    title = "Cloud Usage",
                    accentColor = Color(0xFF0EA5E9),
                    badgeText = formatSize(uiState.totalStorageUsed),
                    onClick = onNavigateToCloudAnalysis
                )
                ModernActionBarItem(
                    icon = Icons.Default.Storage,
                    title = "Local Cache",
                    accentColor = Color(0xFF0EA5E9),
                    badgeText = formatSize(uiState.internalCacheSize),
                    onClick = onNavigateToCacheDetails
                )
                ModernActionBarItem(
                    icon = Icons.Default.Storage,
                    title = "Cache Limit",
                    accentColor = Color(0xFF0EA5E9),
                    badgeText = if (uiState.cacheSizeLimit == 0L) "Unlimited" else formatSize(uiState.cacheSizeLimit),
                    onClick = { showCacheSizeDialog = true }
                )
                ModernActionBarItem(
                    icon = Icons.Default.History,
                    title = "Cache Duration",
                    accentColor = Color(0xFF0EA5E9),
                    badgeText = formatCacheAge(uiState.cacheAgeLimit, context),
                    onClick = { showCacheAgeDialog = true }
                )
            }

            // 3. Security Bar Section (Violet Accent)
            SettingsSectionBar(title = "Security", accentColor = Color(0xFF8B5CF6), glowAlpha = glowAlpha) {
                ModernToggleBarItem(
                    icon = Icons.Default.Lock,
                    title = "App PIN Lock",
                    accentColor = Color(0xFF8B5CF6),
                    checked = uiState.isSecurityEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !uiState.isAppPinSet) showCreateAppPinDialog = true
                        else onSetSecurityEnabled(enabled)
                    }
                )
                ModernActionBarItem(
                    icon = Icons.Default.Edit,
                    title = "Change App PIN",
                    accentColor = Color(0xFF8B5CF6),
                    badgeText = if (uiState.isAppPinSet) "4-Digit" else "Not Set",
                    onClick = {
                        if (!uiState.isAppPinSet) showCreateAppPinDialog = true
                        else showChangeAppPinDialog = true
                    }
                )
                ModernToggleBarItem(
                    icon = Icons.Default.Fingerprint,
                    title = "Biometric Unlock",
                    accentColor = Color(0xFF8B5CF6),
                    checked = uiState.isBiometricEnabled,
                    enabled = uiState.isSecurityEnabled,
                    onCheckedChange = onSetBiometricEnabled
                )
            }

            // 4. Appearance & Downloads Bar Section (Amber Accent)
            SettingsSectionBar(title = "Appearance & Files", accentColor = Color(0xFFF59E0B), glowAlpha = glowAlpha) {
                ModernToggleBarItem(
                    icon = Icons.Default.DarkMode,
                    title = "Dark Theme",
                    accentColor = Color(0xFFF59E0B),
                    checked = uiState.isDarkMode,
                    onCheckedChange = { onSetDarkMode(it); showThemeRestartConfirm = true }
                )
                ModernToggleBarItem(
                    icon = Icons.Default.PhotoLibrary,
                    title = "Auto-Load Thumbnails",
                    accentColor = Color(0xFFF59E0B),
                    checked = isThumbnailAutoDownloadEnabled,
                    onCheckedChange = onSetThumbnailAutoDownload
                )
                ModernActionBarItem(
                    icon = Icons.Default.Palette,
                    title = "Accent Color",
                    accentColor = uiState.themeColor,
                    onClick = { showThemeColorDialog = true }
                )
                val downloadPath = if (uiState.downloadUri.isNullOrEmpty()) "Downloads"
                else Uri.parse(uiState.downloadUri).path?.substringAfterLast(':') ?: "Custom"
                ModernActionBarItem(
                    icon = Icons.Default.FolderOpen,
                    title = "Save Location",
                    accentColor = Color(0xFF3B82F6),
                    badgeText = downloadPath,
                    onClick = { folderLauncher.launch(null) }
                )
            }

            // 5. System Update & Account Bar Section (Rose/Primary Accent)
            SettingsSectionBar(title = "System", accentColor = Color(0xFFEC4899), glowAlpha = glowAlpha) {
                ModernActionBarItem(
                    icon = Icons.Default.SystemUpdate,
                    title = "App Update",
                    accentColor = Color(0xFFEC4899),
                    badgeText = if (uiState.updateState is UpdateState.Checking) "Checking..." else "v${uiState.versionName}",
                    onClick = onCheckForUpdates
                )
                ModernActionBarItem(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    title = "Log Out",
                    accentColor = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { showLogoutConfirm = true }
                )
            }

            // Copyright Footer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "© 2026 MyCloud • All Rights Reserved",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Developed by Yasin Adnan",
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
                    .ClickableOrStaticBg(accentColor)
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
private fun Modifier.ClickableOrStaticBg(color: Color): Modifier {
    return this.then(
        Modifier.drawWithCacheOrBg(color)
    )
}

@Composable
private fun Modifier.drawWithCacheOrBg(color: Color): Modifier {
    return androidx.compose.foundation.background(color, CircleShape)
}

@Composable
private fun ModernActionBarItem(
    icon: ImageVector,
    title: String,
    accentColor: Color,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    badgeText: String? = null,
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
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
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
fun SettingsCategory(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun SettingsRow(icon: ImageVector, title: String, subtitle: String? = null) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    )
}

@Composable
fun SettingsClickableRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    showArrow: Boolean = true,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = if (showArrow) {
            { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) }
        } else null,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { onCheckedChange(!checked) }
    )
}

private fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
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
