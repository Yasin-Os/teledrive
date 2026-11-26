package com.awd.teledrive.ui.screens.security

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.awd.teledrive.R
import com.awd.teledrive.core.BiometricCipherResult
import com.awd.teledrive.core.BiometricHelper
import kotlinx.coroutines.launch

/**
 * Vault 2.0 safe-folder unlock dialog:
 * - PIN unlock derives the KEK and unwraps the per-vault DEK off the main thread.
 * - Biometric unlock uses a Keystore CryptoObject so the DEK can only be
 *   unwrapped after hardware-verified BIOMETRIC_STRONG authentication.
 * - Live-ticking PIN lockout countdown disables input while locked out.
 */
@Composable
fun SafeFolderUnlockDialog(
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
    securityViewModel: SecurityViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val safePinError by securityViewModel.safePinError.collectAsState()
    val safePinLockoutRemaining by securityViewModel.safePinLockoutRemainingSeconds.collectAsState()
    val isSafeFolderBiometricEnabled by securityViewModel.isSafeFolderBiometricEnabled.collectAsState()

    val isLockedOut = safePinLockoutRemaining > 0
    val showBiometric = isSafeFolderBiometricEnabled &&
        BiometricHelper.isBiometricAvailable(context)

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }

    val lockoutMessage = if (isLockedOut) {
        stringResource(R.string.vault_pin_locked_out, safePinLockoutRemaining)
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = { if (!isVerifying) onDismiss() },
        title = { Text(stringResource(R.string.vault_unlock_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { input ->
                        pin = input.filter { it.isDigit() }.take(6)
                        error = null
                    },
                    label = { Text(stringResource(R.string.vault_enter_pin)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    enabled = !isLockedOut && !isVerifying,
                    isError = error != null || isLockedOut,
                    supportingText = (lockoutMessage ?: error)?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (showBiometric) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        enabled = !isVerifying,
                        onClick = {
                            val activity = context as? FragmentActivity ?: return@OutlinedButton
                            when (val prep = securityViewModel.prepareSafeFolderBiometricUnlockCipher()) {
                                is BiometricCipherResult.Ready -> {
                                    BiometricHelper.showCryptoPrompt(
                                        activity = activity,
                                        title = context.getString(R.string.biometric_unlock),
                                        subtitle = context.getString(R.string.vault_biometric_subtitle),
                                        negativeButtonText = context.getString(R.string.vault_unlock_title),
                                        cipher = prep.cipher,
                                        onSuccess = { authedCipher ->
                                            if (securityViewModel.unlockSafeFolderWithBiometric(authedCipher)) {
                                                onUnlocked()
                                            } else {
                                                error = securityViewModel.safePinError.value
                                                    ?: context.getString(R.string.vault_incorrect_pin)
                                            }
                                        },
                                        onError = { message -> error = message }
                                    )
                                }
                                is BiometricCipherResult.KeyInvalidated -> {
                                    error = "Biometrics changed on this device. Unlock with your PIN and re-enable biometric unlock."
                                }
                                is BiometricCipherResult.Unavailable -> {
                                    error = prep.reason
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.vault_biometric_title))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isLockedOut && !isVerifying,
                onClick = {
                    if (isLockedOut) {
                        error = lockoutMessage
                    } else {
                        isVerifying = true
                        scope.launch {
                            val unlocked = securityViewModel.verifySafeFolderPin(pin)
                            isVerifying = false
                            if (unlocked) {
                                onUnlocked()
                            } else {
                                error = securityViewModel.safePinError.value
                                    ?: safePinError
                                    ?: context.getString(R.string.vault_incorrect_pin)
                            }
                        }
                    }
                }
            ) { Text(stringResource(R.string.unlock)) }
        },
        dismissButton = {
            TextButton(enabled = !isVerifying, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
