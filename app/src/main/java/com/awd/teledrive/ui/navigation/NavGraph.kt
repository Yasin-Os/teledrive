package com.awd.teledrive.ui.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.awd.teledrive.R
import com.awd.teledrive.domain.model.DriveItem
import com.awd.teledrive.ui.screens.auth.LoginScreen
import com.awd.teledrive.ui.screens.home.HomeScreen
import com.awd.teledrive.ui.screens.logs.LogsScreen
import com.awd.teledrive.ui.screens.media.MediaScreen
import com.awd.teledrive.ui.screens.preview.PdfViewerScreen
import com.awd.teledrive.ui.screens.preview.PreviewScreen
import com.awd.teledrive.ui.screens.preview.TextViewerScreen
import com.awd.teledrive.ui.screens.preview.VideoPlayerScreen
import com.awd.teledrive.ui.screens.security.MasterPasswordScreen
import com.awd.teledrive.ui.screens.security.SecurityViewModel
import com.awd.teledrive.ui.screens.settings.AboutScreen
import com.awd.teledrive.ui.screens.settings.BackupSettingsScreen
import com.awd.teledrive.ui.screens.settings.CacheDetailsScreen
import com.awd.teledrive.ui.screens.settings.CloudAnalysisScreen
import com.awd.teledrive.ui.screens.settings.SettingsScreen
import com.awd.teledrive.ui.screens.starred.StarredScreen
import com.awd.teledrive.ui.screens.transfers.TransfersScreen
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

sealed class Screen(val route: String, val icon: ImageVector? = null, val labelRes: Int? = null) {
    object Security : Screen("security")
    object Login : Screen("login")
    object Home : Screen("home", Icons.Default.Home, R.string.home)
    object Starred : Screen("starred", Icons.Default.Star, R.string.starred)
    object Media : Screen("media", Icons.Default.PermMedia, R.string.media)
    object Transfers : Screen("transfers", Icons.Default.SwapVert, R.string.transfers)
    object Settings : Screen("settings", Icons.Default.Settings, R.string.settings)
    object About : Screen("about")
    object CacheDetails : Screen("cache_details")
    object CloudAnalysis : Screen("cloud_analysis")
    object Logs : Screen("logs")
    object BackupSettings : Screen("backup_settings")
    object SecureStorage : Screen("secure_storage")
    object Preview : Screen("preview/{chatId}/{fileId}?isMediaOnly={isMediaOnly}&virtualParentId={virtualParentId}") {
        fun createRoute(chatId: Long, fileId: Long, isMediaOnly: Boolean = false, virtualParentId: String = "0") = 
            "preview/$chatId/$fileId?isMediaOnly=$isMediaOnly&virtualParentId=$virtualParentId"
    }
    object VideoPlayer : Screen("video_player/{path}") {
        fun createRoute(path: String) = "video_player/${URLEncoder.encode(path, "UTF-8")}"
    }
    object PdfViewer : Screen("pdf_viewer/{path}") {
        fun createRoute(path: String) = "pdf_viewer/${URLEncoder.encode(path, "UTF-8")}"
    }
    object TextViewer : Screen("text_viewer/{path}") {
        fun createRoute(path: String) = "text_viewer/${URLEncoder.encode(path, "UTF-8")}"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NavGraph(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    
    val securityViewModel: SecurityViewModel = hiltViewModel()
    val loginViewModel: com.awd.teledrive.ui.screens.auth.LoginViewModel = hiltViewModel()
    val settingsViewModel: com.awd.teledrive.ui.screens.settings.SettingsViewModel = hiltViewModel()
    
    val isLocked by securityViewModel.isLocked.collectAsState()
    val loginUiState by loginViewModel.uiState.collectAsState()
    val showCacheWarning by settingsViewModel.showCacheWarning.collectAsState()

    val isSafeFolderPinSet by securityViewModel.isSafeFolderPinSet.collectAsState()
    val isSafeFolderLockEnabled by securityViewModel.isSafeFolderLockEnabled.collectAsState()

    var showSafePinUnlockDialog by remember { mutableStateOf(false) }
    var showCreateSafePinDialog by remember { mutableStateOf(false) }

    // Safe Folder সম্পূর্ণ আলাদা পিন লজিক (শুধুমাত্র পিন কোড, কোনো বায়োমেট্রিক নয়)
    val triggerSafeFolderOpen = {
        if (!isSafeFolderPinSet) {
            showCreateSafePinDialog = true
        } else if (!isSafeFolderLockEnabled) {
            navController.navigate(Screen.SecureStorage.route)
        } else {
            showSafePinUnlockDialog = true
        }
    }

    val openFileOrPlayVideo: (DriveItem.File, Boolean) -> Unit = { file, isMediaOnly ->
        val isDirectPlayable = !file.isEncrypted && (file.mimeType.startsWith("video/") || file.mimeType.startsWith("audio/"))
        if (isDirectPlayable) {
            val localFile = file.localPath?.let { File(it) }
            val playTarget = if (localFile != null && localFile.exists()) {
                localFile.absolutePath
            } else {
                "tdstream://${file.parentChatId}|${file.id}|${file.telegramFileId}|${file.size}|${file.name}"
            }
            navController.navigate(Screen.VideoPlayer.createRoute(playTarget))
        } else {
            navController.navigate(
                Screen.Preview.createRoute(
                    chatId = file.parentChatId,
                    fileId = file.id,
                    isMediaOnly = isMediaOnly,
                    virtualParentId = file.virtualParentId ?: "0"
                )
            )
        }
    }

    LaunchedEffect(isLocked, loginUiState) {
        if (isLocked && loginUiState is com.awd.teledrive.ui.screens.auth.LoginUiState.Success && currentRoute != Screen.Security.route) {
            navController.navigate(Screen.Security.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    LaunchedEffect(loginUiState, currentRoute) {
        if (currentRoute != null &&
            loginUiState !is com.awd.teledrive.ui.screens.auth.LoginUiState.Success &&
            loginUiState !is com.awd.teledrive.ui.screens.auth.LoginUiState.Loading &&
            currentRoute != Screen.Login.route && 
            currentRoute != Screen.Security.route) {
            
            navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    val bottomNavScreens = listOf(Screen.Home, Screen.Media, Screen.Settings)
    val shouldShowBottomBar = currentRoute in bottomNavScreens.map { it.route } && currentRoute != Screen.SecureStorage.route

    Scaffold(
        bottomBar = {
            if (shouldShowBottomBar) {
                NavigationBar {
                    bottomNavScreens.forEach { screen ->
                        val isHome = screen == Screen.Home
                        NavigationBarItem(
                            icon = { 
                                Box(
                                    modifier = if (isHome) {
                                        Modifier.combinedClickable(
                                            onClick = {
                                                if (currentRoute != screen.route) {
                                                    navController.navigate(screen.route) {
                                                        popUpTo(Screen.Home.route) { saveState = true }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                }
                                            },
                                            onLongClick = { triggerSafeFolderOpen() }
                                        )
                                    } else Modifier
                                ) {
                                    Icon(screen.icon!!, contentDescription = null) 
                                }
                            },
                            label = { Text(stringResource(screen.labelRes!!)) },
                            selected = currentRoute == screen.route,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (isLocked) Screen.Security.route else Screen.Login.route,
            modifier = Modifier.padding(
                bottom = if (shouldShowBottomBar) innerPadding.calculateBottomPadding() else 0.dp
            )
        ) {
            composable(Screen.Security.route) {
                MasterPasswordScreen(
                    onSuccess = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Security.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Login.route) {
                LoginScreen(
                    onLoginSuccess = {
                        val nextRoute = if (securityViewModel.isLocked.value) Screen.Security.route else Screen.Home.route
                        navController.navigate(nextRoute) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToTransfers = { navController.navigate(Screen.Transfers.route) },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToStarred = { navController.navigate(Screen.Starred.route) },
                    onNavigateToSafeFolder = { triggerSafeFolderOpen() },
                    onNavigateToCloudAnalysis = { navController.navigate(Screen.CloudAnalysis.route) },
                    onNavigateToPreview = { file -> openFileOrPlayVideo(file, false) }
                )
            }
            composable(Screen.Starred.route) {
                StarredScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToPreview = { file -> openFileOrPlayVideo(file, false) }
                )
            }
            composable(Screen.Media.route) {
                MediaScreen(
                    onNavigateToPreview = { file -> openFileOrPlayVideo(file, true) }
                )
            }
            composable(Screen.Transfers.route) {
                TransfersScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToBackupFolders = { navController.navigate(Screen.BackupSettings.route) },
                    onNavigateToLogs = { navController.navigate(Screen.Logs.route) },
                    onNavigateToCacheDetails = { navController.navigate(Screen.CacheDetails.route) },
                    onNavigateToCloudAnalysis = { navController.navigate(Screen.CloudAnalysis.route) },
                    onNavigateToAbout = { navController.navigate(Screen.About.route) }
                )
            }
            composable(Screen.About.route) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.CacheDetails.route) {
                CacheDetailsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.CloudAnalysis.route) {
                CloudAnalysisScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.BackupSettings.route) {
                BackupSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Logs.route) {
                LogsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.SecureStorage.route) {
                com.awd.teledrive.ui.screens.security.SecureStorageScreen(
                    onBack = { 
                        navController.popBackStack()
                        securityViewModel.lockSecureMode()
                    },
                    onNavigateToPreview = { file -> openFileOrPlayVideo(file, false) },
                    onNavigateToTransfers = { navController.navigate(Screen.Transfers.route) }
                )
            }
            composable(
                route = Screen.Preview.route,
                arguments = listOf(
                    navArgument("chatId") { type = NavType.LongType },
                    navArgument("fileId") { type = NavType.LongType },
                    navArgument("isMediaOnly") { type = NavType.BoolType; defaultValue = false },
                    navArgument("virtualParentId") { type = NavType.StringType; defaultValue = "0" }
                )
            ) {
                PreviewScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlayer = { path -> navController.navigate(Screen.VideoPlayer.createRoute(path)) },
                    onOpenPdf = { path -> navController.navigate(Screen.PdfViewer.createRoute(path)) },
                    onOpenText = { path -> navController.navigate(Screen.TextViewer.createRoute(path)) }
                )
            }
            composable(
                route = Screen.VideoPlayer.route,
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("path")?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
                VideoPlayerScreen(url = path, onBack = { navController.popBackStack() })
            }
            composable(
                route = Screen.PdfViewer.route,
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("path")?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
                PdfViewerScreen(filePath = path, onBack = { navController.popBackStack() })
            }
            composable(
                route = Screen.TextViewer.route,
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("path")?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
                TextViewerScreen(filePath = path, onBack = { navController.popBackStack() })
            }
        }
    }

    // Safe Folder-এর স্বতন্ত্র পিন কোড তৈরি করার ডায়ালগ
    if (showCreateSafePinDialog) {
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showCreateSafePinDialog = false },
            title = { Text("Set Safe Folder PIN") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(6); error = null },
                        label = { Text("Enter PIN Code") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(6); error = null },
                        label = { Text("Confirm PIN Code") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newPin.length < 4) {
                        error = "Enter at least 4 digits"
                    } else if (newPin != confirmPin) {
                        error = "PIN did not match"
                    } else {
                        securityViewModel.setupSafeFolderPin(newPin)
                        showCreateSafePinDialog = false
                        navController.navigate(Screen.SecureStorage.route)
                    }
                }) { Text("Save & Open") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateSafePinDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Safe Folder পিন আনলক ডায়ালগ (শুধুমাত্র পিন কোড)
    if (showSafePinUnlockDialog) {
        var pin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        
        AlertDialog(
            onDismissRequest = { showSafePinUnlockDialog = false },
            title = { Text("Safe Folder PIN") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { input ->
                            val clean = input.filter { it.isDigit() }.take(6)
                            pin = clean
                            error = null
                        },
                        label = { Text("Enter PIN Code") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (securityViewModel.verifySafeFolderPin(pin)) {
                        showSafePinUnlockDialog = false
                        navController.navigate(Screen.SecureStorage.route)
                    } else {
                        error = "Incorrect PIN"
                    }
                }) { Text(stringResource(R.string.unlock)) }
            },
            dismissButton = {
                TextButton(onClick = { showSafePinUnlockDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showCacheWarning) {
        AlertDialog(
            onDismissRequest = { settingsViewModel.dismissCacheWarning() },
            title = { Text(stringResource(R.string.clear_cache_confirm_title)) },
            text = { Text(stringResource(R.string.cache_limit_reached_message)) },
            confirmButton = {
                TextButton(onClick = {
                    settingsViewModel.clearCache()
                    settingsViewModel.dismissCacheWarning()
                }) { Text(stringResource(R.string.clear_now), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { settingsViewModel.dismissCacheWarning() }) { Text(stringResource(R.string.later)) }
            }
        )
    }
}
