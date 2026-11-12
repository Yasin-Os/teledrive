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
import com.awd.teledrive.core.BiometricHelper

/**
 * Vault 2.0 safe-folder unlock dialog — drop-in replacement for the inline
 * PIN dialog in NavGraph (showSafePinUnlockDialog). Same PIN flow, plus:
 * - a "Unlock with biometrics" button (only when the user opted in and the
 *   device can do a strong biometric check; on success the vault unlocks
 *   from the stored safe PIN, exactly like the PIN path — the PIN stays
 *   the fallback);
 * - the existing safe-PIN lockout is surfaced with a live-ticking
 *   remaining-seconds message, and the PIN field + Unlock button are
 *   disabled while locked (the old dialog showed the message only after
 *   tapping Unlock and left the input enabled).
 *
 * Swap-in (NavGraph): replace the `if (showSafePinUnlockDialog) {...}`
 * block with:
 *   if (showSafePinUnlockDialog) {
 *       SafeFolderUnlockDialog(
 *           onUnlocked = {
 *               showSafePinUnlockDialog = false
 *               navController.navigate(Screen.SecureStorage.route)
 *           },
 *           onDismiss = { showSafePinUnlockDialog = false },
 *           securityViewModel = securityViewModel
 *       )
 *   }
 * and remove the now-unused safePinError / safePinLockoutRemaining collects
 * at the top of NavGraph if nothing else uses them.
 */
@Composable
fun SafeFolderUnlockDialog(
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
    securityViewModel: SecurityViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val safePinError by securityViewModel.safePinError.collectAsState()
    val safePinLockoutRemaining by securityViewModel.safePinLockoutRemainingSeconds.collectAsState()
    val isSafeFolderBiometricEnabled by securityViewModel.isSafeFolderBiometricEnabled.collectAsState()

    val isLockedOut = safePinLockoutRemaining > 0
    val showBiometric = isSafeFolderBiometricEnabled &&
        BiometricHelper.isBiometricAvailable(context)

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // Live lockout message (ticks every second from the ViewModel ticker);
    // otherwise the last PIN error, if any.
    val lockoutMessage = if (isLockedOut) {
        stringResource(R.string.vault_pin_locked_out, safePinLockoutRemaining)
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
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
                    enabled = !isLockedOut,
                    isError = error != null || isLockedOut,
                    supportingText = (lockoutMessage ?: error)?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (showBiometric) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            val activity = context as? FragmentActivity ?: return@OutlinedButton
                            BiometricHelper.showPrompt(
                                activity = activity,
                                title = context.getString(R.string.biometric_unlock),
                                onSuccess = {
                                    if (securityViewModel.unlockSafeFolderWithBiometric()) {
                                        onUnlocked()
                                    } else {
                                        error = context.getString(R.string.vault_incorrect_pin)
                                    }
                                },
                                onError = { message -> error = message }
                            )
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
                enabled = !isLockedOut,
                onClick = {
                    if (isLockedOut) {
                        error = lockoutMessage
                    } else if (securityViewModel.verifySafeFolderPin(pin)) {
                        onUnlocked()
                    } else {
                        error = safePinError ?: context.getString(R.string.vault_incorrect_pin)
                    }
                }
            ) { Text(stringResource(R.string.unlock)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
