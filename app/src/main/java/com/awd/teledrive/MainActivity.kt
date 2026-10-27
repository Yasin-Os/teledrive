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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.TextStyle
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
    var cloudPhase by remember { mutableStateOf(false) }
    var swooshPhase by remember { mutableStateOf(false) }
    var signalPhase by remember { mutableStateOf(false) }
    var titlePhase by remember { mutableStateOf(false) }
    var devPhase by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "SplashPulse")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowPulse"
    )
    val shimmerShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ShimmerShift"
    )

    val badgeScale by animateFloatAsState(
        targetValue = if (cloudPhase) 1f else 0.5f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "BadgeScale"
    )
    val cloudAlpha by animateFloatAsState(
        targetValue = if (cloudPhase) 1f else 0f,
        animationSpec = tween(500),
        label = "CloudAlpha"
    )
    val swooshProgress by animateFloatAsState(
        targetValue = if (swooshPhase) 1f else 0f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "SwooshProgress"
    )
    val signalProgress by animateFloatAsState(
        targetValue = if (signalPhase) 1f else 0f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "SignalProgress"
    )

    val titleAlpha by animateFloatAsState(
        targetValue = if (titlePhase) 1f else 0f,
        animationSpec = tween(450),
        label = "TitleAlpha"
    )
    val titleOffsetY by animateDpAsState(
        targetValue = if (titlePhase) 0.dp else 18.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "TitleOffset"
    )

    val devAlpha by animateFloatAsState(
        targetValue = if (devPhase) 1f else 0f,
        animationSpec = tween(500),
        label = "DevAlpha"
    )
    val devOffsetY by animateDpAsState(
        targetValue = if (devPhase) 0.dp else 12.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "DevOffset"
    )

    LaunchedEffect(Unit) {
        cloudPhase = true
        delay(300)
        swooshPhase = true
        delay(350)
        signalPhase = true
        titlePhase = true
        delay(220)
        devPhase = true
        delay(1050)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF0C1E3E), Color(0xFF070B14)),
                    radius = 1100f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Background Halo Glow
                Canvas(modifier = Modifier.size(160.dp).scale(glowPulse)) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF00E5CE).copy(alpha = 0.28f * cloudAlpha), Color.Transparent)
                        ),
                        radius = size.minDimension / 2f
                    )
                }

                // হুবহু ২য় ছবির আইকনটির লাইভ অ্যানিমেশন
                Canvas(
                    modifier = Modifier
                        .size(116.dp)
                        .scale(badgeScale)
                ) {
                    val w = size.width
                    val h = size.height
                    val s = w / 108f

                    // 1. Vibrant Royal Blue to Cyan-Aqua Squircle Card
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF003894),
                                Color(0xFF0074D9),
                                Color(0xFF00B8E6),
                                Color(0xFF00E5CE)
                            ),
                            start = Offset(10f * s, 98f * s),
                            end = Offset(98f * s, 10f * s)
                        ),
                        cornerRadius = CornerRadius(26f * s, 26f * s),
                        size = Size(w, h)
                    )

                    // 2. White Cloud Silhouette
                    val cloudPath = Path().apply {
                        moveTo(34f * s, 74f * s)
                        lineTo(76f * s, 74f * s)
                        cubicTo(83.5f * s, 74f * s, 89.5f * s, 68.2f * s, 89.5f * s, 60.8f * s)
                        cubicTo(89.5f * s, 54.3f * s, 84.8f * s, 48.9f * s, 78.5f * s, 47.9f * s)
                        cubicTo(77.3f * s, 40.0f * s, 70.5f * s, 34.0f * s, 62.2f * s, 34.0f * s)
                        cubicTo(59.7f * s, 34.0f * s, 57.3f * s, 34.6f * s, 55.2f * s, 35.6f * s)
                        cubicTo(51.4f * s, 29.0f * s, 44.3f * s, 24.6f * s, 36.0f * s, 24.6f * s)
                        cubicTo(24.0f * s, 24.6f * s, 14.2f * s, 34.1f * s, 13.8f * s, 46.0f * s)
                        cubicTo(8.8f * s, 48.0f * s, 5.5f * s, 53.0f * s, 5.5f * s, 58.8f * s)
                        cubicTo(5.5f * s, 66.2f * s, 11.2f * s, 72.2f * s, 18.5f * s, 73.2f * s)
                        close()
                    }
                    drawPath(path = cloudPath, color = Color.White.copy(alpha = cloudAlpha))

                    // 3. Three Dynamic Swoosh Arrows Shooting Up-Right
                    if (swooshProgress > 0f) {
                        val s1 = Path().apply {
                            moveTo(12f * s, 53f * s)
                            cubicTo(15f * s, 72f * s, 30f * s, 70f * s, 46f * s, 48f * s)
                        }
                        val s2 = Path().apply {
                            moveTo(15f * s, 62f * s)
                            cubicTo(23f * s, 80f * s, 43f * s, 71f * s, 64f * s, 42f * s)
                        }
                        val s3 = Path().apply {
                            moveTo(24f * s, 74f * s)
                            cubicTo(38f * s, 84f * s, 53f * s, 74f * s, 65f * s, 57f * s)
                        }

                        val pm = PathMeasure()
                        listOf(
                            Triple(s1, Color(0xFF0088E0), 4.2f * s),
                            Triple(s2, Color(0xFF009CE8), 4.6f * s),
                            Triple(s3, Color(0xFF00AEEF), 4.2f * s)
                        ).forEach { (fullPath, col, strokeW) ->
                            pm.setPath(fullPath, false)
                            val seg = Path()
                            pm.getSegment(0f, pm.length * swooshProgress, seg, true)
                            drawPath(seg, col, style = Stroke(width = strokeW, cap = StrokeCap.Round))
                        }

                        if (swooshProgress > 0.8f) {
                            val headAlpha = ((swooshProgress - 0.8f) * 5f).coerceIn(0f, 1f)
                            val h1 = Path().apply {
                                moveTo(50f * s, 43f * s); lineTo(40f * s, 46.5f * s); lineTo(47.5f * s, 52.5f * s); close()
                            }
                            val h2 = Path().apply {
                                moveTo(68f * s, 37f * s); lineTo(57.5f * s, 40.5f * s); lineTo(65.5f * s, 47f * s); close()
                            }
                            val h3 = Path().apply {
                                moveTo(69f * s, 52f * s); lineTo(58.5f * s, 55.5f * s); lineTo(66.5f * s, 62f * s); close()
                            }
                            drawPath(h1, Color(0xFF0088E0).copy(alpha = headAlpha))
                            drawPath(h2, Color(0xFF009CE8).copy(alpha = headAlpha))
                            drawPath(h3, Color(0xFF00AEEF).copy(alpha = headAlpha))
                        }
                    }

                    // 4. Two Wireless Signal Waves Pulsing at Top-Right
                    if (signalProgress > 0f) {
                        drawArc(
                            color = Color.White.copy(alpha = signalProgress),
                            startAngle = -68f,
                            sweepAngle = 68f * signalProgress,
                            useCenter = false,
                            topLeft = Offset(55f * s, 27f * s),
                            size = Size(28f * s, 28f * s),
                            style = Stroke(width = 4.6f * s, cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = Color.White.copy(alpha = signalProgress),
                            startAngle = -68f,
                            sweepAngle = 68f * signalProgress,
                            useCenter = false,
                            topLeft = Offset(47f * s, 17f * s),
                            size = Size(46f * s, 46f * s),
                            style = Stroke(width = 4.6f * s, cap = StrokeCap.Round)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Shimmering App Name
            Text(
                text = stringResource(R.string.app_name),
                style = TextStyle(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.White, Color(0xFF00E5CE), Color.White),
                        start = Offset(shimmerShift - 200f, 0f),
                        end = Offset(shimmerShift + 100f, 0f)
                    ),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp
                ),
                modifier = Modifier
                    .offset(y = titleOffsetY)
                    .alpha(titleAlpha)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Sleek Glass Pill Developer Badge Right Below App Name
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White.copy(alpha = 0.06f),
                border = BorderStroke(0.8.dp, Color(0xFF00E5CE).copy(alpha = 0.35f)),
                modifier = Modifier
                    .offset(y = devOffsetY)
                    .alpha(devAlpha)
            ) {
                Text(
                    text = "Developer: Yasin Adnan",
                    color = Color(0xFFE0F7FA),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
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
