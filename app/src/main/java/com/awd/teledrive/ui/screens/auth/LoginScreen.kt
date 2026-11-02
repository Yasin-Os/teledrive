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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.delay
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

// ──────────────────────────────────────────────────────────────
//  Telegram-style colour tokens
// ──────────────────────────────────────────────────────────────
private val TgBg = Color(0xFF1E2733)
private val TgBlue = Color(0xFF5EAEF0)      // focused outline, label, links
private val TgFab = Color(0xFF229AF0)       // round action button
private val TgBorder = Color(0xFF3A4859)    // idle outline
private val TgGray = Color(0xFF7F8C9A)      // subtitles / hints
private val TgError = Color(0xFFE5737A)

// ──────────────────────────────────────────────────────────────
//  সব লেখা এক জায়গায় — বাংলা করতে চাইলে শুধু এখানে বদলান
// ──────────────────────────────────────────────────────────────
private object LoginText {
    const val PHONE_TITLE = "Your phone number"
    const val PHONE_SUBTITLE = "Please confirm your country code and enter your phone number."
    const val PHONE_LABEL = "Phone number"
    const val COUNTRY_HINT = "Country"
    const val COUNTRY_WRONG = "Wrong country code"

    const val CODE_TITLE_FALLBACK = "Enter code"
    const val CODE_SUBTITLE = "We've sent the code to the Telegram app on your other device."
    const val RESEND = "Resend code"
    const val RESEND_IN = "Resend code in"

    const val PASSWORD_TITLE = "Your password"
    const val PASSWORD_SUBTITLE = "Two-Step Verification enabled. Your account is protected with an additional password."
    const val PASSWORD_LABEL = "Enter password"
    const val FORGOT = "Forgot password?"
    const val FORGOT_BODY = "Open Telegram on another device and go to Settings → Privacy and Security → Two-Step Verification. If you added a recovery email, you can use it to reset your password there."
    const val OK = "OK"

    const val PICKER_TITLE = "Choose a country"
    const val SEARCH = "Search"

    const val SECURITY = "End-to-End Encrypted • Powered by Telegram Cloud"
    const val COPYRIGHT = "© 2026 Yasin Adnan. All rights reserved."
}

// ──────────────────────────────────────────────────────────────
//  দেশের তালিকা: ISO কোড + ডায়াল কোড (পতাকা ও নাম নিজে থেকে তৈরি হয়)
// ──────────────────────────────────────────────────────────────
data class CountryOption(
    val name: String,
    val dialCode: String,
    val flag: String,
    val iso: String = ""
)

private const val DIAL_DATA = """
AF:93 AX:358 AL:355 DZ:213 AS:1684 AD:376 AO:244 AI:1264 AG:1268 AR:54 AM:374 AW:297
AU:61 AT:43 AZ:994 BS:1242 BH:973 BD:880 BB:1246 BY:375 BE:32 BZ:501 BJ:229 BM:1441
BT:975 BO:591 BQ:599 BA:387 BW:267 BR:55 IO:246 VG:1284 BN:673 BG:359 BF:226 BI:257
KH:855 CM:237 CA:1 CV:238 KY:1345 CF:236 TD:235 CL:56 CN:86 CX:61 CC:61 CO:57 KM:269
CG:242 CD:243 CK:682 CR:506 CI:225 HR:385 CU:53 CW:599 CY:357 CZ:420 DK:45 DJ:253
DM:1767 DO:1809 EC:593 EG:20 SV:503 GQ:240 ER:291 EE:372 SZ:268 ET:251 FK:500 FO:298
FJ:679 FI:358 FR:33 GF:594 PF:689 GA:241 GM:220 GE:995 DE:49 GH:233 GI:350 GR:30
GL:299 GD:1473 GP:590 GU:1671 GT:502 GG:44 GN:224 GW:245 GY:592 HT:509 HN:504 HK:852
HU:36 IS:354 IN:91 ID:62 IR:98 IQ:964 IE:353 IM:44 IL:972 IT:39 JM:1876 JP:81 JE:44
JO:962 KZ:7 KE:254 KI:686 XK:383 KW:965 KG:996 LA:856 LV:371 LB:961 LS:266 LR:231
LY:218 LI:423 LT:370 LU:352 MO:853 MG:261 MW:265 MY:60 MV:960 ML:223 MT:356 MH:692
MQ:596 MR:222 MU:230 YT:262 MX:52 FM:691 MD:373 MC:377 MN:976 ME:382 MS:1664 MA:212
MZ:258 MM:95 NA:264 NR:674 NP:977 NL:31 NC:687 NZ:64 NI:505 NE:227 NG:234 NU:683
NF:672 KP:850 MK:389 MP:1670 NO:47 OM:968 PK:92 PW:680 PS:970 PA:507 PG:675 PY:595
PE:51 PH:63 PL:48 PT:351 PR:1787 QA:974 RE:262 RO:40 RU:7 RW:250 WS:685 SM:378
ST:239 SA:966 SN:221 RS:381 SC:248 SL:232 SG:65 SX:1721 SK:421 SI:386 SB:677 SO:252
ZA:27 KR:82 SS:211 ES:34 LK:94 BL:590 SH:290 KN:1869 LC:1758 MF:590 PM:508 VC:1784
SD:249 SR:597 SJ:47 SE:46 CH:41 SY:963 TW:886 TJ:992 TZ:255 TH:66 TL:670 TG:228
TK:690 TO:676 TT:1868 TN:216 TR:90 TM:993 TC:1649 TV:688 UG:256 UA:380 AE:971 GB:44
US:1 VI:1340 UY:598 UZ:998 VU:678 VA:379 VE:58 VN:84 WF:681 EH:212 YE:967 ZM:260 ZW:263
"""

// একাধিক দেশের একই কোড হলে যেটি আগে ধরা হবে (যেমন +1 → US)
private val PRIMARY_ISO = setOf("US", "RU", "GB", "AU", "FI", "NO", "MA", "RE", "GP", "CW", "IT")

private fun flagEmoji(iso: String): String {
    val first = Character.codePointAt(iso, 0) - 'A'.code + 0x1F1E6
    val second = Character.codePointAt(iso, 1) - 'A'.code + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}

private val COUNTRY_LIST: List<CountryOption> by lazy {
    val collator = Collator.getInstance(Locale.ENGLISH)
    DIAL_DATA.trim().split(Regex("\\s+")).mapNotNull { token ->
        val parts = token.split(":")
        if (parts.size != 2 || parts[0].length != 2) return@mapNotNull null
        val iso = parts[0]
        val name = Locale.Builder().setRegion(iso).build()
            .getDisplayCountry(Locale.ENGLISH)
            .ifBlank { iso }
        CountryOption(name = name, dialCode = parts[1], flag = flagEmoji(iso), iso = iso)
    }.sortedWith { a, b -> collator.compare(a.name, b.name) }
}

private fun findCountryByCode(digits: String): CountryOption? {
    if (digits.isEmpty()) return null
    val matches = COUNTRY_LIST.filter { it.dialCode == digits }
    return matches.firstOrNull { it.iso in PRIMARY_ISO } ?: matches.firstOrNull()
}

private fun hasCountryWithPrefix(digits: String): Boolean =
    COUNTRY_LIST.any { it.dialCode.startsWith(digits) }

private fun sectionKey(name: String): Char {
    val plain = Normalizer.normalize(name, Normalizer.Form.NFD)
    return plain.firstOrNull { it.isLetter() }?.uppercaseChar() ?: '#'
}

// ──────────────────────────────────────────────────────────────
//  লোগো (ভেক্টর) + ড্রয়িং এনিমেশন
// ──────────────────────────────────────────────────────────────
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

    // আউটলাইন আঁকার ব্রাশ
    val bTrace = Brush.linearGradient(
        0f to Color(0xFF2CE0FF),
        1f to Color.White,
        start = Offset(14f, 80f),
        end = Offset(90f, 20f)
    )

    val traceOrder: List<Path> = listOf(left, sphere, lobeUR, aqua, ribbon, arrow1, arrow2)

    private val measurer = PathMeasure()
    private val segment = Path()

    /** পাথের শুরু থেকে [progress] অংশ পর্যন্ত স্ট্রোক আঁকে */
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
private fun FloatingBrandMark(modifier: Modifier = Modifier, logoSize: Dp = 96.dp) {
    val shapes = remember { BrandMarkVector() }

    // 0 → 1 : আউটলাইন আঁকা → রং ভরা → সিগন্যাল আর্ক আঁকা
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
        // নরম গ্লো
        Canvas(modifier = Modifier.size(logoSize * 1.35f)) {
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
                .size(logoSize)
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
                    // ধাপ ১: আউটলাইন একে একে আঁকা হয়
                    if (outlineAlpha > 0f) {
                        shapes.traceOrder.forEachIndexed { i, path ->
                            val start = i * 0.045f
                            val p = FastOutSlowInEasing.transform(((t - start) / 0.38f).coerceIn(0f, 1f))
                            shapes.drawTrimmed(this, path, p, shapes.bTrace, 1.6f, outlineAlpha)
                        }
                    }

                    // ধাপ ২: গ্রেডিয়েন্ট রং ভরে ওঠে
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

                    // ধাপ ৩: সিগন্যাল আর্ক ভেতর থেকে বাইরে আঁকা হয়
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

// ──────────────────────────────────────────────────────────────
//  টেলিগ্রামের মতো UI অংশগুলো
// ──────────────────────────────────────────────────────────────

/** টেলিগ্রামের "নচ" লেবেলসহ আউটলাইন বক্স (ফোন নাম্বার / পাসওয়ার্ড) */
@Composable
private fun NotchedBox(
    label: String,
    focused: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val color by animateColorAsState(
        targetValue = when {
            isError -> TgError
            focused -> TgBlue
            else -> TgBorder
        },
        animationSpec = tween(180),
        label = "NotchColor"
    )
    val borderWidth = if (focused || isError) 2.dp else 1.dp

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(58.dp)
                .border(borderWidth, color, RoundedCornerShape(12.dp))
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
        Text(
            text = label,
            color = if (isError) TgError else if (focused) TgBlue else TgGray,
            fontSize = 12.sp,
            modifier = Modifier
                .padding(start = 14.dp)
                .background(TgBg)
                .padding(horizontal = 4.dp)
        )
    }
}

/** টেলিগ্রামের নিচ-ডানের গোল অ্যাকশন বাটন */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TgFab(
    enabled: Boolean,
    isProcessing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color by animateColorAsState(
        targetValue = if (enabled || isProcessing) TgFab else TgFab.copy(alpha = 0.4f),
        animationSpec = tween(200),
        label = "FabColor"
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = color,
        contentColor = Color.White,
        shadowElevation = 6.dp,
        modifier = modifier.size(56.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.5.dp
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun StepHeader(title: String, subtitle: String) {
    Text(
        text = title,
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = subtitle,
        color = TgGray,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        textAlign = TextAlign.Center
    )
}

/** টেলিগ্রামের মতো পূর্ণ-স্ক্রিন দেশ নির্বাচন: সব দেশ, পতাকা, কোড, সার্চ, A–Z সেকশন */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CountryPickerDialog(
    selected: CountryOption?,
    onSelect: (CountryOption) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = TgBg, modifier = Modifier.fillMaxSize()) {
            var searching by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            val searchFocus = remember { FocusRequester() }

            val filtered = remember(query) {
                val q = query.trim()
                if (q.isEmpty()) COUNTRY_LIST
                else {
                    val digits = q.removePrefix("+")
                    COUNTRY_LIST.filter {
                        it.name.contains(q, ignoreCase = true) ||
                            it.iso.equals(q, ignoreCase = true) ||
                            (digits.isNotEmpty() && digits.all { c -> c.isDigit() } && it.dialCode.startsWith(digits))
                    }
                }
            }
            val grouped = remember(filtered) { filtered.groupBy { sectionKey(it.name) } }

            LaunchedEffect(searching) {
                if (searching) runCatching { searchFocus.requestFocus() }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (searching) {
                            searching = false
                            query = ""
                        } else onDismiss()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    if (searching) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(LoginText.SEARCH, color = TgGray, fontSize = 18.sp)
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                                cursorBrush = SolidColor(TgBlue),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocus)
                            )
                        }
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = TgGray)
                            }
                        }
                    } else {
                        Text(
                            text = LoginText.PICKER_TITLE,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        )
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                        }
                    }
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    grouped.forEach { (letter, list) ->
                        stickyHeader {
                            Text(
                                text = letter.toString(),
                                color = TgBlue,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TgBg)
                                    .padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                        items(list, key = { it.iso }) { country ->
                            val isSelected = selected?.iso == country.iso
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(country) }
                                    .padding(horizontal = 20.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = country.flag, fontSize = 24.sp)
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = country.name,
                                    color = if (isSelected) TgBlue else Color.White,
                                    fontSize = 16.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "+${country.dialCode}",
                                    color = if (isSelected) TgBlue else TgGray,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
//  স্ক্রিন
// ──────────────────────────────────────────────────────────────
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

    var selectedCountry by remember { mutableStateOf<CountryOption?>(findCountryByCode("880")) }
    var countryCode by remember { mutableStateOf("880") }
    var localPhone by remember { mutableStateOf("") }
    var showCountryDialog by remember { mutableStateOf(false) }
    var showForgotDialog by remember { mutableStateOf(false) }

    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var resendTimer by remember { mutableIntStateOf(60) }
    var resendKey by remember { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()

    var codeFieldFocused by remember { mutableStateOf(false) }
    var numberFieldFocused by remember { mutableStateOf(false) }
    var passwordFocused by remember { mutableStateOf(false) }

    val canGoBackToPhone = uiState is LoginUiState.EnteringCode || uiState is LoginUiState.EnteringPassword

    BackHandler(enabled = canGoBackToPhone) {
        code = ""
        password = ""
        onBackToPhoneInput()
    }

    // রিসেন্ড টাইমার (রিসেন্ড চাপলে আবার নতুন করে ৬০ সেকেন্ড গোনে)
    LaunchedEffect(uiState, resendKey) {
        if (uiState is LoginUiState.EnteringCode) {
            resendTimer = 60
            while (resendTimer > 0) {
                delay(1000)
                resendTimer--
            }
        }
    }

    // লোগো আঁকা শেষ হলে লেখা ও ফিল্ড ভেসে ওঠে, তারপর কিবোর্ড খোলে
    var appear by remember { mutableStateOf(false) }
    var logoFinished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        appear = true
        delay(1900)
        logoFinished = true
    }
    val contentAlpha by animateFloatAsState(
        targetValue = if (appear) 1f else 0f,
        animationSpec = tween(600, delayMillis = 1500, easing = FastOutSlowInEasing),
        label = "ContentAlpha"
    )

    if (showCountryDialog) {
        CountryPickerDialog(
            selected = selectedCountry,
            onSelect = { country ->
                selectedCountry = country
                countryCode = country.dialCode
                showCountryDialog = false
            },
            onDismiss = { showCountryDialog = false }
        )
    }

    if (showForgotDialog) {
        AlertDialog(
            onDismissRequest = { showForgotDialog = false },
            containerColor = Color(0xFF263241),
            title = { Text(LoginText.FORGOT, color = Color.White, fontWeight = FontWeight.SemiBold) },
            text = { Text(LoginText.FORGOT_BODY, color = TgGray, lineHeight = 21.sp) },
            confirmButton = {
                TextButton(onClick = { showForgotDialog = false }) {
                    Text(LoginText.OK, color = TgBlue, fontWeight = FontWeight.SemiBold)
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

    val canSubmitPhone = !isProcessing && countryCode.isNotBlank() && localPhone.length >= 6
    val canVerify = code.length == 5 && !isProcessing
    val canUnlock = password.isNotBlank() && !isProcessing

    val fabEnabled = when (uiState) {
        is LoginUiState.InitialSetup -> canSubmitPhone
        is LoginUiState.EnteringCode -> canVerify
        is LoginUiState.EnteringPassword -> canUnlock
        else -> false
    }
    val fabVisible = uiState is LoginUiState.InitialSetup ||
        uiState is LoginUiState.EnteringCode ||
        uiState is LoginUiState.EnteringPassword
    val onFabClick: () -> Unit = {
        when (uiState) {
            is LoginUiState.InitialSetup -> submitFormattedPhone()
            is LoginUiState.EnteringCode -> onCodeSubmit(code)
            is LoginUiState.EnteringPassword -> onPasswordSubmit(password)
            else -> Unit
        }
    }

    Scaffold(
        containerColor = TgBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TgBg)
                .padding(padding)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                val minContentHeight = maxHeight

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .heightIn(min = minContentHeight),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Spacer(modifier = Modifier.height(24.dp))

                    // ড্রাইভের লোগো — আঁকার এনিমেশনসহ
                    FloatingBrandMark(logoSize = 96.dp)

                    Spacer(modifier = Modifier.height(24.dp))

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.graphicsLayer {
                            alpha = contentAlpha
                            translationY = (1f - contentAlpha) * 24.dp.toPx()
                        }
                    ) {
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
                                    .padding(horizontal = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                when (targetState) {
                                    is LoginUiState.InitialSetup -> {
                                        StepHeader(LoginText.PHONE_TITLE, LoginText.PHONE_SUBTITLE)

                                        Spacer(modifier = Modifier.height(32.dp))

                                        // দেশ নির্বাচন (ট্যাপ করলে সব দেশের তালিকা খোলে)
                                        val codeHasPrefix = remember(countryCode) {
                                            countryCode.isEmpty() || hasCountryWithPrefix(countryCode)
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(54.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .border(1.dp, TgBorder, RoundedCornerShape(12.dp))
                                                .clickable { showCountryDialog = true }
                                                .padding(horizontal = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val country = selectedCountry
                                            when {
                                                country != null -> {
                                                    Text(text = country.flag, fontSize = 22.sp)
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Text(
                                                        text = country.name,
                                                        color = Color.White,
                                                        fontSize = 17.sp,
                                                        maxLines = 1,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                                codeHasPrefix -> Text(
                                                    text = LoginText.COUNTRY_HINT,
                                                    color = TgGray,
                                                    fontSize = 17.sp,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                else -> Text(
                                                    text = LoginText.COUNTRY_WRONG,
                                                    color = TgError,
                                                    fontSize = 17.sp,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                contentDescription = null,
                                                tint = TgGray
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        val phoneFocus = remember { FocusRequester() }
                                        LaunchedEffect(logoFinished) {
                                            if (logoFinished) {
                                                delay(150)
                                                runCatching { phoneFocus.requestFocus() }
                                            }
                                        }

                                        // ফোন নাম্বার বক্স: + কোড | নাম্বার
                                        NotchedBox(
                                            label = LoginText.PHONE_LABEL,
                                            focused = codeFieldFocused || numberFieldFocused,
                                            isError = authError != null
                                        ) {
                                            Text(
                                                text = "+",
                                                color = Color.White,
                                                fontSize = 18.sp
                                            )
                                            BasicTextField(
                                                value = countryCode,
                                                onValueChange = { input ->
                                                    val digits = input.filter { it.isDigit() }.take(4)
                                                    countryCode = digits
                                                    if (selectedCountry?.dialCode != digits) {
                                                        selectedCountry = findCountryByCode(digits)
                                                    }
                                                },
                                                singleLine = true,
                                                textStyle = TextStyle(
                                                    color = Color.White,
                                                    fontSize = 18.sp
                                                ),
                                                cursorBrush = SolidColor(TgBlue),
                                                keyboardOptions = KeyboardOptions(
                                                    keyboardType = KeyboardType.Number,
                                                    imeAction = ImeAction.Next
                                                ),
                                                modifier = Modifier
                                                    .width(54.dp)
                                                    .onFocusChanged { codeFieldFocused = it.isFocused }
                                            )

                                            Box(
                                                modifier = Modifier
                                                    .width(1.dp)
                                                    .height(28.dp)
                                                    .background(TgBorder)
                                            )

                                            Spacer(modifier = Modifier.width(14.dp))

                                            BasicTextField(
                                                value = localPhone,
                                                onValueChange = { input ->
                                                    localPhone = input.filter { it.isDigit() }.take(15)
                                                },
                                                singleLine = true,
                                                textStyle = TextStyle(
                                                    color = Color.White,
                                                    fontSize = 18.sp,
                                                    letterSpacing = 0.8.sp
                                                ),
                                                cursorBrush = SolidColor(TgBlue),
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
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .focusRequester(phoneFocus)
                                                    .onFocusChanged { numberFieldFocused = it.isFocused }
                                            )
                                        }
                                    }

                                    is LoginUiState.EnteringCode -> {
                                        // শিরোনাম = পাঠানো নাম্বার; ট্যাপ করলে নাম্বার বদলানো যায়
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable(enabled = submittedPhone.isNotEmpty()) {
                                                    code = ""
                                                    onBackToPhoneInput()
                                                }
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = submittedPhone.ifEmpty { LoginText.CODE_TITLE_FALLBACK },
                                                color = Color.White,
                                                fontSize = 22.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (submittedPhone.isNotEmpty()) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "Change number",
                                                    tint = TgBlue,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = LoginText.CODE_SUBTITLE,
                                            color = TgGray,
                                            fontSize = 16.sp,
                                            lineHeight = 22.sp,
                                            textAlign = TextAlign.Center
                                        )

                                        Spacer(modifier = Modifier.height(32.dp))

                                        // কোড ঘরে ঢুকলেই কিবোর্ড খুলে যায়
                                        val codeFocusRequester = remember { FocusRequester() }
                                        LaunchedEffect(Unit) {
                                            delay(300)
                                            runCatching { codeFocusRequester.requestFocus() }
                                        }

                                        // ৫-ডিজিট কোড বক্স
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
                                                        val boxColor = when {
                                                            authError != null -> TgError
                                                            isFocused -> TgBlue
                                                            else -> TgBorder
                                                        }
                                                        Box(
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .height(58.dp)
                                                                .border(
                                                                    width = if (isFocused || authError != null) 2.dp else 1.dp,
                                                                    color = boxColor,
                                                                    shape = RoundedCornerShape(12.dp)
                                                                ),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Text(
                                                                text = char,
                                                                color = Color.White,
                                                                fontSize = 22.sp,
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        )

                                        Spacer(modifier = Modifier.height(18.dp))

                                        if (resendTimer > 0) {
                                            val mm = resendTimer / 60
                                            val ss = (resendTimer % 60).toString().padStart(2, '0')
                                            Text(
                                                text = "${LoginText.RESEND_IN} $mm:$ss",
                                                color = TgGray,
                                                fontSize = 15.sp
                                            )
                                        } else {
                                            TextButton(
                                                onClick = {
                                                    onResendCode()
                                                    resendKey++
                                                },
                                                enabled = !isProcessing
                                            ) {
                                                Text(
                                                    text = LoginText.RESEND,
                                                    color = TgBlue,
                                                    fontSize = 16.sp
                                                )
                                            }
                                        }
                                    }

                                    is LoginUiState.EnteringPassword -> {
                                        StepHeader(LoginText.PASSWORD_TITLE, LoginText.PASSWORD_SUBTITLE)

                                        Spacer(modifier = Modifier.height(32.dp))

                                        val passwordFocus = remember { FocusRequester() }
                                        LaunchedEffect(Unit) {
                                            delay(300)
                                            runCatching { passwordFocus.requestFocus() }
                                        }

                                        NotchedBox(
                                            label = LoginText.PASSWORD_LABEL,
                                            focused = passwordFocused,
                                            isError = authError != null
                                        ) {
                                            BasicTextField(
                                                value = password,
                                                onValueChange = { password = it },
                                                singleLine = true,
                                                textStyle = TextStyle(
                                                    color = Color.White,
                                                    fontSize = 18.sp
                                                ),
                                                cursorBrush = SolidColor(TgBlue),
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
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .focusRequester(passwordFocus)
                                                    .onFocusChanged { passwordFocused = it.isFocused }
                                            )
                                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                                Icon(
                                                    imageVector = if (isPasswordVisible) {
                                                        Icons.Default.VisibilityOff
                                                    } else {
                                                        Icons.Default.Visibility
                                                    },
                                                    contentDescription = null,
                                                    tint = TgGray
                                                )
                                            }
                                        }
                                    }

                                    else -> {
                                        Spacer(modifier = Modifier.height(24.dp))
                                        CircularProgressIndicator(color = TgBlue)
                                        Spacer(modifier = Modifier.height(24.dp))
                                    }
                                }
                            }
                        }

                        // এরর বার্তা
                        AnimatedVisibility(
                            visible = authError != null,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = TgError,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = authError ?: "",
                                    color = TgError,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(36.dp))

                        // ফুটার + কপিরাইট
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color(0xFF5E6B7A),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = LoginText.SECURITY,
                                color = Color(0xFF5E6B7A),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = LoginText.COPYRIGHT,
                            color = Color(0xFF4E5A68),
                            fontSize = 11.5.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    // নিচের গোল বাটনের জন্য জায়গা
                    Spacer(modifier = Modifier.height(96.dp))
                }
            }

            // টেলিগ্রামের মতো ব্যাক অ্যারো (উপরে বামে)
            if (canGoBackToPhone) {
                IconButton(
                    onClick = {
                        code = ""
                        password = ""
                        onBackToPhoneInput()
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }

            // নিচে: "Forgot password?" (বামে) + গোল অ্যাকশন বাটন (ডানে) — কিবোর্ডের ঠিক উপরে
            if (fabVisible) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                        .graphicsLayer {
                            alpha = contentAlpha
                            val pop = 0.6f + 0.4f * contentAlpha
                            scaleX = pop
                            scaleY = pop
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState is LoginUiState.EnteringPassword) {
                        TextButton(onClick = { showForgotDialog = true }) {
                            Text(
                                text = LoginText.FORGOT,
                                color = TgBlue,
                                fontSize = 17.sp
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    TgFab(
                        enabled = fabEnabled,
                        isProcessing = isProcessing,
                        onClick = onFabClick
                    )
                }
            }
        }
    }
}
