package com.awd.teledrive.data.secure

import android.util.Log
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.nio.CharBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MasterPasswordService @Inject constructor(
    private val secureSettings: SecureSettings,
    private val encryptionManager: EncryptionManager
) {
    companion object {
        private const val TAG = "MasterPasswordService"

        // Argon2id parameters for KEK derivation and PIN/password hashing:
        // 64 MiB memory, 3 iterations, 1 lane (OWASP-aligned mobile profile).
        const val ARGON2_T_COST = 3
        const val ARGON2_M_COST_KIB = 65536
        const val ARGON2_PARALLELISM = 1

        // Legacy master_password_hash parameters used by older builds.
        private const val LEGACY_ARGON2_T_COST = 2
        private const val LEGACY_ARGON2_M_COST_KIB = 65536
        private const val LEGACY_ARGON2_PARALLELISM = 1

        private const val SALT_LENGTH_BYTE = 16
        private const val PREFIX_ARGON2ID = "v2:argon2id"
        private const val PREFIX_PBKDF2 = "v2:pbkdf2"
    }

    private val secureRandom = SecureRandom()

    private val argon2Kt: Argon2Kt? = try {
        Argon2Kt()
    } catch (e: UnsatisfiedLinkError) {
        logError("Argon2Kt native library failed to load; falling back to PBKDF2-600k", e)
        null
    } catch (e: Exception) {
        logError("Argon2Kt initialization failed; falling back to PBKDF2-600k", e)
        null
    }

    private val saltKey = "master_password_salt"
    private val hashKey = "master_password_hash"

    // Legacy key from older builds that stored the master password in plaintext.
    // It is deleted on startup (see init) and never written again.
    private val legacyRawKey = "master_password_raw"

    init {
        // One-time migration: erase any plaintext master password left behind
        // by older builds. Best-effort only: if secure storage is unavailable,
        // removeString throws and must not break startup.
        try {
            secureSettings.removeString(legacyRawKey)
        } catch (e: Exception) {
            logError("Could not delete legacy plaintext password key", e)
        }
    }

    fun isArgon2Available(): Boolean = argon2Kt != null

    /**
     * Derives a raw 32-byte KEK from [secret] and [salt] using Argon2id.
     * Returns null if the Argon2Kt native library is unavailable on this host,
     * allowing callers (such as [EncryptionManager.wrapDekWithPin]) to fall
     * back cleanly to PBKDF2-HMAC-SHA256 (600,000 iterations).
     */
    fun deriveKeyArgon2id(
        secret: CharArray,
        salt: ByteArray,
        tCost: Int = ARGON2_T_COST,
        mCostKiB: Int = ARGON2_M_COST_KIB,
        parallelism: Int = ARGON2_PARALLELISM,
        hashLength: Int = EncryptionManager.DEK_LENGTH_BYTE
    ): ByteArray? {
        val argon = argon2Kt ?: return null
        val passwordBytes = charArrayToUtf8Bytes(secret)
        return try {
            val result = argon.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = passwordBytes,
                salt = salt,
                tCostInIterations = tCost,
                mCostInKibibyte = mCostKiB,
                parallelism = parallelism,
                hashLengthInBytes = hashLength
            )
            result.rawHashAsByteArray()
        } catch (e: Exception) {
            logError("Argon2id key derivation failed", e)
            null
        } finally {
            passwordBytes.fill(0)
        }
    }

    /**
     * Hashes a PIN or password into a self-describing salted record using
     * Argon2id (64 MiB, t=3) when available, or PBKDF2-HMAC-SHA256 (600,000
     * iterations) otherwise. Always succeeds with a strong KDF.
     */
    fun hashSecret(secret: CharArray): String {
        val salt = generateSalt()
        val argonHash = hashArgon2Raw(secret, salt, ARGON2_T_COST, ARGON2_M_COST_KIB, ARGON2_PARALLELISM)
        if (argonHash != null) {
            return try {
                "$PREFIX_ARGON2ID:$ARGON2_T_COST:$ARGON2_M_COST_KIB:$ARGON2_PARALLELISM:${bytesToHex(salt)}:${bytesToHex(argonHash)}"
            } finally {
                argonHash.fill(0)
            }
        }
        val pbkdf2Hash = encryptionManager.deriveKekPbkdf2(secret, salt, EncryptionManager.KEK_ITERATION_COUNT)
        return try {
            "$PREFIX_PBKDF2:${EncryptionManager.KEK_ITERATION_COUNT}:${bytesToHex(salt)}:${bytesToHex(pbkdf2Hash)}"
        } finally {
            pbkdf2Hash.fill(0)
        }
    }

    /**
     * Verifies [secret] against a record produced by [hashSecret] using
     * constant-time byte comparison ([MessageDigest.isEqual]).
     */
    fun verifySecret(secret: CharArray, storedRecord: String): Boolean {
        val parts = storedRecord.split(":")
        return when {
            storedRecord.startsWith("$PREFIX_ARGON2ID:") && parts.size == 7 -> {
                val tCost = parts[2].toIntOrNull() ?: return false
                val mCost = parts[3].toIntOrNull() ?: return false
                val parallelism = parts[4].toIntOrNull() ?: return false
                val salt = hexToBytes(parts[5]) ?: return false
                val expected = hexToBytes(parts[6]) ?: return false
                val actual = hashArgon2Raw(secret, salt, tCost, mCost, parallelism, expected.size) ?: return false
                try {
                    MessageDigest.isEqual(actual, expected)
                } finally {
                    actual.fill(0)
                    expected.fill(0)
                }
            }
            storedRecord.startsWith("$PREFIX_PBKDF2:") && parts.size == 5 -> {
                val iterations = parts[2].toIntOrNull() ?: return false
                if (iterations <= 0) return false
                val salt = hexToBytes(parts[3]) ?: return false
                val expected = hexToBytes(parts[4]) ?: return false
                val actual = encryptionManager.deriveKekPbkdf2(secret, salt, iterations)
                try {
                    MessageDigest.isEqual(actual, expected)
                } finally {
                    actual.fill(0)
                    expected.fill(0)
                }
            }
            else -> false
        }
    }

    /**
     * Fail-closed: if secure storage is unavailable, returns true so callers
     * never mistake a broken encrypted store for "no password set".
     */
    fun isPasswordSet(): Boolean {
        return try {
            !secureSettings.getStringStrict(hashKey).isNullOrEmpty()
        } catch (e: IllegalStateException) {
            logError("Secure storage unavailable in isPasswordSet(); failing closed", e)
            true
        }
    }

    fun setPassword(password: String): Boolean {
        val chars = password.toCharArray()
        return try {
            val record = hashSecret(chars)
            secureSettings.editSync {
                putString(hashKey, record)
                remove(saltKey)
                remove(legacyRawKey)
            }
            true
        } catch (e: Exception) {
            logError("setPassword failed", e)
            false
        } finally {
            chars.fill('\u0000')
        }
    }

    fun verifyPassword(password: String): Boolean {
        val storedHash = try {
            secureSettings.getStringStrict(hashKey) ?: return false
        } catch (e: IllegalStateException) {
            logError("Secure storage unavailable in verifyPassword(); failing closed", e)
            return false
        }

        val chars = password.toCharArray()
        try {
            if (storedHash.startsWith("$PREFIX_ARGON2ID:") || storedHash.startsWith("$PREFIX_PBKDF2:")) {
                return verifySecret(chars, storedHash)
            }
            // Legacy encoded Argon2 string stored alongside master_password_salt.
            val saltHex = try {
                secureSettings.getStringStrict(saltKey) ?: return false
            } catch (_: IllegalStateException) {
                return false
            }
            val salt = hexToBytes(saltHex) ?: return false
            val computed = hashLegacyArgon2Encoded(chars, salt) ?: return false
            val computedBytes = computed.toByteArray(Charsets.UTF_8)
            val storedBytes = storedHash.toByteArray(Charsets.UTF_8)
            return try {
                MessageDigest.isEqual(computedBytes, storedBytes)
            } finally {
                computedBytes.fill(0)
                storedBytes.fill(0)
            }
        } finally {
            chars.fill('\u0000')
        }
    }

    private fun hashArgon2Raw(
        secret: CharArray,
        salt: ByteArray,
        tCost: Int,
        mCostKiB: Int,
        parallelism: Int,
        hashLength: Int = EncryptionManager.DEK_LENGTH_BYTE
    ): ByteArray? {
        return deriveKeyArgon2id(secret, salt, tCost, mCostKiB, parallelism, hashLength)
    }

    private fun hashLegacyArgon2Encoded(secret: CharArray, salt: ByteArray): String? {
        val argon = argon2Kt ?: return null
        val passwordBytes = charArrayToUtf8Bytes(secret)
        return try {
            val result = argon.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = passwordBytes,
                salt = salt,
                tCostInIterations = LEGACY_ARGON2_T_COST,
                mCostInKibibyte = LEGACY_ARGON2_M_COST_KIB,
                parallelism = LEGACY_ARGON2_PARALLELISM
            )
            result.encodedOutputAsString()
        } catch (e: Exception) {
            logError("Legacy Argon2 hashing failed", e)
            null
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun generateSalt(): ByteArray {
        return ByteArray(SALT_LENGTH_BYTE).also { secureRandom.nextBytes(it) }
    }

    private fun charArrayToUtf8Bytes(chars: CharArray): ByteArray {
        val byteBuffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(byteBuffer.remaining())
        byteBuffer.get(bytes)
        if (byteBuffer.hasArray()) {
            byteBuffer.array().fill(0)
        }
        return bytes
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            result[i * 2] = hexChars[v ushr 4]
            result[i * 2 + 1] = hexChars[v and 0x0F]
        }
        return String(result)
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi == -1 || lo == -1) return null
            bytes[i] = ((hi shl 4) or lo).toByte()
        }
        return bytes
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
        } catch (_: RuntimeException) {
            // Ignore in JVM unit tests where android.util.Log is not mocked.
        }
    }
}
