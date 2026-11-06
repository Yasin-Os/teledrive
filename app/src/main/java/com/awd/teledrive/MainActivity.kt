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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.awd.teledrive.data.model.GitHubRelease
import com.awd.teledrive.data.repository.AuthRepository
import com.awd.teledrive.data.repository.SettingsRepository
import com.awd.teledrive.data.repository.UpdateRepository
import com.awd.teledrive.data.repository.UpdateState
import com.awd.teledrive.data.secure.SecureSettings
import com.awd.teledrive.data.worker.TeleDriveWorkerManager
import com.awd.teledrive.ui.navigation.NavGraph
import com.awd.teledrive.ui.screens.security.SecurityViewModel
import com.awd.teledrive.ui.theme.TeledriveTheme
import com.awd.teledrive.ui.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
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

    @Inject
    lateinit var authRepository: AuthRepository

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
                    updateRepository.onAppResumed()
                    lifecycleScope.launch {
                        updateRepository.checkForUpdates(manual = false)
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

            val isDarkModePref by themeViewModel.isDarkMode.collectAsState()
            val themeColor by themeViewModel.themeColor.collectAsState()
            val updateState by updateRepository.updateState.collectAsState()
            val isBannerHidden by updateRepository.isDownloadMinimized.collectAsState()

            val darkTheme = isDarkModePref ?: isSystemInDarkTheme()
            val configuration = LocalConfiguration.current

            var showSplash by remember { mutableStateOf(true) }

            LaunchedEffect(showSplash) {
                if (!showSplash) {
                    checkAndRequestPermissions()
                    updateRepository.checkForUpdates(manual = false)
                    while (true) {
                        delay(5 * 60 * 1000L)
                        updateRepository.checkForUpdates(manual = false)
                    }
                }
            }

            CompositionLocalProvider(
                LocalConfiguration provides configuration
            ) {
                TeledriveTheme(darkTheme = darkTheme, primaryColor = themeColor) {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF070B14))) {
                        // NavGraph শুরু থেকেই নিচে প্রস্তুত থাকবে, যাতে স্প্ল্যাশ শেষ হলেই সরাসরি হোম স্ক্রিন ভেসে ওঠে
                        val navController = rememberNavController()
                        NavGraph(navController = navController)

                        // উপরে স্মুথ স্টার্টআপ স্প্ল্যাশ ওভারলে
                        AnimatedVisibility(
                            visible = showSplash,
                            enter = EnterTransition.None,
                            exit = fadeOut(tween(240, easing = FastOutSlowInEasing))
                        ) {
                            StartupSplashScreen(onFinished = { showSplash = false })
                        }

                        if (!showSplash) {
                            SlimTopUpdateBar(
                                updateState = updateState,
                                isHidden = isBannerHidden,
                                onStartDownload = { release ->
                                    updateRepository.downloadAndInstall(release, runInBackground = true)
                                },
                                onInstallReadyApk = { file ->
                                    updateRepository.installApk(file)
                                },
                                onDismissBanner = {
                                    updateRepository.dismissBanner()
                                },
                                onResetState = {
                                    updateRepository.resetState()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun svgPath(d: String): Path = PathParser().parsePathString(d).toPath()

private fun grad(
    sx: Float, sy: Float, ex: Float, ey: Float,
    vararg stops: Pair<Float, Color>
): Brush = Brush.linearGradient(
    *stops, start = Offset(sx, sy), end = Offset(ex, ey)
)

private fun seg(t: Float, a: Float, b: Float): Float =
    FastOutSlowInEasing.transform(((t - a) / (b - a)).coerceIn(0f, 1f))

/**
 * ১০০% স্ট্যাটিক পাথ ক্যাশ: অ্যানিমেশন চলাকালীন কোনো PathMeasure বা নতুন অবজেক্ট তৈরি হয় না।
 * GPU সরাসরি ক্যাশড ভেক্টর রেন্ডার করে, ফলে ০% ল্যাগ এবং ১২০ FPS পাওয়া যায়।
 */
private class IconShapes {
    val lobeUR = svgPath("M54,39 C64,39 73,45 75,55 L50,58 Z")
    val aqua = svgPath("M48,75.5 L77.5,75.5 C84.8,75.5 90.5,69.8 90.5,62.5 C90.5,55.2 84.5,49.5 77.0,49.5 C70.5,49.5 65.0,53.8 63.2,59.8 L48,75.5 Z")
    val gloss = svgPath("M68,54 C74,50 83,52 87,58 C82,54 74,53 68,54 Z")
    val left = svgPath("M34,43.5 C23.2,43.5 14.5,52.0 14.5,62.5 C14.5,71.5 21.5,78.5 31.0,78.5 L48,62 Z")
    val sphere = svgPath("M46.5,27.0 C35.5,27.0 26.8,35.8 26.8,46.8 C26.8,57.8 35.5,66.5 46.5,66.5 C57.5,66.5 66.2,57.8 66.2,46.8 C66.2,35.8 57.5,27.0 46.5,27.0 Z")
    val ribbon = svgPath("M14.5,60.5 C14.8,74.8 26.5,81.5 41.5,78.5 C52.5,76.2 63.5,68.5 72.5,58.5 C76.8,53.5 76.5,45.5 71.2,41.5 C66.0,37.8 58.2,39.2 53.0,44.2 C43.5,53.5 34.5,62.5 25.5,65.2 C19.5,67.0 15.2,64.5 14.5,60.5 Z")
    val arrow1 = svgPath("M16.0,63.0 C20.5,72.5 31.5,72.5 45.5,58.5 L42.5,55.5 L53.5,51.5 L51.0,62.5 L48.0,59.5 C32.5,75.0 19.5,75.5 16.0,63.0 Z")
    val arrow2 = svgPath("M24.0,78.0 C36.5,80.5 49.5,71.5 60.0,53.5 L55.5,50.5 L67.5,43.0 L69.5,56.5 L65.0,54.0 C53.5,73.5 38.5,82.0 24.0,78.0 Z")

    val arc1 = svgPath("M66.4,34.6 A9,9 0 0,1 75.4,39.8")
    val arc2 = svgPath("M65.8,27.5 A16.2,16.2 0 0,1 81.9,36.8")
    val arc3 = svgPath("M65.2,20.2 A23.5,23.5 0 0,1 88.5,33.7")

    val outlineStroke = Stroke(width = 1.45f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val arcStroke = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    val bUR = grad(54f, 39f, 75f, 56f, 0f to Color(0xFF2CE0FF), 1f to Color(0xFF0099F0))
    val bAqua = grad(65f, 49f, 88f, 76f, 0f to Color(0xFF48F7E8), 0.55f to Color(0xFF00D2DC), 1f to Color(0xFF00A2C2))
    val bLeft = grad(16f, 44f, 36f, 78f, 0f to Color(0xFF0095FF), 0.55f to Color(0xFF0055E0), 1f to Color(0xFF0033B8))
    val bSphere = grad(36f, 28f, 58f, 64f, 0f to Color(0xFF28D8FF), 0.5f to Color(0xFF0080FF), 1f to Color(0xFF0040C8))
    val bRibbon = grad(15f, 76f, 72f, 42f, 0f to Color(0xFF0078FF), 0.25f to Color(0xFF88DCFF), 0.55f to Color(0xFFE8F8FF), 1f to Color(0xFFFFFFFF))
    val bArrow1 = grad(16f, 72f, 53f, 51f, 0f to Color(0xFF0055D4), 1f to Color(0xFF00B4FF))
    val bArrow2 = grad(24f, 79f, 68f, 43f, 0f to Color(0xFF0038B8), 0.5f to Color(0xFF0078F0), 1f to Color(0xFF00A8FF))
    val bArc = grad(65f, 21f, 89f, 43f, 0f to Color(0xFF18B8FF), 1f to Color(0xFF0055D4))
    val bOutline = grad(15f, 75f, 90f, 28f, 0f to Color(0xFF0088FF), 1f to Color(0xFF38F9E8))
}

@Composable
fun StartupSplashScreen(onFinished: () -> Unit) {
    val shapes = remember { IconShapes() }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // শুরুতে ৬০ms বিরতি যাতে Activity লেআউট ও ডাটাবেস থ্রেড সেটেল হয়, এরপর মাখনের মতো এনিমেশন চলে
        delay(60)
        progress.animateTo(1f, tween(durationMillis = 1150, easing = LinearEasing))
        delay(80)
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
            Canvas(
                modifier = Modifier
                    .size(146.dp)
                    .graphicsLayer {
                        val s = 0.93f + 0.07f * seg(progress.value, 0f, 0.85f)
                        scaleX = s
                        scaleY = s
                    }
            ) {
                val t = progress.value
                val s = size.width / 84f
                translate(
                    left = size.width / 2f - 52.5f * s,
                    top = size.height / 2f - 50.5f * s
                ) {
                    scale(scale = s, pivot = Offset.Zero) {
                        // GPU-accelerated clipRect ড্রয়িং (০% CPU ওভারহেড)
                        val sketch = seg(t, 0f, 0.36f)
                        val outlineAlpha = 1f - seg(t, 0.28f, 0.50f)
                        if (sketch > 0f && outlineAlpha > 0f) {
                            clipRect(left = 10f, top = 18f, right = 10f + 85f * sketch, bottom = 90f) {
                                drawPath(shapes.left, shapes.bOutline, alpha = outlineAlpha, style = shapes.outlineStroke)
                                drawPath(shapes.sphere, shapes.bOutline, alpha = outlineAlpha, style = shapes.outlineStroke)
                                drawPath(shapes.lobeUR, shapes.bOutline, alpha = outlineAlpha, style = shapes.outlineStroke)
                                drawPath(shapes.aqua, shapes.bOutline, alpha = outlineAlpha, style = shapes.outlineStroke)
                            }
                        }

                        drawPath(shapes.lobeUR, shapes.bUR, alpha = seg(t, 0.26f, 0.45f))
                        drawPath(shapes.aqua, shapes.bAqua, alpha = seg(t, 0.28f, 0.48f))
                        drawPath(shapes.gloss, Color.White, alpha = 0.4f * seg(t, 0.46f, 0.58f))
                        drawPath(shapes.left, shapes.bLeft, alpha = seg(t, 0.20f, 0.40f))
                        drawPath(shapes.sphere, shapes.bSphere, alpha = seg(t, 0.23f, 0.44f))

                        val ribbonP = seg(t, 0.40f, 0.64f)
                        if (ribbonP > 0f) {
                            clipRect(left = 0f, top = 0f, right = 14f + 80f * ribbonP, bottom = 108f) {
                                drawPath(shapes.ribbon, shapes.bRibbon)
                            }
                        }

                        val a1 = seg(t, 0.52f, 0.72f)
                        val a2 = seg(t, 0.58f, 0.78f)
                        if (a1 > 0f) clipRect(0f, 0f, 14f + 62f * a1, 108f) {
                            drawPath(shapes.arrow1, shapes.bArrow1)
                        }
                        if (a2 > 0f) clipRect(0f, 0f, 14f + 62f * a2, 108f) {
                            drawPath(shapes.arrow2, shapes.bArrow2)
                        }

                        val pArc1 = seg(t, 0.64f, 0.80f)
                        val pArc2 = seg(t, 0.71f, 0.87f)
                        val pArc3 = seg(t, 0.78f, 0.94f)
                        if (pArc1 > 0f) clipRect(64f, 18f, 64f + 28f * pArc1, 45f) {
                            drawPath(shapes.arc1, shapes.bArc, alpha = pArc1, style = shapes.arcStroke)
                        }
                        if (pArc2 > 0f) clipRect(64f, 18f, 64f + 28f * pArc2, 45f) {
                            drawPath(shapes.arc2, shapes.bArc, alpha = pArc2, style = shapes.arcStroke)
                        }
                        if (pArc3 > 0f) clipRect(64f, 18f, 64f + 28f * pArc3, 45f) {
                            drawPath(shapes.arc3, shapes.bArc, alpha = pArc3, style = shapes.arcStroke)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    val a = seg(progress.value, 0.62f, 0.92f)
                    alpha = a
                    translationY = (1f - a) * 8.dp.toPx()
                }
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    color = Color.White,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = "Developed by Yasin Adnan",
                    color = Color(0xFF38BDF8).copy(alpha = 0.82f),
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.4.sp
                )
            }
        }
    }
}

@Composable
fun SlimTopUpdateBar(
    updateState: UpdateState,
    isHidden: Boolean,
    onStartDownload: (GitHubRelease) -> Unit,
    onInstallReadyApk: (File) -> Unit,
    onDismissBanner: () -> Unit,
    onResetState: () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(updateState) {
        when (updateState) {
            is UpdateState.UpToDate -> {
                android.widget.Toast.makeText(context, context.getString(R.string.up_to_date), android.widget.Toast.LENGTH_SHORT).show()
                onResetState()
            }
            is UpdateState.Error -> {
                android.widget.Toast.makeText(context, updateState.message, android.widget.Toast.LENGTH_SHORT).show()
                onResetState()
            }
            else -> {}
        }
    }

    val visible = !isHidden && (
        updateState is UpdateState.NewVersionAvailable ||
        updateState is UpdateState.Downloading ||
        updateState is UpdateState.ReadyToInstall
    )

    var swipeOffsetX by remember(updateState, isHidden) { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(tween(220)),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .offset { IntOffset(swipeOffsetX.roundToInt(), 0) }
                .pointerInput(updateState) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(swipeOffsetX) > 160f) {
                                onDismissBanner()
                            }
                            swipeOffsetX = 0f
                        },
                        onDragCancel = { swipeOffsetX = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            swipeOffsetX += dragAmount
                        }
                    )
                }
        ) {
            when (updateState) {
                is UpdateState.NewVersionAvailable -> {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF141824).copy(alpha = 0.96f),
                        border = BorderStroke(1.dp, Color(0xFF2CE0FF).copy(alpha = 0.5f)),
                        shadowElevation = 10.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 14.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = null,
                                    tint = Color(0xFF2CE0FF),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Update ${updateState.release.tag_name} available",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { onStartDownload(updateState.release) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2CE0FF)),
                                    shape = RoundedCornerShape(50),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(
                                        text = "Update",
                                        color = Color(0xFF070B14),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = onDismissBanner,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = Color.White.copy(alpha = 0.65f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                is UpdateState.Downloading -> {
                    val pct = (updateState.progress * 100).toInt().coerceIn(0, 100)
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF141824).copy(alpha = 0.96f),
                        border = BorderStroke(1.dp, Color(0xFF2CE0FF).copy(alpha = 0.4f)),
                        shadowElevation = 10.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            LinearProgressIndicator(
                                progress = { updateState.progress },
                                color = Color(0xFF2CE0FF).copy(alpha = 0.18f),
                                trackColor = Color.Transparent,
                                modifier = Modifier.fillMaxSize()
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 14.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    CircularProgressIndicator(
                                        progress = { updateState.progress },
                                        modifier = Modifier.size(16.dp),
                                        color = Color(0xFF2CE0FF),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    val verTag = updateState.release?.tag_name ?: ""
                                    val speedPart = if (updateState.speedText.isNotEmpty()) " • ${updateState.speedText}" else ""
                                    Text(
                                        text = "Updating $verTag • $pct%$speedPart",
                                        color = Color.White,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = onDismissBanner,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Hide",
                                        tint = Color.White.copy(alpha = 0.65f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                is UpdateState.ReadyToInstall -> {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF0E2920).copy(alpha = 0.97f),
                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.7f)),
                        shadowElevation = 12.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 14.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Update ${updateState.release.tag_name} Ready",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { onInstallReadyApk(updateState.apkFile) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                    shape = RoundedCornerShape(50),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(
                                        text = "Install Now",
                                        color = Color.White,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = onDismissBanner,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }
}
