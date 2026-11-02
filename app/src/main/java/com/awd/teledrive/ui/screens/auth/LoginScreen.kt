package com.awd.teledrive.ui.screens.auth

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.awd.teledrive.R
import kotlinx.coroutines.delay

data class CountryOption(
    val name: String,
    val dialCode: String,
    val flag: String
)

private val COUNTRY_LIST = listOf(
    CountryOption("Bangladesh", "880", "🇧🇩"),
    CountryOption("India", "91", "🇮🇳"),
    CountryOption("Pakistan", "92", "🇵🇰"),
    CountryOption("Saudi Arabia", "966", "🇸🇦"),
    CountryOption("United Arab Emirates", "971", "🇦🇪"),
    CountryOption("Malaysia", "60", "🇲🇾"),
    CountryOption("United States", "1", "🇺🇸"),
    CountryOption("United Kingdom", "44", "🇬🇧"),
    CountryOption("Indonesia", "62", "🇮🇩")
)

private class BrandMarkVector {
    val lobeUR = PathParser().parsePathString("M54,39 C64,39 73,45 75,55 L50,58 Z").toPath()
    val aqua = PathParser().parsePathString("M48,75.5 L77.5,75.5 C84.8,75.5 90.5,69.8 90.5,62.5 C90.5,55.2 84.5,49.5 77.0,49.5 C70.5,49.5 65.0,53.8 63.2,59.8 L48,75.5 Z").toPath()
    val gloss = PathParser().parsePathString("M68,54 C74,50 83,52 87,58 C82,54 74,53 68,54 Z").toPath()
    val left = PathParser().parsePathString("M34,43.5 C23.2,43.5 14.5,52.0 14.5,62.5 C14.5,71.5 21.5,78.5 31.0,78.5 L48,62 Z").toPath()
    val sphere = PathParser().parsePathString("M46.5,27.0 C35.5,27.0 26.8,35.8 26.8,46.8 C26.8,57.8 35.5,66.5 46.5,66.5 C57.5,66.5 66.2,57.8 66.2,46.8 C66.2,35.8 57.5,27.0 46.5,27.0 Z").toPath()
    val ribbon = PathParser().parsePathString("M14.5,60.5 C14.8,74.8 26.5,81.5 41.5,78.5 C52.5,76.2 63.5,68.5 72.5,58.5 C76.8,53.5 76.5,45.5 71.2,41.5 C66.0,37.8 58.2,39.2 53.0,44.2 C43.5,53.5 34.5,62.5 25.5,65.2 C19.5,67.0 15.2,64.5 14.5,60.5 Z").toPath()
    val arrow1 = PathParser().parsePathString("M16.0,63.0 C20.5,72.5 31.5,72.5 45.5,58.5 L42.5,55.5 L53.5,51.5 L51.0,62.5 L48.0,59.5 C32.5,75.0 19.5,75.5 16.0,63.0 Z").toPath()
    val arrow2 = PathParser().parsePathString("M24.0,78.0 C36.5,80.5 49.5,71.5 60.0,53.5 L55.5,50.5 L67.5,43.0 L69.5,56.5 L65.0,54.0 C53.5,73.5 38.5,82.0 24.0,78.0 Z").toPath()
    val arc1 = PathParser().parsePathString("M66.4,34.6 A9,9 0 0,1 75.4,39.8").toPath()
    val arc2 = PathParser().parsePathString("M65.8,27.5 A16.2,16.2 0 0,1 81.9,36.8").toPath()
    val arc3 = PathParser().parsePathString("M65.2,20.2 A23.5,23.5 0 0,1 88.5,33.7").toPath()

    val bUR = Brush.linearGradient(0f to Color(0xFF2CE0FF), 1f to Color(0xFF0099F0), start = Offset(54f, 39f), end = Offset(75f, 56f))
    val bAqua = Brush.linearGradient(0f to Color(0xFF48F7E8), 0.55f to Color(0xFF00D2DC), 1f to Color(0xFF00A2C2), start = Offset(65f, 49f), end = Offset(88f, 76f))
    val bLeft = Brush.linearGradient(0f to Color(0xFF0095FF), 0.55f to Color(0xFF0055E0), 1f to Color(0xFF0033B8), start = Offset(16f, 44f), end = Offset(36f, 78f))
    val bSphere = Brush.linearGradient(0f to Color(0xFF28D8FF), 0.5f to Color(0xFF0080FF), 1f to Color(0xFF0040C8), start = Offset(36f, 28f), end = Offset(58f, 64f))
    val bRibbon = Brush.linearGradient(0f to Color(0xFF0078FF), 0.25f to Color(0xFF88DCFF), 0.55f to Color(0xFFE8F8FF), 1f to Color(0xFFFFFFFF), start = Offset(15f, 76f), end = Offset(72f, 42f))
    val bArrow1 = Brush.linearGradient(0f to Color(0xFF0055D4), 1f to Color(0xFF00B4FF), start = Offset(16f, 72f), end = Offset(53f, 51f))
    val bArrow2 = Brush.linearGradient(0f to Color(0xFF0038B8), 0.5f to Color(0xFF0078F0), 1f to Color(0xFF00A8FF), start = Offset(24f, 79f), end = Offset(68f, 43f))
    val bArc = Brush.linearGradient(0f to Color(0xFF18B8FF), 1f to Color(0xFF0055D4), start = Offset(65f, 21f), end = Offset(89f, 43f))

    // ড্রয়িং এনিমেশনের সময় আউটলাইন আঁকার ব্রাশ
    val bTrace = Brush.linearGradient(
        0f to Color(0xFF2CE0FF),
        1f to Color.White,
        start = Offset(14f, 80f),
        end = Offset(90f, 20f)
    )

    // যে ক্রমে আকারগুলো "আঁকা" হবে
    val traceOrder: List<Path> = listOf(left, sphere, lobeUR, aqua, ribbon, arrow1, arrow2)

    private val measurer = PathMeasure()
    private val segment = Path()

    /** একটি পাথের শুরু থেকে [progress] অংশ পর্যন্ত স্ট্রোক আঁকে। */
    fun drawTrimmed(
        scope: DrawScope,
        path: Path,
        progress: Float,
        brush: Brush,
        width: Float,
        alpha: Float = 1f
    ) {
        if (progress <= 0f || alpha <= 0f) return
        measurer.setPath(path, false)
        segment.rewind()
        measurer.getSegment(0f, measurer.length * progress.coerceAtMost(1f), segment, true)
        scope.drawPath(
            path = segment,
            brush = brush,
            alpha = alpha,
            style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun FloatingBrandMark(modifier: Modifier = Modifier) {
    val shapes = remember { BrandMarkVector() }

    // 0 → 1 : আউটলাইন আঁকা → রং ভরা → ওয়াইফাই আর্ক আঁকা
    val drawProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        drawProgress.animateTo(1f, tween(2400, easing = LinearEasing))
    }

    val transition = rememberInfiniteTransition(label = "LogoPulse")
    val floatOffset by transition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "FloatY"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // নরম অ্যাম্বিয়েন্ট গ্লো — আঁকা শেষের দিকে ধীরে ধীরে জ্বলে ওঠে
        Canvas(modifier = Modifier.size(150.dp)) {
            val glow = ((drawProgress.value - 0.35f) / 0.5f).coerceIn(0f, 1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF0A8CFF).copy(alpha = 0.34f),
                        Color(0xFF2CE0FF).copy(alpha = 0.10f),
                        Color.Transparent
                    )
                ),
                radius = size.minDimension / 1.8f,
                alpha = glow
            )
        }

        Canvas(
            modifier = Modifier
                .size(112.dp)
                .graphicsLayer {
                    translationY = floatOffset
                    val pop = 0.92f + 0.08f * FastOutSlowInEasing.transform(drawProgress.value)
                    scaleX = pop
                    scaleY = pop
                }
        ) {
            val t = drawProgress.value
            val fillAlpha = ((t - 0.50f) / 0.32f).coerceIn(0f, 1f)
            val outlineAlpha = 1f - fillAlpha

            val s = size.width / 82f
            translate(
                left = size.width / 2f - 52f * s,
                top = size.height / 2f - 50f * s
            ) {
                scale(scale = s, pivot = Offset.Zero) {
                    // ধাপ ১: আকারগুলোর আউটলাইন একে একে আঁকা হয়
                    if (outlineAlpha > 0f) {
                        shapes.traceOrder.forEachIndexed { i, path ->
                            val start = i * 0.045f
                            val p = FastOutSlowInEasing.transform(((t - start) / 0.38f).coerceIn(0f, 1f))
                            shapes.drawTrimmed(this, path, p, shapes.bTrace, 1.6f, outlineAlpha)
                        }
                    }

                    // ধাপ ২: আউটলাইনের ভেতরে গ্রেডিয়েন্ট রং ভরে ওঠে
                    if (fillAlpha > 0f) {
                        drawPath(shapes.lobeUR, shapes.bUR, alpha = fillAlpha)
                        drawPath(shapes.aqua, shapes.bAqua, alpha = fillAlpha)
                        drawPath(shapes.gloss, Color.White, alpha = 0.4f * fillAlpha)
                        drawPath(shapes.left, shapes.bLeft, alpha = fillAlpha)
                        drawPath(shapes.sphere, shapes.bSphere, alpha = fillAlpha)
                        drawPath(shapes.ribbon, shapes.bRibbon, alpha = fillAlpha)
                        drawPath(shapes.arrow1, shapes.bArrow1, alpha = fillAlpha)
                        drawPath(shapes.arrow2, shapes.bArrow2, alpha = fillAlpha)
                    }

                    // ধাপ ৩: সিগন্যাল আর্কগুলো ভেতর থেকে বাইরে আঁকা হয়
                    listOf(shapes.arc1, shapes.arc2, shapes.arc3).forEachIndexed { k, arc ->
                        val start = 0.66f + k * 0.07f
                        val p = FastOutSlowInEasing.transform(((t - start) / 0.20f).coerceIn(0f, 1f))
                        shapes.drawTrimmed(this, arc, p, shapes.bArc, 4.5f)
                    }
                }
            }
        }
    }
}

/** বর্তমান ধাপ বোঝানোর ছোট ইন্ডিকেটর (ফোন → কোড → পাসওয়ার্ড) */
@Composable
private fun StepIndicator(
    current: Int,
    activeColor: Color,
    inactiveColor: Color,
    total: Int = 3
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { i ->
            val barWidth by animateDpAsState(
                targetValue = if (i == current) 28.dp else 8.dp,
                animationSpec = tween(300),
                label = "StepWidth"
            )
            val barColor by animateColorAsState(
                targetValue = if (i <= current) activeColor else inactiveColor,
                animationSpec = tween(300),
                label = "StepColor"
            )
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(barWidth)
                    .clip(CircleShape)
                    .background(barColor)
            )
        }
    }
}

/** তিনটি ধাপেই ব্যবহৃত একই গ্রেডিয়েন্ট বাটন */
@Composable
private fun GradientButton(
    text: String,
    enabled: Boolean,
    isProcessing: Boolean,
    onClick: () -> Unit,
    gradientStart: Color,
    gradientEnd: Color,
    trailingIcon: ImageVector? = null
) {
    val showGradient = enabled || isProcessing
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.White.copy(alpha = 0.07f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (showGradient) {
                        Modifier.background(
                            Brush.horizontalGradient(colors = listOf(gradientStart, gradientEnd))
                        )
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            // উপরের দিকে হালকা আলোর আভা
            if (showGradient) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.18f), Color.Transparent)
                            )
                        )
                )
            }
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.5.dp
                )
            } else {
                val contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = text,
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                    if (trailingIcon != null) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = trailingIcon,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val authError by viewModel.authError.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val submittedPhone by viewModel.submittedPhone.collectAsState()
    val codeResentMessage by viewModel.codeResentMessage.collectAsState()

    val context = LocalContext.current

    LaunchedEffect(uiState) {
        if (uiState is LoginUiState.Success) {
            onLoginSuccess()
        }
    }

    LaunchedEffect(codeResentMessage) {
        codeResentMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearResentMessage()
        }
    }

    LoginContent(
        uiState = uiState,
        authError = authError,
        isProcessing = isProcessing,
        submittedPhone = submittedPhone,
        onLoginSubmit = viewModel::onLoginSubmit,
        onCodeSubmit = viewModel::onCodeSubmit,
        onPasswordSubmit = viewModel::onPasswordSubmit,
        onResendCode = viewModel::onResendCode,
        onBackToPhoneInput = viewModel::onBackToPhoneInput
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginContent(
    uiState: LoginUiState,
    authError: String?,
    isProcessing: Boolean,
    submittedPhone: String = "",
    onLoginSubmit: (String, String, String) -> Unit,
    onCodeSubmit: (String) -> Unit,
    onPasswordSubmit: (String) -> Unit,
    onResendCode: () -> Unit = {},
    onBackToPhoneInput: () -> Unit = {}
) {
    val defaultApiId = "94575"
    val defaultApiHash = "a3406de8d171bb422bb6ddf3bbd800e2"

    var selectedCountry by remember { mutableStateOf(COUNTRY_LIST.first()) }
    var countryCode by remember { mutableStateOf("880") }
    var localPhone by remember { mutableStateOf("") }
    var showCountryDialog by remember { mutableStateOf(false) }

    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var resendTimer by remember { mutableIntStateOf(60) }
    val scrollState = rememberScrollState()

    val canGoBackToPhone = uiState is LoginUiState.EnteringCode || uiState is LoginUiState.EnteringPassword

    BackHandler(enabled = canGoBackToPhone) {
        code = ""
        password = ""
        onBackToPhoneInput()
    }

    LaunchedEffect(uiState) {
        if (uiState is LoginUiState.EnteringCode) {
            resendTimer = 60
            while (resendTimer > 0) {
                delay(1000)
                resendTimer--
            }
        }
    }

    val bgDark = Color(0xFF08070D)
    val cardSurface = Color(0xFF15131D)
    val fieldSurface = Color(0xFF1F1C29)
    val borderSubtle = Color.White.copy(alpha = 0.09f)
    val accentCyan = Color(0xFF2CE0FF)
    val accentOrange = Color(0xFFFF5A1F)
    val accentOrangeEnd = Color(0xFFFF7A2F)
    val textMuted = Color(0xFFA9A5B6)

    // লোগো আঁকা শেষ হওয়ার পর শিরোনাম ও কার্ড ধীরে ধীরে ভেসে ওঠে
    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }
    val headerAlpha by animateFloatAsState(
        targetValue = if (appear) 1f else 0f,
        animationSpec = tween(600, delayMillis = 1500),
        label = "HeaderAlpha"
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (appear) 1f else 0f,
        animationSpec = tween(650, delayMillis = 1750, easing = FastOutSlowInEasing),
        label = "CardAlpha"
    )

    val stepIndex = when (uiState) {
        is LoginUiState.EnteringCode -> 1
        is LoginUiState.EnteringPassword -> 2
        else -> 0
    }

    if (showCountryDialog) {
        var searchCountry by remember { mutableStateOf("") }
        val filteredCountries = remember(searchCountry) {
            if (searchCountry.isBlank()) COUNTRY_LIST
            else COUNTRY_LIST.filter {
                it.name.contains(searchCountry, ignoreCase = true) || it.dialCode.contains(searchCountry)
            }
        }

        AlertDialog(
            onDismissRequest = { showCountryDialog = false },
            title = { Text("Select Country", color = Color.White, fontWeight = FontWeight.Bold) },
            containerColor = Color(0xFF181522),
            shape = RoundedCornerShape(28.dp),
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = searchCountry,
                        onValueChange = { searchCountry = it },
                        placeholder = { Text("Search country or code...", color = Color.Gray) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = accentCyan,
                            unfocusedBorderColor = borderSubtle
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(filteredCountries) { country ->
                            ListItem(
                                headlineContent = { Text("${country.flag}  ${country.name}", color = Color.White) },
                                trailingContent = { Text("+${country.dialCode}", fontWeight = FontWeight.Bold, color = accentCyan) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        selectedCountry = country
                                        countryCode = country.dialCode
                                        showCountryDialog = false
                                    }
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCountryDialog = false }) {
                    Text(stringResource(R.string.close), color = accentCyan, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    val submitFormattedPhone = {
        val cleanCode = countryCode.filter { it.isDigit() }
        val cleanLocal = localPhone.filter { it.isDigit() }.let {
            if (cleanCode == "880" && it.startsWith("0")) it.removePrefix("0") else it
        }
        val fullPhone = "+$cleanCode$cleanLocal"
        onLoginSubmit(defaultApiId, defaultApiHash, fullPhone)
    }

    Scaffold(
        containerColor = bgDark
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgDark)
                .padding(padding)
        ) {
            // ডুয়াল অ্যাম্বিয়েন্ট ব্যাকগ্রাউন্ড গ্লো
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF0A8CFF).copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(size.width * 0.88f, size.height * 0.08f),
                        radius = size.width * 0.75f
                    ),
                    center = Offset(size.width * 0.88f, size.height * 0.08f),
                    radius = size.width * 0.75f
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFF5A1F).copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(size.width * 0.12f, size.height * 0.88f),
                        radius = size.width * 0.75f
                    ),
                    center = Offset(size.width * 0.12f, size.height * 0.88f),
                    radius = size.width * 0.75f
                )
            }

            if (canGoBackToPhone) {
                Surface(
                    onClick = {
                        code = ""
                        password = ""
                        onBackToPhoneInput()
                    },
                    shape = CircleShape,
                    color = fieldSurface,
                    border = BorderStroke(1.dp, borderSubtle),
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(16.dp)
                        .size(42.dp)
                        .align(Alignment.TopStart)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val minContentHeight = maxHeight

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .imePadding()
                        .heightIn(min = minContentHeight)
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Spacer(modifier = Modifier.height(16.dp))

                    // ড্রয়িং এনিমেশনসহ ভেক্টর লোগো
                    FloatingBrandMark()

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.graphicsLayer { alpha = headerAlpha }
                    ) {
                        Text(
                            text = "MyCloud",
                            style = TextStyle(
                                brush = Brush.horizontalGradient(
                                    listOf(Color.White, Color(0xFFBFF4FF))
                                ),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.6.sp
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = when (uiState) {
                                is LoginUiState.EnteringCode -> "টেলিগ্রাম অ্যাপে পাঠানো ৫ সংখ্যার কোডটি দিন"
                                is LoginUiState.EnteringPassword -> "আপনার ক্লাউড ২-স্টেপ পাসওয়ার্ডটি দিন"
                                else -> "টেলিগ্রাম নাম্বার দিয়ে আনলিমিটেড ক্লাউডে প্রবেশ করুন"
                            },
                            color = textMuted,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    AnimatedVisibility(
                        visible = authError != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Surface(
                            color = Color(0xFFEF4444).copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 18.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = Color(0xFFF87171),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = authError ?: "",
                                    color = Color(0xFFFECACA),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    // মেইন কার্ড
                    Surface(
                        color = cardSurface.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.05f))
                            )
                        ),
                        shadowElevation = 24.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                alpha = cardAlpha
                                translationY = (1f - cardAlpha) * 36.dp.toPx()
                            }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(modifier = Modifier.height(20.dp))

                            StepIndicator(
                                current = stepIndex,
                                activeColor = accentOrange,
                                inactiveColor = Color.White.copy(alpha = 0.12f)
                            )

                            AnimatedContent(
                                targetState = uiState,
                                transitionSpec = {
                                    (slideInHorizontally(
                                        animationSpec = tween(260, easing = FastOutSlowInEasing),
                                        initialOffsetX = { it / 3 }
                                    ) + fadeIn(tween(220))).togetherWith(
                                        slideOutHorizontally(
                                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                                            targetOffsetX = { -it / 3 }
                                        ) + fadeOut(tween(180))
                                    )
                                },
                                label = "AuthStepTransition"
                            ) { targetState ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 22.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    when (targetState) {
                                        is LoginUiState.InitialSetup -> {
                                            // Country Selector Row
                                            Surface(
                                                onClick = { showCountryDialog = true },
                                                color = fieldSurface,
                                                shape = RoundedCornerShape(18.dp),
                                                border = BorderStroke(1.dp, borderSubtle),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 18.dp, vertical = 16.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(text = selectedCountry.flag, fontSize = 20.sp)
                                                        Spacer(modifier = Modifier.width(12.dp))
                                                        Text(
                                                            text = selectedCountry.name,
                                                            color = Color.White,
                                                            fontSize = 15.5.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = "+${selectedCountry.dialCode}",
                                                            color = accentCyan,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Icon(
                                                            imageVector = Icons.Default.KeyboardArrowDown,
                                                            contentDescription = null,
                                                            tint = textMuted
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(14.dp))

                                            // Unified Phone Input Field (একটি বক্সে কোড ও নাম্বার)
                                            Surface(
                                                color = fieldSurface,
                                                shape = RoundedCornerShape(18.dp),
                                                border = BorderStroke(
                                                    width = 1.dp,
                                                    color = if (localPhone.isNotEmpty()) accentCyan.copy(alpha = 0.5f) else borderSubtle
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(58.dp)
                                                        .padding(horizontal = 16.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "+",
                                                        color = accentCyan,
                                                        fontSize = 17.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    BasicTextField(
                                                        value = countryCode,
                                                        onValueChange = { input ->
                                                            val digits = input.filter { it.isDigit() }.take(4)
                                                            countryCode = digits
                                                            COUNTRY_LIST.find { it.dialCode == digits }?.let { matched ->
                                                                selectedCountry = matched
                                                            }
                                                        },
                                                        singleLine = true,
                                                        textStyle = TextStyle(
                                                            color = Color.White,
                                                            fontSize = 17.sp,
                                                            fontWeight = FontWeight.Bold
                                                        ),
                                                        cursorBrush = SolidColor(accentCyan),
                                                        keyboardOptions = KeyboardOptions(
                                                            keyboardType = KeyboardType.Number,
                                                            imeAction = ImeAction.Next
                                                        ),
                                                        modifier = Modifier.width(46.dp)
                                                    )

                                                    Box(
                                                        modifier = Modifier
                                                            .width(1.dp)
                                                            .height(26.dp)
                                                            .background(Color.White.copy(alpha = 0.14f))
                                                    )

                                                    Spacer(modifier = Modifier.width(14.dp))

                                                    Box(
                                                        modifier = Modifier.weight(1f),
                                                        contentAlignment = Alignment.CenterStart
                                                    ) {
                                                        if (localPhone.isEmpty()) {
                                                            Text(
                                                                text = "17XX-XXXXXX",
                                                                color = Color.White.copy(alpha = 0.28f),
                                                                fontSize = 17.sp,
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                        }
                                                        BasicTextField(
                                                            value = localPhone,
                                                            onValueChange = { input ->
                                                                localPhone = input.filter { it.isDigit() }.take(15)
                                                            },
                                                            singleLine = true,
                                                            textStyle = TextStyle(
                                                                color = Color.White,
                                                                fontSize = 17.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                letterSpacing = 0.8.sp
                                                            ),
                                                            cursorBrush = SolidColor(accentCyan),
                                                            keyboardOptions = KeyboardOptions(
                                                                keyboardType = KeyboardType.Phone,
                                                                imeAction = ImeAction.Done
                                                            ),
                                                            keyboardActions = KeyboardActions(
                                                                onDone = {
                                                                    if (countryCode.isNotBlank() && localPhone.length >= 6) {
                                                                        submitFormattedPhone()
                                                                    }
                                                                }
                                                            ),
                                                            modifier = Modifier.fillMaxWidth()
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(22.dp))

                                            val canSubmit = !isProcessing && countryCode.isNotBlank() && localPhone.length >= 6

                                            GradientButton(
                                                text = "Continue",
                                                enabled = canSubmit,
                                                isProcessing = isProcessing,
                                                onClick = submitFormattedPhone,
                                                gradientStart = accentOrange,
                                                gradientEnd = accentOrangeEnd,
                                                trailingIcon = Icons.AutoMirrored.Filled.ArrowForward
                                            )
                                        }

                                        is LoginUiState.EnteringCode -> {
                                            if (submittedPhone.isNotEmpty()) {
                                                Surface(
                                                    onClick = {
                                                        code = ""
                                                        onBackToPhoneInput()
                                                    },
                                                    shape = CircleShape,
                                                    color = fieldSurface,
                                                    border = BorderStroke(1.dp, borderSubtle),
                                                    modifier = Modifier.padding(bottom = 20.dp)
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                                    ) {
                                                        Text(
                                                            text = submittedPhone,
                                                            color = Color.White,
                                                            fontSize = 14.5.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Icon(
                                                            imageVector = Icons.Default.Edit,
                                                            contentDescription = null,
                                                            tint = accentCyan,
                                                            modifier = Modifier.size(15.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            // কোড ঘরে ঢুকলেই কিবোর্ড খুলে যায়
                                            val codeFocusRequester = remember { FocusRequester() }
                                            LaunchedEffect(Unit) {
                                                delay(300)
                                                runCatching { codeFocusRequester.requestFocus() }
                                            }

                                            // ৫-ডিজিট OTP বক্স
                                            BasicTextField(
                                                value = code,
                                                onValueChange = { input ->
                                                    val filtered = input.filter { it.isDigit() }.take(5)
                                                    code = filtered
                                                    if (filtered.length == 5 && !isProcessing) {
                                                        onCodeSubmit(filtered)
                                                    }
                                                },
                                                keyboardOptions = KeyboardOptions(
                                                    keyboardType = KeyboardType.NumberPassword,
                                                    imeAction = ImeAction.Done
                                                ),
                                                keyboardActions = KeyboardActions(
                                                    onDone = { if (code.length == 5) onCodeSubmit(code) }
                                                ),
                                                modifier = Modifier.focusRequester(codeFocusRequester),
                                                decorationBox = {
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        repeat(5) { index ->
                                                            val char = code.getOrNull(index)?.toString() ?: ""
                                                            val isFocused = code.length == index
                                                            Box(
                                                                modifier = Modifier
                                                                    .weight(1f)
                                                                    .height(58.dp)
                                                                    .clip(RoundedCornerShape(16.dp))
                                                                    .background(
                                                                        if (isFocused) accentOrange.copy(alpha = 0.08f) else fieldSurface
                                                                    )
                                                                    .border(
                                                                        width = if (isFocused || char.isNotEmpty()) 1.5.dp else 1.dp,
                                                                        color = when {
                                                                            isFocused -> accentOrange
                                                                            char.isNotEmpty() -> accentCyan.copy(alpha = 0.7f)
                                                                            else -> borderSubtle
                                                                        },
                                                                        shape = RoundedCornerShape(16.dp)
                                                                    ),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Text(
                                                                    text = char,
                                                                    color = Color.White,
                                                                    fontSize = 22.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            )

                                            Spacer(modifier = Modifier.height(22.dp))

                                            val canVerify = code.length == 5 && !isProcessing

                                            GradientButton(
                                                text = "Verify & Sign In",
                                                enabled = canVerify,
                                                isProcessing = isProcessing,
                                                onClick = { onCodeSubmit(code) },
                                                gradientStart = accentOrange,
                                                gradientEnd = accentOrangeEnd
                                            )

                                            Spacer(modifier = Modifier.height(12.dp))

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                TextButton(
                                                    onClick = {
                                                        code = ""
                                                        onBackToPhoneInput()
                                                    }
                                                ) {
                                                    Text(
                                                        text = "Change Number",
                                                        color = textMuted,
                                                        fontSize = 13.sp
                                                    )
                                                }

                                                TextButton(
                                                    onClick = {
                                                        onResendCode()
                                                        resendTimer = 60
                                                    },
                                                    enabled = resendTimer == 0 && !isProcessing
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = null,
                                                        tint = if (resendTimer == 0) accentCyan else Color.Gray,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = if (resendTimer > 0) "Resend (${resendTimer}s)" else "Resend Code",
                                                        color = if (resendTimer == 0) accentCyan else Color.Gray,
                                                        fontSize = 13.sp
                                                    )
                                                }
                                            }
                                        }

                                        is LoginUiState.EnteringPassword -> {
                                            OutlinedTextField(
                                                value = password,
                                                onValueChange = { password = it },
                                                placeholder = {
                                                    Text(
                                                        "2FA Cloud Password",
                                                        color = Color.White.copy(alpha = 0.35f)
                                                    )
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                visualTransformation = if (isPasswordVisible) {
                                                    VisualTransformation.None
                                                } else {
                                                    PasswordVisualTransformation()
                                                },
                                                keyboardOptions = KeyboardOptions(
                                                    keyboardType = KeyboardType.Password,
                                                    imeAction = ImeAction.Done
                                                ),
                                                keyboardActions = KeyboardActions(
                                                    onDone = { if (password.isNotBlank()) onPasswordSubmit(password) }
                                                ),
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Default.Lock,
                                                        contentDescription = null,
                                                        tint = accentCyan
                                                    )
                                                },
                                                trailingIcon = {
                                                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                                        Icon(
                                                            imageVector = if (isPasswordVisible) {
                                                                Icons.Default.VisibilityOff
                                                            } else {
                                                                Icons.Default.Visibility
                                                            },
                                                            contentDescription = null,
                                                            tint = Color.White.copy(alpha = 0.6f)
                                                        )
                                                    }
                                                },
                                                singleLine = true,
                                                shape = RoundedCornerShape(18.dp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = accentCyan,
                                                    unfocusedBorderColor = borderSubtle,
                                                    focusedContainerColor = fieldSurface,
                                                    unfocusedContainerColor = fieldSurface,
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            Spacer(modifier = Modifier.height(22.dp))

                                            val canUnlock = password.isNotBlank() && !isProcessing

                                            GradientButton(
                                                text = "Unlock Cloud",
                                                enabled = canUnlock,
                                                isProcessing = isProcessing,
                                                onClick = { onPasswordSubmit(password) },
                                                gradientStart = accentOrange,
                                                gradientEnd = accentOrangeEnd
                                            )
                                        }

                                        else -> {
                                            Spacer(modifier = Modifier.height(24.dp))
                                            CircularProgressIndicator(color = accentOrange)
                                            Spacer(modifier = Modifier.height(24.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.graphicsLayer { alpha = cardAlpha }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color(0xFF6E6A7C),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "End-to-End Encrypted • Powered by Telegram Cloud",
                                color = Color(0xFF6E6A7C),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "© 2026 Yasin Adnan. All rights reserved.",
                            color = Color(0xFF57536A),
                            fontSize = 11.5.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}
