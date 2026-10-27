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
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.offset
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
                    if (lastPauseTime != 0L && (now - lastPauseTime > 15_000)) {
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
                        animationSpec = tween(400),
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
    var drawIconPhase by remember { mutableStateOf(false) }
    var fillIconPhase by remember { mutableStateOf(false) }
    var showTitlePhase by remember { mutableStateOf(false) }
    var showDevPhase by remember { mutableStateOf(false) }

    val strokeProgress by animateFloatAsState(
        targetValue = if (drawIconPhase) 1f else 0f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "StrokeProgress"
    )
    val fillAlpha by animateFloatAsState(
        targetValue = if (fillIconPhase) 1f else 0f,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "FillAlpha"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (fillIconPhase) 1f else 0.85f,
        animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
        label = "IconScale"
    )

    val titleAlpha by animateFloatAsState(
        targetValue = if (showTitlePhase) 1f else 0f,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "TitleAlpha"
    )
    val titleOffsetY by animateDpAsState(
        targetValue = if (showTitlePhase) 0.dp else 14.dp,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "TitleOffset"
    )

    val devAlpha by animateFloatAsState(
        targetValue = if (showDevPhase) 1f else 0f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "DevAlpha"
    )
    val devOffsetY by animateDpAsState(
        targetValue = if (showDevPhase) 0.dp else 10.dp,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "DevOffset"
    )

    LaunchedEffect(Unit) {
        drawIconPhase = true
        delay(480)
        fillIconPhase = true
        delay(180)
        showTitlePhase = true
        delay(220)
        showDevPhase = true
        delay(900)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF070B14), Color(0xFF0B1322))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // নতুন প্রফেশনাল আইকন ড্রয়িং অ্যানিমেশন
            Canvas(
                modifier = Modifier
                    .size(106.dp)
                    .scale(iconScale)
            ) {
                val w = size.width
                val h = size.height
                val s = w / 108f

                // Ambient Glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF00E5FF).copy(alpha = 0.25f * strokeProgress), Color.Transparent),
                        center = Offset(w / 2f, h / 2f),
                        radius = w * 0.55f
                    ),
                    radius = w * 0.55f
                )

                // Squircle Frame
                drawRoundRect(
                    color = Color(0xFF0F2942).copy(alpha = 0.85f * fillAlpha),
                    topLeft = Offset(14f * s, 14f * s),
                    size = Size(80f * s, 80f * s),
                    cornerRadius = CornerRadius(22f * s, 22f * s)
                )

                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF00F2FE).copy(alpha = strokeProgress),
                            Color(0xFF0284C7).copy(alpha = 0.4f * strokeProgress)
                        )
                    ),
                    topLeft = Offset(14f * s, 14f * s),
                    size = Size(80f * s, 80f * s),
                    cornerRadius = CornerRadius(22f * s, 22f * s),
                    style = Stroke(width = 2.2f * s)
                )

                // Main Cloud Path
                val cloudPath = Path().apply {
                    moveTo(39.5f * s, 67f * s)
                    lineTo(68.8f * s, 67f * s)
                    cubicTo(73.9f * s, 67f * s, 78f * s, 62.9f * s, 78f * s, 57.8f * s)
                    cubicTo(78f * s, 52.9f * s, 74.3f * s, 48.9f * s, 69.5f * s, 48.5f * s)
                    cubicTo(68f * s, 41.2f * s, 61.5f * s, 36f * s, 53.8f * s, 36f * s)
                    cubicTo(46.8f * s, 36f * s, 40.8f * s, 40.3f * s, 38.4f * s, 46.4f * s)
                    cubicTo(33.6f * s, 47.2f * s, 30f * s, 51.4f * s, 30f * s, 56.5f * s)
                    cubicTo(30f * s, 62.3f * s, 34.7f * s, 67f * s, 39.5f * s, 67f * s)
                    close()
                }

                val measure = PathMeasure()
                measure.setPath(cloudPath, false)
                val partialCloud = Path()
                measure.getSegment(0f, measure.length * strokeProgress, partialCloud, true)

                drawPath(
                    path = partialCloud,
                    color = Color(0xFF00F2FE),
                    style = Stroke(width = 3.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                if (fillAlpha > 0f) {
                    drawPath(
                        path = cloudPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = fillAlpha),
                                Color(0xFFDFF6FF).copy(alpha = fillAlpha)
                            )
                        )
                    )

                    // Minimalist Upward Chevron inside Cloud
                    val chevron = Path().apply {
                        moveTo(47.5f * s, 54.5f * s)
                        lineTo(54f * s, 48f * s)
                        lineTo(60.5f * s, 54.5f * s)
                    }
                    drawPath(
                        path = chevron,
                        color = Color(0xFF0284C7).copy(alpha = fillAlpha),
                        style = Stroke(width = 3.4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawLine(
                        color = Color(0xFF0284C7).copy(alpha = fillAlpha),
                        start = Offset(54f * s, 48.5f * s),
                        end = Offset(54f * s, 61.5f * s),
                        strokeWidth = 3.4f * s,
                        cap = StrokeCap.Round
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // অ্যাপের নাম সুন্দর ক্লিন ফন্টে
            Text(
                text = stringResource(R.string.app_name),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                modifier = Modifier
                    .offset(y = titleOffsetY)
                    .alpha(titleAlpha)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // অ্যাপ নামের ঠিক নিচেই ডেভেলপারের নাম সুন্দর অ্যানিমেশনে
            Text(
                text = "Developer: Yasin Adnan",
                color = Color(0xFF38BDF8).copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                modifier = Modifier
                    .offset(y = devOffsetY)
                    .alpha(devAlpha)
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
