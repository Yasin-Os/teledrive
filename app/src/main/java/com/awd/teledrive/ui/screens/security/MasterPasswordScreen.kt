package com.awd.teledrive.ui.screens.security

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.awd.teledrive.core.BiometricHelper

@Composable
fun MasterPasswordScreen(
    onSuccess: () -> Unit,
    viewModel: SecurityViewModel = hiltViewModel()
) {
    val isPasswordSet by viewModel.isPasswordSet.collectAsState()
    val isBiometricEnabled by viewModel.isBiometricEnabled.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current

    val triggerBiometric = {
        val activity = context as? FragmentActivity
        if (activity != null && isPasswordSet && isBiometricEnabled) {
            BiometricHelper.showPrompt(
                activity = activity,
                title = "Unlock MyCloud",
                onSuccess = {
                    viewModel.unlockAppWithBiometric()
                    onSuccess()
                },
                onError = {}
            )
        }
    }

    LaunchedEffect(isPasswordSet, isBiometricEnabled) {
        if (isPasswordSet && isBiometricEnabled) {
            triggerBiometric()
        }
    }

    PinLockScreenContent(
        isPasswordSet = isPasswordSet,
        isBiometricEnabled = isBiometricEnabled,
        externalError = error,
        onVerifyPin = { pin ->
            if (viewModel.verifyPassword(pin)) {
                onSuccess()
                true
            } else false
        },
        onSetPin = { pin ->
            viewModel.setPassword(pin)
            onSuccess()
        },
        onBiometricClick = triggerBiometric
    )
}

@Composable
fun PinLockScreenContent(
    isPasswordSet: Boolean,
    isBiometricEnabled: Boolean,
    externalError: String?,
    onVerifyPin: (String) -> Boolean,
    onSetPin: (String) -> Unit,
    onBiometricClick: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf("") }
    var isConfirmStep by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val handleDigit: (String) -> Unit = { digit ->
        localError = null
        if (pin.length < 4) {
            val nextPin = pin + digit
            pin = nextPin
            if (nextPin.length == 4) {
                if (isPasswordSet) {
                    if (!onVerifyPin(nextPin)) {
                        localError = "Incorrect PIN"
                        pin = ""
                    }
                } else {
                    if (!isConfirmStep) {
                        firstPin = nextPin
                        pin = ""
                        isConfirmStep = true
                    } else {
                        if (nextPin == firstPin) {
                            onSetPin(nextPin)
                        } else {
                            localError = "PIN did not match. Try again."
                            pin = ""
                            firstPin = ""
                            isConfirmStep = false
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A1122), Color(0xFF060A14))
                )
            )
            .statusBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 48.dp, horizontal = 24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xFF0077CC).copy(alpha = 0.2f),
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = when {
                        isPasswordSet -> "Enter App Lock PIN"
                        isConfirmStep -> "Confirm Your PIN"
                        else -> "Create 4-Digit App PIN"
                    },
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                val errText = localError ?: externalError
                Text(
                    text = errText ?: "Protected with MyCloud Security",
                    color = if (errText != null) Color(0xFFEF5350) else Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(36.dp))

                // 4 PIN Dots Indicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(4) { index ->
                        val isFilled = index < pin.length
                        val dotColor by animateColorAsState(
                            targetValue = if (isFilled) Color(0xFF38BDF8) else Color.Transparent,
                            label = "PinDotColor"
                        )
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                                .border(2.dp, if (isFilled) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.35f), CircleShape)
                        )
                    }
                }
            }

            // Modern Circular PIN Keypad
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9")
                )
                rows.forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        row.forEach { digit ->
                            PinKeyButton(text = digit) { handleDigit(digit) }
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isPasswordSet && isBiometricEnabled) {
                        Surface(
                            onClick = onBiometricClick,
                            shape = CircleShape,
                            color = Color(0xFF0077CC).copy(alpha = 0.25f),
                            modifier = Modifier.size(74.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Fingerprint,
                                    contentDescription = "Biometric",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.size(74.dp))
                    }

                    PinKeyButton(text = "0") { handleDigit("0") }

                    Surface(
                        onClick = {
                            if (pin.isNotEmpty()) pin = pin.dropLast(1)
                        },
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.size(74.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "Backspace",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinKeyButton(text: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.1f),
        modifier = Modifier
            .size(74.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
