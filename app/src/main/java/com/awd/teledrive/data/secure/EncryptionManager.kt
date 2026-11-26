package com.awd.teledrive.data.secure

import android.util.Log
import kotlinx.coroutines.yield
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thrown when [EncryptionManager.decryptFile] is asked to decrypt a file that
 * does not carry the v2 DEK-encrypted header (i.e. a legacy file encrypted
 * directly under a PIN). Callers can surface a migration prompt or invoke
 * [EncryptionManager.migrateLegacyFileToDek] when the legacy PIN is available.
 */
class LegacyEncryptedFileException(message: String) : GeneralSecurityException(message)

@Singleton
class EncryptionManager @Inject constructor() {

    companion object {
        private const val TAG = "EncryptionManager"

        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BIT = 128
        private const val TAG_LENGTH_BYTE = TAG_LENGTH_BIT / 8
        const val IV_LENGTH_BYTE = 12
        const val SALT_LENGTH_BYTE = 16
        const val DEK_LENGTH_BYTE = 32
        private const val KEY_LENGTH_BIT = 256

        // OWASP recommends 600,000 PBKDF2-HMAC-SHA256 iterations when Argon2id
        // is not used.
        const val KEK_ITERATION_COUNT = 600_000
        private const val MIN_PBKDF2_WRAP_ITERATIONS = 100_000
        private const val MAX_PBKDF2_WRAP_ITERATIONS = 5_000_000

        // Legacy v1 files used PBKDF2-HMAC-SHA256 directly on the vault PIN:
        // recent builds used 600,000 iterations and older builds used 10,000.
        const val LEGACY_CURRENT_ITERATION_COUNT = 600_000
        const val LEGACY_OLD_ITERATION_COUNT = 10_000

        // v2 file format:
        // [8B MAGIC_V2][16B fileSalt][4B chunkSize][8B noncePrefix]
        // followed by 1+ chunks of:
        // [4B ciphertextLen (plainLen + 16B tag)][1B isFinal (0|1)][ciphertext+tag]
        private val MAGIC_V2 = byteArrayOf(
            'M'.code.toByte(),
            'Y'.code.toByte(),
            'C'.code.toByte(),
            'L'.code.toByte(),
            'O'.code.toByte(),
            'U'.code.toByte(),
            'D'.code.toByte(),
            0x02
        )
        private const val NONCE_PREFIX_LENGTH_BYTE = 8
        private const val DEFAULT_CHUNK_SIZE = 64 * 1024
        private const val MIN_CHUNK_SIZE = 1024
        private const val MAX_CHUNK_SIZE = 1024 * 1024

        private val FILE_HKDF_INFO = "MyCloud-Vault-File-v2".toByteArray(Charsets.UTF_8)
        private val PIN_WRAP_AAD = "MyCloud-Vault-DEK-PIN-v2".toByteArray(Charsets.UTF_8)
        private val BIOMETRIC_WRAP_AAD = "MyCloud-Vault-DEK-BIO-v2".toByteArray(Charsets.UTF_8)

        private const val WRAP_PREFIX_ARGON2ID = "v2:argon2id:t3:m65536:p1"
        private const val WRAP_PREFIX_PBKDF2 = "v2:pbkdf2"
        private const val WRAP_PREFIX_KEYSTORE = "v2:ks"
    }

    private val secureRandom = SecureRandom()

    /** Generates a fresh random 256-bit per-vault Data Encryption Key (DEK). */
    fun generateDek(): ByteArray {
        return ByteArray(DEK_LENGTH_BYTE).also { secureRandom.nextBytes(it) }
    }

    /**
     * Derives a 256-bit key using PBKDF2-HMAC-SHA256. Falls back to an RFC 2898
     * HMAC-SHA256 implementation if the platform JCA provider lacks
     * PBKDF2WithHmacSHA256 (Android API 24-25).
     */
    fun deriveKekPbkdf2(
        secret: CharArray,
        salt: ByteArray,
        iterations: Int = KEK_ITERATION_COUNT
    ): ByteArray {
        require(iterations > 0) { "PBKDF2 iteration count must be positive" }
        val spec = PBEKeySpec(secret, salt, iterations, KEY_LENGTH_BIT)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        } catch (_: NoSuchAlgorithmException) {
            return pbkdf2HmacSha256Fallback(secret, salt, iterations, DEK_LENGTH_BYTE)
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Wraps [dek] under a KEK derived from [pin]. Uses [argon2Deriver] when
     * provided and available, falling back to PBKDF2-HMAC-SHA256 (600,000
     * iterations). The KDF identifier and parameters are stored in the returned
     * envelope so unwrapping always uses the matching derivation.
     */
    fun wrapDekWithPin(
        dek: ByteArray,
        pin: CharArray,
        argon2Deriver: ((CharArray, ByteArray) -> ByteArray?)? = null
    ): String {
        require(dek.size == DEK_LENGTH_BYTE) { "DEK must be $DEK_LENGTH_BYTE bytes" }
        val salt = ByteArray(SALT_LENGTH_BYTE).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH_BYTE).also { secureRandom.nextBytes(it) }

        val argon2Kek = try {
            argon2Deriver?.invoke(pin, salt)?.takeIf { it.size == DEK_LENGTH_BYTE }
        } catch (e: Exception) {
            logWarn("Argon2id KEK derivation failed, falling back to PBKDF2-600k", e)
            null
        }

        val kdfPrefix: String
        val kek: ByteArray
        if (argon2Kek != null) {
            kdfPrefix = WRAP_PREFIX_ARGON2ID
            kek = argon2Kek
        } else {
            kdfPrefix = "$WRAP_PREFIX_PBKDF2:$KEK_ITERATION_COUNT"
            kek = deriveKekPbkdf2(pin, salt, KEK_ITERATION_COUNT)
        }

        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_LENGTH_BIT, iv))
            cipher.updateAAD(PIN_WRAP_AAD)
            val wrapped = cipher.doFinal(dek)
            "$kdfPrefix:${bytesToHex(salt)}:${bytesToHex(iv)}:${bytesToHex(wrapped)}"
        } finally {
            kek.fill(0)
        }
    }

    /**
     * Authenticates [pin] and unwraps the 256-bit DEK from [wrappedBlob].
     * Returns null if the PIN is wrong, the envelope is malformed, or GCM
     * authentication fails.
     */
    fun unwrapDekWithPin(
        wrappedBlob: String,
        pin: CharArray,
        argon2Deriver: ((CharArray, ByteArray) -> ByteArray?)? = null
    ): ByteArray? {
        val parts = wrappedBlob.split(":")
        val salt: ByteArray
        val iv: ByteArray
        val ciphertext: ByteArray
        val kek: ByteArray

        try {
            when {
                wrappedBlob.startsWith("$WRAP_PREFIX_ARGON2ID:") && parts.size == 8 -> {
                    salt = hexToBytes(parts[5]) ?: return null
                    iv = hexToBytes(parts[6]) ?: return null
                    ciphertext = hexToBytes(parts[7]) ?: return null
                    val derived = argon2Deriver?.invoke(pin, salt) ?: return null
                    if (derived.size != DEK_LENGTH_BYTE) {
                        derived.fill(0)
                        return null
                    }
                    kek = derived
                }
                wrappedBlob.startsWith("$WRAP_PREFIX_PBKDF2:") && parts.size == 6 -> {
                    val iterations = parts[2].toIntOrNull() ?: return null
                    if (iterations !in MIN_PBKDF2_WRAP_ITERATIONS..MAX_PBKDF2_WRAP_ITERATIONS) return null
                    salt = hexToBytes(parts[3]) ?: return null
                    iv = hexToBytes(parts[4]) ?: return null
                    ciphertext = hexToBytes(parts[5]) ?: return null
                    kek = deriveKekPbkdf2(pin, salt, iterations)
                }
                else -> return null
            }
        } catch (e: Exception) {
            logWarn("Failed to parse or derive KEK for wrapped DEK", e)
            return null
        }

        if (salt.size != SALT_LENGTH_BYTE || iv.size != IV_LENGTH_BYTE || ciphertext.size < TAG_LENGTH_BYTE) {
            kek.fill(0)
            return null
        }

        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_LENGTH_BIT, iv))
            cipher.updateAAD(PIN_WRAP_AAD)
            val unwrapped = cipher.doFinal(ciphertext)
            if (unwrapped.size == DEK_LENGTH_BYTE) {
                unwrapped
            } else {
                unwrapped.fill(0)
                null
            }
        } catch (_: GeneralSecurityException) {
            null
        } finally {
            kek.fill(0)
        }
    }

    /**
     * Wraps [dek] with a biometric-authenticated Android Keystore AES-GCM
     * [cipher] (initialized in ENCRYPT_MODE). Must be called only after
     * BiometricPrompt succeeds with a CryptoObject containing [cipher].
     */
    fun wrapDekWithCipher(dek: ByteArray, cipher: Cipher): String {
        require(dek.size == DEK_LENGTH_BYTE) { "DEK must be $DEK_LENGTH_BYTE bytes" }
        return wrapSecretWithCipher(dek, BIOMETRIC_WRAP_AAD, cipher)
    }

    /**
     * Unwraps the 256-bit DEK from [wrappedBlob] using a biometric-authenticated
     * Android Keystore AES-GCM [cipher] (initialized in DECRYPT_MODE with the
     * IV from [extractBiometricWrapIv]). Returns null on authentication failure.
     */
    fun unwrapDekWithCipher(wrappedBlob: String, cipher: Cipher): ByteArray? {
        val unwrapped = unwrapSecretWithCipher(wrappedBlob, BIOMETRIC_WRAP_AAD, cipher) ?: return null
        if (unwrapped.size != DEK_LENGTH_BYTE) {
            unwrapped.fill(0)
            return null
        }
        return unwrapped
    }

    /** Wraps an arbitrary secret byte array with an authenticated Keystore AES-GCM cipher. */
    fun wrapSecretWithCipher(secret: ByteArray, aad: ByteArray, cipher: Cipher): String {
        val iv = cipher.iv
        require(iv != null && iv.size == IV_LENGTH_BYTE) { "Keystore cipher did not provide a $IV_LENGTH_BYTE-byte IV" }
        cipher.updateAAD(aad)
        val wrapped = cipher.doFinal(secret)
        return "$WRAP_PREFIX_KEYSTORE:${bytesToHex(iv)}:${bytesToHex(wrapped)}"
    }

    /** Unwraps an arbitrary secret byte array with an authenticated Keystore AES-GCM cipher. */
    fun unwrapSecretWithCipher(wrappedBlob: String, aad: ByteArray, cipher: Cipher): ByteArray? {
        val parts = wrappedBlob.split(":")
        if (parts.size != 4 || "${parts[0]}:${parts[1]}" != WRAP_PREFIX_KEYSTORE) return null
        val ciphertext = hexToBytes(parts[3]) ?: return null
        if (ciphertext.size < TAG_LENGTH_BYTE) return null
        return try {
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /** Extracts the 12-byte GCM IV stored inside a Keystore-wrapped blob. */
    fun extractBiometricWrapIv(wrappedBlob: String): ByteArray? {
        val parts = wrappedBlob.split(":")
        if (parts.size != 4 || "${parts[0]}:${parts[1]}" != WRAP_PREFIX_KEYSTORE) return null
        val iv = hexToBytes(parts[2]) ?: return null
        return iv.takeIf { it.size == IV_LENGTH_BYTE }
    }

    /** True if [file] begins with the v2 DEK-encrypted magic header. */
    fun isV2EncryptedFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < MAGIC_V2.size) return false
        return try {
            FileInputStream(file).use { fis ->
                val magic = ByteArray(MAGIC_V2.size)
                val read = readUpTo(fis, magic, magic.size)
                read == MAGIC_V2.size && Arrays.equals(magic, MAGIC_V2)
            }
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Encrypts [inputFile] into [outputFile] using the v2 chunked AES-256-GCM
     * format keyed by a per-file HKDF-SHA256 subkey derived from [dek].
     * Writes to a sibling temp file first and moves it into place on success.
     */
    suspend fun encryptFile(
        inputFile: File,
        outputFile: File,
        dek: ByteArray,
        onProgress: (Float) -> Unit = {}
    ) {
        require(dek.size == DEK_LENGTH_BYTE) { "DEK must be $DEK_LENGTH_BYTE bytes" }
        val fileSalt = ByteArray(SALT_LENGTH_BYTE).also { secureRandom.nextBytes(it) }
        val noncePrefix = ByteArray(NONCE_PREFIX_LENGTH_BYTE).also { secureRandom.nextBytes(it) }
        val fileKey = hkdfSha256(dek, fileSalt, FILE_HKDF_INFO)
        val tempFile = createTempSibling(outputFile, ".enc")
        val plainBuf = ByteArray(DEFAULT_CHUNK_SIZE)

        try {
            val totalSize = inputFile.length()
            var processedBytes = 0L
            var lastReportedProgress = -1f

            DataOutputStream(BufferedOutputStream(FileOutputStream(tempFile), DEFAULT_CHUNK_SIZE)).use { dos ->
                dos.write(MAGIC_V2)
                dos.write(fileSalt)
                dos.writeInt(DEFAULT_CHUNK_SIZE)
                dos.write(noncePrefix)

                BufferedInputStream(FileInputStream(inputFile), DEFAULT_CHUNK_SIZE).use { fis ->
                    var pendingByte = fis.read()
                    if (pendingByte == -1) {
                        writeEncryptedChunk(dos, fileKey, fileSalt, noncePrefix, 0, plainBuf, 0, isFinal = true)
                        onProgress(1f)
                    } else {
                        var chunkIndex = 0
                        while (pendingByte != -1) {
                            if (chunkIndex < 0) throw GeneralSecurityException("File exceeds maximum chunk count")
                            plainBuf[0] = pendingByte.toByte()
                            var filled = 1
                            while (filled < DEFAULT_CHUNK_SIZE) {
                                val r = fis.read(plainBuf, filled, DEFAULT_CHUNK_SIZE - filled)
                                if (r == -1) break
                                filled += r
                            }
                            pendingByte = fis.read()
                            val isFinal = (pendingByte == -1)
                            writeEncryptedChunk(dos, fileKey, fileSalt, noncePrefix, chunkIndex, plainBuf, filled, isFinal)
                            chunkIndex++
                            processedBytes += filled
                            if (totalSize > 0) {
                                val progress = (processedBytes.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)
                                if (progress - lastReportedProgress >= 0.02f || isFinal) {
                                    onProgress(progress)
                                    lastReportedProgress = progress
                                    yield()
                                }
                            }
                        }
                    }
                }
                dos.flush()
            }
            moveTempIntoPlace(tempFile, outputFile)
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        } finally {
            fileKey.fill(0)
            plainBuf.fill(0)
        }
    }

    /**
     * Decrypts a v2 DEK-encrypted file into [outputFile]. Throws
     * [LegacyEncryptedFileException] if [encryptedFile] does not have the v2
     * magic header (i.e. it is a legacy PIN-encrypted file). Never leaves
     * partial or unauthenticated plaintext at [outputFile] on failure.
     */
    suspend fun decryptFile(
        encryptedFile: File,
        outputFile: File,
        dek: ByteArray,
        onProgress: (Float) -> Unit = {}
    ) {
        require(dek.size == DEK_LENGTH_BYTE) { "DEK must be $DEK_LENGTH_BYTE bytes" }
        if (!isV2EncryptedFile(encryptedFile)) {
            throw LegacyEncryptedFileException("Encrypted file uses legacy PIN-based format and must be migrated")
        }

        val tempFile = createTempSibling(outputFile, ".dec")
        var fileKey: ByteArray? = null
        try {
            val totalSize = encryptedFile.length()
            var processedBytes = 0L
            var lastReportedProgress = -1f

            DataInputStream(BufferedInputStream(FileInputStream(encryptedFile), DEFAULT_CHUNK_SIZE)).use { dis ->
                val magic = ByteArray(MAGIC_V2.size)
                dis.readFully(magic)
                if (!Arrays.equals(magic, MAGIC_V2)) {
                    throw LegacyEncryptedFileException("Invalid v2 vault header")
                }

                val fileSalt = ByteArray(SALT_LENGTH_BYTE)
                dis.readFully(fileSalt)

                val chunkSize = dis.readInt()
                if (chunkSize !in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) {
                    throw GeneralSecurityException("Invalid encrypted chunk size: $chunkSize")
                }

                val noncePrefix = ByteArray(NONCE_PREFIX_LENGTH_BYTE)
                dis.readFully(noncePrefix)
                processedBytes += MAGIC_V2.size + SALT_LENGTH_BYTE + 4 + NONCE_PREFIX_LENGTH_BYTE

                val derivedKey = hkdfSha256(dek, fileSalt, FILE_HKDF_INFO)
                fileKey = derivedKey
                val keySpec = SecretKeySpec(derivedKey, "AES")

                BufferedOutputStream(FileOutputStream(tempFile), DEFAULT_CHUNK_SIZE).use { fos ->
                    var chunkIndex = 0
                    var sawFinal = false
                    while (true) {
                        if (chunkIndex < 0) throw GeneralSecurityException("File exceeds maximum chunk count")
                        val ciphertextLen = try {
                            dis.readInt()
                        } catch (_: EOFException) {
                            throw AEADBadTagException("Truncated encrypted file before final chunk")
                        }
                        if (ciphertextLen < TAG_LENGTH_BYTE || ciphertextLen > chunkSize + TAG_LENGTH_BYTE) {
                            throw GeneralSecurityException("Invalid ciphertext chunk length: $ciphertextLen")
                        }

                        val isFinalByte = dis.read()
                        if (isFinalByte != 0 && isFinalByte != 1) {
                            throw GeneralSecurityException("Invalid chunk final marker: $isFinalByte")
                        }
                        val isFinal = (isFinalByte == 1)

                        val ciphertext = ByteArray(ciphertextLen)
                        try {
                            dis.readFully(ciphertext)
                        } catch (_: EOFException) {
                            throw AEADBadTagException("Truncated ciphertext chunk $chunkIndex")
                        }

                        val cipher = Cipher.getInstance(ALGORITHM)
                        val iv = buildChunkIv(noncePrefix, chunkIndex)
                        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_LENGTH_BIT, iv))
                        cipher.updateAAD(buildChunkAad(fileSalt, chunkIndex, isFinal))
                        val plaintext = cipher.doFinal(ciphertext)
                        try {
                            if (plaintext.isNotEmpty()) {
                                fos.write(plaintext)
                            }
                        } finally {
                            plaintext.fill(0)
                        }

                        processedBytes += 5L + ciphertextLen
                        if (totalSize > 0) {
                            val progress = (processedBytes.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)
                            if (progress - lastReportedProgress >= 0.02f || isFinal) {
                                onProgress(progress)
                                lastReportedProgress = progress
                                yield()
                            }
                        }

                        if (isFinal) {
                            if (dis.read() != -1) {
                                throw AEADBadTagException("Unexpected trailing bytes after final chunk")
                            }
                            sawFinal = true
                            break
                        }
                        chunkIndex++
                    }

                    if (!sawFinal) {
                        throw AEADBadTagException("Missing final chunk marker")
                    }
                    fos.flush()
                }
            }
            moveTempIntoPlace(tempFile, outputFile)
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        } finally {
            fileKey?.fill(0)
        }
    }

    /**
     * Decrypts a legacy v1 file ([16B salt][12B IV][AES-256-GCM ciphertext+tag]
     * derived directly from [legacyPin] via PBKDF2-HMAC-SHA256).
     *
     * Uses direct Cipher.update/doFinal instead of CipherInputStream so
     * AEADBadTagException (a BadPaddingException subclass) is thrown directly
     * rather than wrapped in IOException — making the 600,000 -> 10,000
     * iteration fallback actually functional and preventing partial plaintext
     * from ever being written to [outputFile] on authentication failure.
     */
    suspend fun decryptLegacyFile(
        encryptedFile: File,
        outputFile: File,
        legacyPin: CharArray,
        onProgress: (Float) -> Unit = {}
    ) {
        try {
            decryptLegacyWithIterations(
                encryptedFile = encryptedFile,
                outputFile = outputFile,
                legacyPin = legacyPin,
                iterations = LEGACY_CURRENT_ITERATION_COUNT,
                onProgress = onProgress
            )
        } catch (e: BadPaddingException) {
            logWarn("Legacy decrypt with 600k KDF failed, retrying with 10k legacy iteration count", e)
            decryptLegacyWithIterations(
                encryptedFile = encryptedFile,
                outputFile = outputFile,
                legacyPin = legacyPin,
                iterations = LEGACY_OLD_ITERATION_COUNT,
                onProgress = onProgress
            )
        }
    }

    /**
     * Migrates a single legacy PIN-encrypted file in place to the v2 DEK format:
     * decrypts with [legacyPin] (supporting both 600k and 10k PBKDF2 counts),
     * re-encrypts under [dek], securely deletes the temporary plaintext, and
     * atomically swaps the original file with rollback on failure.
     *
     * Returns true if the file was migrated, or false if it was already v2.
     */
    suspend fun migrateLegacyFileToDek(
        encryptedFile: File,
        legacyPin: CharArray,
        dek: ByteArray,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        require(dek.size == DEK_LENGTH_BYTE) { "DEK must be $DEK_LENGTH_BYTE bytes" }
        if (isV2EncryptedFile(encryptedFile)) return false

        val tempPlain = createTempSibling(encryptedFile, ".mig_plain")
        val tempV2 = createTempSibling(encryptedFile, ".mig_v2")
        try {
            decryptLegacyFile(encryptedFile, tempPlain, legacyPin) { p ->
                onProgress(p * 0.5f)
            }
            encryptFile(tempPlain, tempV2, dek) { p ->
                onProgress(0.5f + p * 0.5f)
            }
            tempPlain.delete()
            replaceFileSafely(source = tempV2, target = encryptedFile)
            return true
        } catch (e: Exception) {
            tempV2.delete()
            throw e
        } finally {
            if (tempPlain.exists()) {
                tempPlain.delete()
            }
        }
    }

    private suspend fun decryptLegacyWithIterations(
        encryptedFile: File,
        outputFile: File,
        legacyPin: CharArray,
        iterations: Int,
        onProgress: (Float) -> Unit
    ) {
        val headerSize = SALT_LENGTH_BYTE + IV_LENGTH_BYTE
        if (encryptedFile.length() < headerSize + TAG_LENGTH_BYTE) {
            throw GeneralSecurityException("Legacy encrypted file is truncated")
        }

        val tempFile = createTempSibling(outputFile, ".ldec")
        var keyBytes: ByteArray? = null
        try {
            val totalSize = encryptedFile.length() - headerSize
            var processedBytes = 0L
            var lastReportedProgress = -1f

            BufferedInputStream(FileInputStream(encryptedFile), 32768).use { fis ->
                val salt = ByteArray(SALT_LENGTH_BYTE)
                readFully(fis, salt)

                val iv = ByteArray(IV_LENGTH_BYTE)
                readFully(fis, iv)

                val derived = deriveKekPbkdf2(legacyPin, salt, iterations)
                keyBytes = derived

                val cipher = Cipher.getInstance(ALGORITHM)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(TAG_LENGTH_BIT, iv))

                BufferedOutputStream(FileOutputStream(tempFile), 32768).use { fos ->
                    val buffer = ByteArray(32768)
                    var bytesRead = fis.read(buffer)
                    while (bytesRead != -1) {
                        val out = cipher.update(buffer, 0, bytesRead)
                        if (out != null && out.isNotEmpty()) {
                            fos.write(out)
                            out.fill(0)
                        }
                        processedBytes += bytesRead
                        if (totalSize > 0) {
                            val currentProgress = (processedBytes.toFloat() / totalSize.toFloat()).coerceIn(0f, 0.99f)
                            if (currentProgress - lastReportedProgress >= 0.02f) {
                                onProgress(currentProgress)
                                lastReportedProgress = currentProgress
                                yield()
                            }
                        }
                        bytesRead = fis.read(buffer)
                    }
                    // Verify the GCM authentication tag directly; throws
                    // AEADBadTagException (a BadPaddingException subclass) on mismatch.
                    val finalOut = cipher.doFinal()
                    if (finalOut != null && finalOut.isNotEmpty()) {
                        fos.write(finalOut)
                        finalOut.fill(0)
                    }
                    fos.flush()
                    onProgress(1f)
                }
            }
            moveTempIntoPlace(tempFile, outputFile)
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        } finally {
            keyBytes?.fill(0)
        }
    }

    private fun writeEncryptedChunk(
        dos: DataOutputStream,
        fileKey: ByteArray,
        fileSalt: ByteArray,
        noncePrefix: ByteArray,
        chunkIndex: Int,
        plainBuf: ByteArray,
        length: Int,
        isFinal: Boolean
    ) {
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = buildChunkIv(noncePrefix, chunkIndex)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(TAG_LENGTH_BIT, iv))
        cipher.updateAAD(buildChunkAad(fileSalt, chunkIndex, isFinal))
        val ciphertext = cipher.doFinal(plainBuf, 0, length)
        dos.writeInt(ciphertext.size)
        dos.writeByte(if (isFinal) 1 else 0)
        dos.write(ciphertext)
    }

    private fun buildChunkIv(noncePrefix: ByteArray, chunkIndex: Int): ByteArray {
        val iv = ByteArray(IV_LENGTH_BYTE)
        System.arraycopy(noncePrefix, 0, iv, 0, NONCE_PREFIX_LENGTH_BYTE)
        iv[8] = (chunkIndex ushr 24).toByte()
        iv[9] = (chunkIndex ushr 16).toByte()
        iv[10] = (chunkIndex ushr 8).toByte()
        iv[11] = chunkIndex.toByte()
        return iv
    }

    private fun buildChunkAad(fileSalt: ByteArray, chunkIndex: Int, isFinal: Boolean): ByteArray {
        val buffer = ByteBuffer.allocate(MAGIC_V2.size + SALT_LENGTH_BYTE + 4 + 1)
        buffer.put(MAGIC_V2)
        buffer.put(fileSalt)
        buffer.putInt(chunkIndex)
        buffer.put(if (isFinal) 1.toByte() else 0.toByte())
        return buffer.array()
    }

    /** RFC 5869 HKDF-SHA256 for a single 32-byte output key block. */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val extractMac = Mac.getInstance("HmacSHA256")
        extractMac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = extractMac.doFinal(ikm)
        return try {
            val expandMac = Mac.getInstance("HmacSHA256")
            expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
            expandMac.update(info)
            expandMac.update(0x01.toByte())
            expandMac.doFinal()
        } finally {
            prk.fill(0)
        }
    }

    /** Pure-JCA RFC 2898 PBKDF2-HMAC-SHA256 fallback for API 24-25 devices. */
    private fun pbkdf2HmacSha256Fallback(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        dkLen: Int
    ): ByteArray {
        val passwordBytes = charArrayToUtf8Bytes(password)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))
            val hLen = mac.macLength
            val blockCount = (dkLen + hLen - 1) / hLen
            val output = ByteArray(blockCount * hLen)
            val blockIndexBytes = ByteArray(4)

            for (block in 1..blockCount) {
                blockIndexBytes[0] = (block ushr 24).toByte()
                blockIndexBytes[1] = (block ushr 16).toByte()
                blockIndexBytes[2] = (block ushr 8).toByte()
                blockIndexBytes[3] = block.toByte()

                mac.update(salt)
                mac.update(blockIndexBytes)
                var u = mac.doFinal()
                val t = u.copyOf()
                for (i in 1 until iterations) {
                    u = mac.doFinal(u)
                    for (j in t.indices) {
                        t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                    }
                }
                System.arraycopy(t, 0, output, (block - 1) * hLen, hLen)
            }
            return output.copyOf(dkLen)
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun charArrayToUtf8Bytes(chars: CharArray): ByteArray {
        val byteBuffer = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
        val bytes = ByteArray(byteBuffer.remaining())
        byteBuffer.get(bytes)
        if (byteBuffer.hasArray()) {
            byteBuffer.array().fill(0)
        }
        return bytes
    }

    private fun createTempSibling(target: File, suffix: String): File {
        val parent = target.parentFile ?: File(".")
        if (!parent.exists()) parent.mkdirs()
        return File(parent, ".${target.name}$suffix.${System.nanoTime()}.tmp")
    }

    private fun moveTempIntoPlace(tempFile: File, target: File) {
        if (target.exists() && !target.delete()) {
            throw IOException("Could not overwrite target file: ${target.name}")
        }
        if (!tempFile.renameTo(target)) {
            tempFile.inputStream().use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.delete()
        }
    }

    private fun replaceFileSafely(source: File, target: File) {
        val backup = createTempSibling(target, ".bak")
        if (target.exists() && !target.renameTo(backup)) {
            throw IOException("Could not stage backup for ${target.name}")
        }
        if (source.renameTo(target)) {
            backup.delete()
        } else {
            // Roll back original file if atomic swap failed.
            if (backup.exists()) backup.renameTo(target)
            throw IOException("Could not replace ${target.name} with migrated ciphertext")
        }
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray) {
        val read = readUpTo(input, buffer, buffer.size)
        if (read < buffer.size) throw EOFException("Unexpected end of encrypted file")
    }

    private fun readUpTo(input: java.io.InputStream, buffer: ByteArray, length: Int): Int {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return offset
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

    private fun logWarn(message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
        } catch (_: RuntimeException) {
            // Ignore in JVM unit tests where android.util.Log is not mocked.
        }
    }
}
