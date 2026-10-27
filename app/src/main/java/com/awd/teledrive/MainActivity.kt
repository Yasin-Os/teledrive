package com.awd.teledrive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.awd.teledrive.data.model.GitHubRelease
import com.awd.teledrive.data.repository.SettingsRepository
import com.awd.teledrive.data.repository.UpdateRepository
import com.awd.teledrive.data.repository.UpdateState
import com.awd.teledrive.data.secure.SecureSettings
import com.awd.teledrive.data.worker.TeleDriveWorkerManager
import com.awd.teledrive.ui.navigation.NavGraph
import com.awd.teledrive.ui.screens.security.SecurityViewModel
import com.awd.teledrive.ui.screens.settings.SettingsViewModel
import com.awd.teledrive.ui.theme.TeledriveTheme
import com.awd.teledrive.ui.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var workerManager: TeleDriveWorkerManager
    
    @Inject
    lateinit var secureSettings: SecureSettings

    @Inject
    lateinit var settingsRepository: SettingsRepository
    
    @Inject
    lateinit var updateRepository: UpdateRepository
    
    private val securityViewModel: SecurityViewModel by viewModels()
    private var lastPauseTime: Long = 0

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permissions.add(Manifest.permission.CAMERA)
        
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()
        
        lifecycleScope.launch {
            updateRepository.checkForUpdates(manual = false)
        }

        // অটো-ব্যাকআপ চালু থাকলে সিডিউল সচল রাখা হবে
        if (settingsRepository.isAutoBackupEnabled.value) {
            workerManager.scheduleBackup(true)
        }
        
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val now = System.currentTimeMillis()
                    if (lastPauseTime != 0L && (now - lastPauseTime > 30_000)) {
                        securityViewModel.setLocked(true)
                    }
                } else if (event == Lifecycle.Event.ON_PAUSE) {
                    lastPauseTime = System.currentTimeMillis()
                }
            },
        )

        enableEdgeToEdge()
        @OptIn(ExperimentalMaterial3Api::class)
        setContent {
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            
            val isDarkModePref by themeViewModel.isDarkMode.collectAsState()
            val themeColor by themeViewModel.themeColor.collectAsState()
            val updateState by updateRepository.updateState.collectAsState()
            
            val darkTheme = isDarkModePref ?: isSystemInDarkTheme()
            val configuration = LocalConfiguration.current

            var showSplash by remember { mutableStateOf(true) }
            
            CompositionLocalProvider(
                LocalConfiguration provides configuration
            ) {
                TeledriveTheme(darkTheme = darkTheme, primaryColor = themeColor) {
                    Crossfade(
                        targetState = showSplash,
                        animationSpec = tween(500),
                        label = "StartupSplashTransition"
                    ) { isSplash ->
                        if (isSplash) {
                            StartupSplashScreen(onFinished = { showSplash = false })
                        } else {
                            val navController = rememberNavController()
                            NavGraph(navController = navController)
                        }
                    }
                    
                    if (!showSplash) {
                        GlobalUpdateDialog(
                            updateState = updateState,
                            onDownloadUpdate = { release -> updateRepository.downloadAndInstall(release) },
                            onDismiss = { updateRepository.resetState() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StartupSplashScreen(onFinished: () -> Unit) {
    var startAnim by remember { mutableStateOf(false) }
    val developerName = "Yasin Adnan"
    var typedIndex by remember { mutableIntStateOf(0) }

    val scale by animateFloatAsState(
        targetValue = if (startAnim) 1f else 0.6f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "IconScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (startAnim) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "IconAlpha"
    )

    LaunchedEffect(Unit) {
        startAnim = true
        delay(350)
        for (i in 1..developerName.length) {
            typedIndex = i
            delay(85)
        }
        delay(700)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF0B1120)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .scale(scale)
                .alpha(alpha)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                modifier = Modifier.size(108.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(82.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CloudQueue,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(46.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = stringResource(R.string.app_name),
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.2.sp
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 52.dp)
                .alpha(alpha)
        ) {
            Text(
                text = "DEVELOPED BY",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = developerName.take(typedIndex),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalUpdateDialog(
    updateState: UpdateState,
    onDownloadUpdate: (GitHubRelease) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(updateState) {
        when (updateState) {
            is UpdateState.UpToDate -> {
                android.widget.Toast.makeText(context, context.getString(R.string.up_to_date), android.widget.Toast.LENGTH_SHORT).show()
                onDismiss()
            }
            is UpdateState.Error -> {
                android.widget.Toast.makeText(context, context.getString(R.string.err_update_check, updateState.message), android.widget.Toast.LENGTH_SHORT).show()
                onDismiss()
            }
            else -> {}
        }
    }
    
    when (updateState) {
        is UpdateState.NewVersionAvailable -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Update Available (${updateState.release.tag_name})") },
                text = { 
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        Text(updateState.release.body.ifEmpty { "A new version of MyCloud is ready to install." })
                    }
                },
                confirmButton = {
                    Button(onClick = { onDownloadUpdate(updateState.release) }) {
                        Text("Update Now")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.later))
                    }
                }
            )
        }
        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Updating App...") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(
                            progress = { updateState.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "${(updateState.progress * 100).toInt()}% • ${updateState.progressText}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {}
            )
        }
        else -> {}
    }
}
