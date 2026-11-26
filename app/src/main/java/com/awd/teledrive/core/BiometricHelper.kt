package com.awd.teledrive.core

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed class BiometricCipherResult {
    data class Ready(val cipher: Cipher) : BiometricCipherResult()
    data object KeyInvalidated : BiometricCipherResult()
    data class Unavailable(val reason: String) : BiometricCipherResult()
}

object BiometricHelper {
    private const val TAG = "BiometricHelper"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /**
     * True only when this device can do a strong biometric check right now
     * (hardware present, enrolled, and available). Devices without
     * biometrics return false so callers stay on PIN-only and never crash.
     * BIOMETRIC_STRONG only: required for hardware-backed Keystore CryptoObject
     * and compatible with a negative PIN-fallback button.
     */
    fun isBiometricAvailable(context: Context): Boolean {
        return try {
            BiometricManager.from(context).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (_: Exception) {
            false
        }
    }

    /** True if the given Android Keystore alias currently exists. */
    fun hasBiometricKey(alias: String): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.containsAlias(alias)
        } catch (_: Exception) {
            false
        }
    }

    /** Deletes a biometric Keystore key alias if it exists. */
    fun deleteBiometricKey(alias: String) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete biometric key $alias", e)
        }
    }

    /**
     * Generates a fresh per-use strong-biometric-bound AES-256-GCM key under
     * [alias] in Android Keystore and returns a Cipher initialized in
     * ENCRYPT_MODE for passing into [BiometricPrompt.CryptoObject].
     */
    fun createEncryptCipher(alias: String): Cipher? {
        return try {
            deleteBiometricKey(alias)
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    0, // Require strong biometric authentication on every use.
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }

            keyGenerator.init(builder.build())
            val secretKey = keyGenerator.generateKey()
            Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secretKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create biometric encrypt cipher for $alias", e)
            null
        }
    }

    /**
     * Loads the biometric-bound Keystore key [alias] and initializes a
     * DECRYPT_MODE Cipher with [iv] for passing into
     * [BiometricPrompt.CryptoObject]. Detects [KeyPermanentlyInvalidatedException]
     * (e.g. after new biometric enrollment) and deletes the stale key cleanly.
     */
    fun createDecryptCipher(alias: String, iv: ByteArray): BiometricCipherResult {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val secretKey = keyStore.getKey(alias, null) as? SecretKey
                ?: return BiometricCipherResult.Unavailable("Biometric key not found. Unlock with PIN and re-enable biometric unlock.")
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            BiometricCipherResult.Ready(cipher)
        } catch (_: KeyPermanentlyInvalidatedException) {
            deleteBiometricKey(alias)
            BiometricCipherResult.KeyInvalidated
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create biometric decrypt cipher for $alias", e)
            BiometricCipherResult.Unavailable("Biometric unlock unavailable. Please use your PIN.")
        }
    }

    /**
     * Shows a Class 3 (BIOMETRIC_STRONG) BiometricPrompt bound to a Keystore
     * [BiometricPrompt.CryptoObject]. [onSuccess] is ONLY invoked when Android
     * returns an authenticated [Cipher] from the CryptoObject — never as a
     * plain boolean callback.
     */
    fun showCryptoPrompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String = "Verify your identity with biometrics",
        negativeButtonText: String = "Use PIN",
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errString.toString())
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    val authenticatedCipher = result.cryptoObject?.cipher
                    if (authenticatedCipher != null) {
                        onSuccess(authenticatedCipher)
                    } else {
                        onError("Biometric cryptographic verification failed")
                    }
                }

                // L28: a transient bad touch is not an error — the system
                // prompt already gives feedback and lets the user retry.
                // Failing the whole flow here made one smudged fingerprint
                // look like biometric was broken.
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }
}
