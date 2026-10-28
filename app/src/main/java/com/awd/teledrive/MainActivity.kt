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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
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

// ───────────────────────── Splash helpers ─────────────────────────

private fun svgPath(d: String): Path = PathParser().parsePathString(d).toPath()

private fun grad(
    sx: Float, sy: Float, ex: Float, ey: Float,
    vararg stops: Pair<Float, Color>
): Brush = Brush.linearGradient(
    *stops, start = Offset(sx, sy), end = Offset(ex, ey)
)

/** t এর [a, b] অংশকে 0..1 এ ম্যাপ করে, ইজিং সহ */
private fun seg(t: Float, a: Float, b: Float): Float =
    FastOutSlowInEasing.transform(((t - a) / (b - a)).coerceIn(0f, 1f))

private fun DrawScope.drawTrimmed(
    path: Path, pm: PathMeasure, fraction: Float,
    brush: Brush, width: Float, alpha: Float = 1f
) {
    if (fraction <= 0f || alpha <= 0f) return
    pm.setPath(path, false)
    val out = Path()
    pm.getSegment(0f, pm.length * fraction, out, true)
    drawPath(
        out, brush, alpha = alpha,
        style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/** আইকনের হুবহু pathData ও গ্রেডিয়েন্ট (viewport 108x108) */
private class IconShapes {
    val lobeUR = svgPath("M54,39 C64,39 73,45 75,55 L50,58 Z")
    val aqua = svgPath("M48,75.5 L77.5,75.5 C84.8,75.5 90.5,69.8 90.5,62.5 C90.5,55.2 84.5,49.5 77.0,49.5 C70.5,49.5 65.0,53.8 63.2,59.8 L48,75.5 Z")
    val gloss = svgPath("M68,54 C74,50 83,52 87,58 C82,54 74,53 68,54 Z")
    val left = svgPath("M34,43.5 C23.2,43.5 14.5,52.0 14.5,62.5 C14.5,71.5 21.5,78.5 31.0,78.5 L48,62 Z")
    val sphere = svgPath("M46.5,27.0 C35.5,27.0 26.8,35.8 26.8,46.8 C26.8,57.8 35.5,66.5 46.5,66.5 C57.5,66.5 66.2,57.8 66.2,46.8 C66.2,35.8 57.5,27.0 46.5,27.0 Z")
    val ribbon = svgPath("M14.5,60.5 C14.8,74.8 26.5,81.5 41.5,78.5 C52.5,76.2 63.5,68.5 72.5,58.5 C76.8,53.5 76.5,45.5 71.2,41.5 C66.0,37.8 58.2,39.2 53.0,44.2 C43.5,53.5 34.5,62.5 25.5,65.2 C19.5,67.0 15.2,64.5 14.5,60.5 Z")
    val arrow1 = svgPath("M16.0,63.0 C20.5,72.5 31.5,72.5 45.5,58.5 L42.5,55.5 L53.5,51.5 L51.0,62.5 L48.0,59.5 C32.5,75.0 19.5,75.5 16.0,63.0 Z")
    val arrow2 = svgPath("M24.0,78.0 C36.5,80.5 49.5,71.5 60.0,53.5 L55.5,50.5 L67.5,43.0 L69.5,56.5 L65.0,54.0 C53.5,73.5 38.5,82.0 24.0,78.0 Z")

    // সিগনাল আর্ক — তীরের দিক (৬০°) বরাবর
    val arc1 = svgPath("M66.4,34.6 A9,9 0 0,1 75.4,39.8")
    val arc2 = svgPath("M65.8,27.5 A16.2,16.2 0 0,1 81.9,36.8")
    val arc3 = svgPath("M65.2,20.2 A23.5,23.5 0 0,1 88.5,33.7")

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

// ───────────────────────── Splash screen ─────────────────────────

@Composable
fun StartupSplashScreen(onFinished: () -> Unit) {
    val shapes = remember { IconShapes() }
    val pm = remember { PathMeasure() }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 2100, easing = LinearEasing))
        delay(350)
        onFinished()
    }

    val t = progress.value
    val textAlpha = seg(t, 0.72f, 0.95f)
    val logoScale = 0.92f + 0.08f * seg(t, 0f, 0.9f)

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF070B14)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Canvas(
                modifier = Modifier
                    .size(150.dp)
                    .graphicsLayer { scaleX = logoScale; scaleY = logoScale }
            ) {
                // আইকনের কনটেন্ট প্রায় ৮৪ ইউনিট চওড়া — সেটাই ক্যানভাস জুড়ে বসানো হচ্ছে
                val s = size.width / 84f
                translate(
                    left = size.width / 2f - 52.5f * s,
                    top = size.height / 2f - 50.5f * s
                ) {
                    scale(scale = s, pivot = Offset.Zero) {

                        // ১. আউটলাইন স্কেচ (০ → ০.৩৫), ফিল আসার সাথে মিলিয়ে যায়
                        val sketch = seg(t, 0f, 0.35f)
                        val outlineAlpha = 1f - seg(t, 0.28f, 0.5f)
                        listOf(shapes.lobeUR, shapes.aqua, shapes.left, shapes.sphere).forEach {
                            drawTrimmed(it, pm, sketch, shapes.bOutline, 1.4f, outlineAlpha)
                        }

                        // ২. গ্রেডিয়েন্ট ফিল, ক্রমানুসারে
                        drawPath(shapes.lobeUR, shapes.bUR, alpha = seg(t, 0.28f, 0.45f))
                        drawPath(shapes.aqua, shapes.bAqua, alpha = seg(t, 0.30f, 0.50f))
                        drawPath(shapes.gloss, Color.White, alpha = 0.4f * seg(t, 0.48f, 0.58f))
                        drawPath(shapes.left, shapes.bLeft, alpha = seg(t, 0.22f, 0.42f))
                        drawPath(shapes.sphere, shapes.bSphere, alpha = seg(t, 0.25f, 0.46f))

                        // ৩. সাদা রিবন — বাম থেকে ডানে উন্মোচন
                        val ribbonP = seg(t, 0.42f, 0.65f)
                        if (ribbonP > 0f) {
                            clipRect(left = 0f, top = 0f, right = 14f + 80f * ribbonP, bottom = 108f) {
                                drawPath(shapes.ribbon, shapes.bRibbon)
                            }
                        }

                        // ৪. তীর দুটি — একে একে বেরিয়ে আসে
                        val a1 = seg(t, 0.55f, 0.74f)
                        val a2 = seg(t, 0.62f, 0.80f)
                        if (a1 > 0f) clipRect(0f, 0f, 14f + 62f * a1, 108f) {
                            drawPath(shapes.arrow1, shapes.bArrow1)
                        }
                        if (a2 > 0f) clipRect(0f, 0f, 14f + 62f * a2, 108f) {
                            drawPath(shapes.arrow2, shapes.bArrow2)
                        }

                        // ৫. তিনটি সিগনাল আর্ক — ভেতর থেকে বাইরে আঁকা হয়
                        listOf(shapes.arc1, shapes.arc2, shapes.arc3).forEachIndexed { i, arc ->
                            val p = seg(t, 0.68f + i * 0.07f, 0.84f + i * 0.07f)
                            drawTrimmed(arc, pm, p, shapes.bArc, 4.5f)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.app_name),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                modifier = Modifier
                    .offset(y = (8f * (1f - textAlpha)).dp)
                    .alpha(textAlpha)
            )

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = "Developer: Yasin Adnan",
                color = Color(0xFF38BDF8).copy(alpha = 0.8f),
                fontSize = 11.sp,
                letterSpacing = 0.4.sp,
                modifier = Modifier
                    .offset(y = (8f * (1f - textAlpha)).dp)
                    .alpha(textAlpha)
            )
        }
    }
}

// ───────────────────────── Update dialog ─────────────────────────

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
