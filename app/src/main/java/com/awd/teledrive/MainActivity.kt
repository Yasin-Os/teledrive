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
                        animationSpec = tween(350),
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
    var sketchPhase by remember { mutableStateOf(false) }
    var fillPhase by remember { mutableStateOf(false) }
    var textPhase by remember { mutableStateOf(false) }

    // কোনো ব্যাকগ্রাউন্ড বক্স ছাড়াই লাইভ ড্রয়িং (Sketch) প্রোগ্রেস
    val drawProgress by animateFloatAsState(
        targetValue = if (sketchPhase) 1f else 0f,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "DrawProgress"
    )
    val fillAlpha by animateFloatAsState(
        targetValue = if (fillPhase) 1f else 0f,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "FillAlpha"
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (textPhase) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "TextAlpha"
    )
    val textOffsetY by animateDpAsState(
        targetValue = if (textPhase) 0.dp else 8.dp,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "TextOffsetY"
    )

    LaunchedEffect(Unit) {
        sketchPhase = true
        delay(550)
        fillPhase = true
        textPhase = true
        delay(850)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070B14)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // কোনো ব্যাকগ্রাউন্ড বক্স ছাড়াই সরাসরি মেঘ, ২টি তীর ও ৩টি ওয়েভ আঁকা হচ্ছে
            Canvas(modifier = Modifier.size(120.dp)) {
                val s = size.width / 108f

                val cloudPath = Path().apply {
                    moveTo(48f * s, 75.5f * s)
                    lineTo(77.5f * s, 75.5f * s)
                    cubicTo(84.8f * s, 75.5f * s, 90.5f * s, 69.8f * s, 90.5f * s, 62.5f * s)
                    cubicTo(90.5f * s, 55.2f * s, 84.5f * s, 49.5f * s, 77.0f * s, 49.5f * s)
                    cubicTo(75.0f * s, 43.0f * s, 69.0f * s, 39.0f * s, 62.0f * s, 39.5f * s)
                    cubicTo(59.0f * s, 31.8f * s, 51.5f * s, 27.0f * s, 43.0f * s, 27.0f * s)
                    cubicTo(32.5f * s, 27.0f * s, 24.5f * s, 35.0f * s, 24.0f * s, 45.0f * s)
                    cubicTo(18.0f * s, 47.5f * s, 14.5f * s, 54.0f * s, 14.5f * s, 61.5f * s)
                    cubicTo(14.5f * s, 71.0f * s, 23.0f * s, 77.5f * s, 34.0f * s, 77.5f * s)
                    close()
                }

                val arrow1Path = Path().apply {
                    moveTo(16f * s, 63f * s)
                    cubicTo(21f * s, 73f * s, 32f * s, 72f * s, 50f * s, 54f * s)
                }
                val arrow2Path = Path().apply {
                    moveTo(24f * s, 78f * s)
                    cubicTo(37f * s, 80f * s, 50f * s, 70f * s, 65f * s, 46f * s)
                }

                val pm = PathMeasure()

                // 1. Live Sketching of Cloud Outline
                pm.setPath(cloudPath, false)
                val partialCloud = Path()
                pm.getSegment(0f, pm.length * drawProgress, partialCloud, true)
                drawPath(
                    path = partialCloud,
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF0088FF), Color(0xFF38F9E8))
                    ),
                    style = Stroke(width = 3.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // 2. Smooth 3D Fill Reveal of Cloud Lobes
                if (fillAlpha > 0f) {
                    drawPath(
                        path = cloudPath,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF0055E0).copy(alpha = fillAlpha),
                                Color(0xFF1CB5FF).copy(alpha = fillAlpha),
                                Color(0xFF38F9E8).copy(alpha = fillAlpha)
                            ),
                            start = Offset(15f * s, 75f * s),
                            end = Offset(90f * s, 30f * s)
                        )
                    )

                    // White 3D Ribbon Band
                    val ribbonPath = Path().apply {
                        moveTo(14.5f * s, 60.5f * s)
                        cubicTo(15f * s, 75f * s, 27f * s, 81.5f * s, 41.5f * s, 78.5f * s)
                        cubicTo(52.5f * s, 76.2f * s, 63.5f * s, 68.5f * s, 72.5f * s, 58.5f * s)
                        cubicTo(76.8f * s, 53.5f * s, 76.5f * s, 45.5f * s, 71.2f * s, 41.5f * s)
                        cubicTo(66.0f * s, 37.8f * s, 58.2f * s, 39.2f * s, 53.0f * s, 44.2f * s)
                        cubicTo(43.5f * s, 53.5f * s, 34.5f * s, 62.5f * s, 25.5f * s, 65.2f * s)
                        close()
                    }
                    drawPath(path = ribbonPath, color = Color.White.copy(alpha = fillAlpha))
                }

                // 3. Live Drawing of the 2 Curved Swoosh Arrows
                listOf(
                    Triple(arrow1Path, Color(0xFF0099FF), 3.8f * s),
                    Triple(arrow2Path, Color(0xFF0066E6), 4.6f * s)
                ).forEach { (p, col, strokeW) ->
                    pm.setPath(p, false)
                    val seg = Path()
                    pm.getSegment(0f, pm.length * drawProgress, seg, true)
                    drawPath(seg, col, style = Stroke(width = strokeW, cap = StrokeCap.Round))
                }

                if (drawProgress > 0.75f) {
                    val headAlpha = ((drawProgress - 0.75f) * 4f).coerceIn(0f, 1f)
                    val head1 = Path().apply {
                        moveTo(53.5f * s, 51f * s); lineTo(43f * s, 54f * s); lineTo(50.5f * s, 61f * s); close()
                    }
                    val head2 = Path().apply {
                        moveTo(67.5f * s, 43f * s); lineTo(55.5f * s, 49.5f * s); lineTo(68.5f * s, 56f * s); close()
                    }
                    drawPath(head1, Color(0xFF0099FF).copy(alpha = headAlpha))
                    drawPath(head2, Color(0xFF0066E6).copy(alpha = headAlpha))
                }

                // 4. Live Drawing of the 3 Signal Waves at Top-Right
                val waveSweep = 72f * drawProgress
                listOf(18f to 35f, 32f to 28f, 46f to 21f).forEach { (boxDim, topY) ->
                    drawArc(
                        color = Color(0xFF18B8FF),
                        startAngle = -75f,
                        sweepAngle = waveSweep,
                        useCenter = false,
                        topLeft = Offset((67.5f - boxDim / 2f) * s, topY * s),
                        size = Size(boxDim * s, boxDim * s),
                        style = Stroke(width = 4.2f * s, cap = StrokeCap.Round)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.app_name),
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                modifier = Modifier
                    .offset(y = textOffsetY)
                    .alpha(textAlpha)
            )

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = "Developer: Yasin Adnan",
                color = Color(0xFF38BDF8).copy(alpha = 0.8f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.4.sp,
                modifier = Modifier
                    .offset(y = textOffsetY)
                    .alpha(textAlpha)
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
