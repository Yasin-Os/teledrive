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
    var drawPhase by remember { mutableStateOf(false) }
    var fillPhase by remember { mutableStateOf(false) }
    var textPhase by remember { mutableStateOf(false) }

    val strokeProgress by animateFloatAsState(
        targetValue = if (drawPhase) 1f else 0f,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "StrokeProgress"
    )
    val fillAlpha by animateFloatAsState(
        targetValue = if (fillPhase) 1f else 0f,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "FillAlpha"
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (textPhase) 1f else 0f,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "TextAlpha"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (fillPhase) 1f else 0.88f,
        animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
        label = "IconScale"
    )

    LaunchedEffect(Unit) {
        drawPhase = true
        delay(600)
        fillPhase = true
        delay(200)
        textPhase = true
        delay(900)
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
            // হুবহু ৩য় ছবির আইকনটি (লেখা ছাড়া) অ্যানিমেশনে তৈরি হচ্ছে
            Canvas(
                modifier = Modifier
                    .size(112.dp)
                    .scale(iconScale)
            ) {
                val w = size.width
                val h = size.height
                val s = w / 108f

                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF003B99), Color(0xFF0077CC), Color(0xFF00E5DF)),
                        start = Offset(12f * s, 96f * s),
                        end = Offset(96f * s, 12f * s)
                    ),
                    cornerRadius = CornerRadius(26f * s, 26f * s),
                    size = Size(w, h),
                    alpha = 0.25f + (0.75f * fillAlpha)
                )

                val cloudPath = Path().apply {
                    moveTo(29f * s, 68f * s)
                    lineTo(75f * s, 68f * s)
                    cubicTo(81.6f * s, 68f * s, 86.5f * s, 63.2f * s, 86.5f * s, 56.8f * s)
                    cubicTo(86.5f * s, 51.2f * s, 82.4f * s, 46.6f * s, 76.9f * s, 45.8f * s)
                    cubicTo(75.8f * s, 38.9f * s, 69.8f * s, 33.8f * s, 62.6f * s, 33.8f * s)
                    cubicTo(60.4f * s, 33.8f * s, 58.3f * s, 34.3f * s, 56.5f * s, 35.2f * s)
                    cubicTo(53.2f * s, 29.5f * s, 47.0f * s, 25.8f * s, 39.8f * s, 25.8f * s)
                    cubicTo(29.3f * s, 25.8f * s, 20.8f * s, 34.1f * s, 20.5f * s, 44.5f * s)
                    cubicTo(15.8f * s, 46.2f * s, 12.5f * s, 50.7f * s, 12.5f * s, 56.0f * s)
                    cubicTo(12.5f * s, 62.7f * s, 18.0f * s, 68f * s, 25.0f * s, 68f * s)
                    close()
                }

                val measure = PathMeasure()
                measure.setPath(cloudPath, false)
                val partialCloud = Path()
                measure.getSegment(0f, measure.length * strokeProgress, partialCloud, true)

                drawPath(
                    path = partialCloud,
                    color = Color.White,
                    style = Stroke(width = 3.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                if (fillAlpha > 0f) {
                    drawPath(path = cloudPath, color = Color.White.copy(alpha = fillAlpha))

                    // ২টি সিগন্যাল ওয়েভ আর্ক
                    drawArc(
                        color = Color.White.copy(alpha = fillAlpha),
                        startAngle = -65f,
                        sweepAngle = 70f,
                        useCenter = false,
                        topLeft = Offset(55f * s, 30f * s),
                        size = Size(24f * s, 24f * s),
                        style = Stroke(width = 3.8f * s, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = Color.White.copy(alpha = fillAlpha),
                        startAngle = -65f,
                        sweepAngle = 70f,
                        useCenter = false,
                        topLeft = Offset(49f * s, 24f * s),
                        size = Size(36f * s, 36f * s),
                        style = Stroke(width = 3.8f * s, cap = StrokeCap.Round)
                    )

                    // ৩টি বাঁকানো তীর (Swoosh Arrows)
                    val swoosh1 = Path().apply {
                        moveTo(19f * s, 55f * s)
                        quadraticTo(28f * s, 68f * s, 46f * s, 48f * s)
                    }
                    val swoosh2 = Path().apply {
                        moveTo(22f * s, 63f * s)
                        quadraticTo(35f * s, 74f * s, 63f * s, 43f * s)
                    }
                    val swoosh3 = Path().apply {
                        moveTo(29f * s, 72f * s)
                        quadraticTo(45f * s, 78f * s, 64f * s, 56f * s)
                    }

                    drawPath(swoosh1, Color(0xFF005BB5).copy(alpha = fillAlpha), style = Stroke(3.5f * s, cap = StrokeCap.Round))
                    drawPath(swoosh2, Color(0xFF0066CC).copy(alpha = fillAlpha), style = Stroke(3.8f * s, cap = StrokeCap.Round))
                    drawPath(swoosh3, Color(0xFF0088DD).copy(alpha = fillAlpha), style = Stroke(3.5f * s, cap = StrokeCap.Round))
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = stringResource(R.string.app_name),
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                modifier = Modifier.alpha(textAlpha)
            )
        }

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
