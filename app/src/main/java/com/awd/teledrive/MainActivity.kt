package com.awd.teledrive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
                        animationSpec = tween(450),
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
    var drawPhase by remember { mutableStateOf(false) }
    var fillPhase by remember { mutableStateOf(false) }
    var textPhase by remember { mutableStateOf(false) }

    val strokeProgress by animateFloatAsState(
        targetValue = if (drawPhase) 1f else 0f,
        animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing),
        label = "StrokeProgress"
    )
    val fillAlpha by animateFloatAsState(
        targetValue = if (fillPhase) 1f else 0f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "FillAlpha"
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (textPhase) 1f else 0f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "TextAlpha"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (fillPhase) 1f else 0.9f,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "IconScale"
    )

    LaunchedEffect(Unit) {
        drawPhase = true
        delay(650)
        fillPhase = true
        delay(200)
        textPhase = true
        delay(950)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090D16)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // মূল অ্যাপ আইকনটি লাইভ ড্র হয়ে তৈরি হওয়ার অ্যানিমেশন
            Canvas(
                modifier = Modifier
                    .size(104.dp)
                    .scale(iconScale)
            ) {
                val w = size.width
                val h = size.height
                val s = w / 108f

                // আইকন কার্ডের ব্যাকগ্রাউন্ড
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF0F2942), Color(0xFF0284C7)),
                        start = Offset.Zero,
                        end = Offset(w, h)
                    ),
                    cornerRadius = CornerRadius(26f * s, 26f * s),
                    size = Size(w, h),
                    alpha = fillAlpha * 0.95f
                )

                drawRoundRect(
                    color = Color(0xFF38BDF8).copy(alpha = 0.5f * strokeProgress),
                    cornerRadius = CornerRadius(26f * s, 26f * s),
                    size = Size(w, h),
                    style = Stroke(width = 2.2f * s)
                )

                // ক্লাউডের পাথ তৈরি
                val cloudPath = Path().apply {
                    moveTo(37.2f * s, 71f * s)
                    lineTo(70.5f * s, 71f * s)
                    cubicTo(76.8f * s, 71f * s, 82f * s, 65.8f * s, 82f * s, 59.5f * s)
                    cubicTo(82f * s, 53.7f * s, 77.3f * s, 49f * s, 71.5f * s, 48.5f * s)
                    cubicTo(69.9f * s, 39.6f * s, 62.1f * s, 33f * s, 52.8f * s, 33f * s)
                    cubicTo(44.8f * s, 33f * s, 38f * s, 37.9f * s, 35.2f * s, 44.8f * s)
                    cubicTo(28.9f * s, 45.7f * s, 24f * s, 51.1f * s, 24f * s, 57.8f * s)
                    cubicTo(24f * s, 65.1f * s, 29.9f * s, 71f * s, 37.2f * s, 71f * s)
                    close()
                }

                // ক্লাউডের আউটলাইন ড্রয়িং অ্যানিমেশন
                val measure = PathMeasure()
                measure.setPath(cloudPath, false)
                val partialCloudPath = Path()
                measure.getSegment(0f, measure.length * strokeProgress, partialCloudPath, true)

                drawPath(
                    path = partialCloudPath,
                    color = Color.White,
                    style = Stroke(width = 3.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // ক্লাউড সম্পূর্ণ ভরাট (Fill) হওয়া
                if (fillAlpha > 0f) {
                    drawPath(
                        path = cloudPath,
                        color = Color.White.copy(alpha = fillAlpha)
                    )

                    // ক্লাউডের ভেতরের আপলোড তীর চিহ্ন
                    val arrowPath = Path().apply {
                        moveTo(53f * s, 44f * s)
                        lineTo(44f * s, 53f * s)
                        lineTo(49.5f * s, 53f * s)
                        lineTo(49.5f * s, 64f * s)
                        lineTo(56.5f * s, 64f * s)
                        lineTo(56.5f * s, 53f * s)
                        lineTo(62f * s, 53f * s)
                        close()
                    }
                    drawPath(
                        path = arrowPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF0284C7).copy(alpha = fillAlpha),
                                Color(0xFF090D16).copy(alpha = fillAlpha)
                            )
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // অ্যাপের নাম ক্লিন ফন্টে
            Text(
                text = stringResource(R.string.app_name),
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                modifier = Modifier.alpha(textAlpha)
            )
        }

        // একদম নিচে ছোট ও ক্লিন ফন্টে Developer: Yasin Adnan
        Text(
            text = "Developer: Yasin Adnan",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.4.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 42.dp)
                .alpha(textAlpha)
        )
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
