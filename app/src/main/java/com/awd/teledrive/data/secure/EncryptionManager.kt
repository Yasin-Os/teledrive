package com.awd.teledrive.data.secure

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptionManager @Inject constructor() {
    private val ALGORITHM = "AES/GCM/NoPadding"
    private val TAG_LENGTH_BIT = 128
    private val IV_LENGTH_BYTE = 12
    private val SALT_LENGTH_BYTE = 16
    // OWASP recommends 600,000 PBKDF2-HMAC-SHA256 iterations for passwords.
    private val ITERATION_COUNT = 600000
    // Files encrypted by older builds used 10,000 iterations; the count is
    // not stored in the file header, so decryption retries with this count.
    private val LEGACY_ITERATION_COUNT = 10000
    private val KEY_LENGTH_BIT = 256

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int = ITERATION_COUNT): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BIT)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    suspend fun encryptFile(inputFile: File, outputFile: File, password: String, onProgress: (Float) -> Unit = {}) {
        val salt = ByteArray(SALT_LENGTH_BYTE)
        SecureRandom().nextBytes(salt)
        
        val iv = ByteArray(IV_LENGTH_BYTE)
        SecureRandom().nextBytes(iv)
        
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        
        val totalSize = inputFile.length()
        var processedBytes = 0L
        var lastReportedProgress = -1f

        FileOutputStream(outputFile).use { fos ->
            // Write metadata first: Salt then IV
            fos.write(salt)
            fos.write(iv)
            
            CipherOutputStream(fos, cipher).use { cos ->
                inputFile.inputStream().use { fis ->
                    val buffer = ByteArray(32768) // Increased buffer to 32KB
                    var bytesRead = fis.read(buffer)
                    while (bytesRead != -1) {
                        cos.write(buffer, 0, bytesRead)
                        processedBytes += bytesRead
                        
                        if (totalSize > 0) {
                            val currentProgress = processedBytes.toFloat() / totalSize.toFloat()
                            // Only callback every 2% to reduce overhead during fast processing
                            if (currentProgress - lastReportedProgress >= 0.02f || currentProgress >= 1f) {
                                onProgress(currentProgress)
                                lastReportedProgress = currentProgress
                                // Small yield to prevent thread starvation during heavy IO
                                kotlinx.coroutines.yield()
                            }
                        }
                        bytesRead = fis.read(buffer)
                    }
                }
            }
        }
    }

    suspend fun decryptFile(encryptedFile: File, outputFile: File, password: String, onProgress: (Float) -> Unit = {}) {
        try {
            decryptWithIterations(encryptedFile, outputFile, password, ITERATION_COUNT, onProgress)
        } catch (e: BadPaddingException) {
            // GCM tag mismatch: either the password is wrong or the file was
            // encrypted by an older build with 10,000 iterations. Retry once
            // with the legacy count; a wrong password still fails here.
            Log.w("EncryptionManager", "Decrypt with current KDF failed, retrying with legacy iteration count", e)
            decryptWithIterations(encryptedFile, outputFile, password, LEGACY_ITERATION_COUNT, onProgress)
        }
    }

    private suspend fun decryptWithIterations(
        encryptedFile: File,
        outputFile: File,
        password: String,
        iterations: Int,
        onProgress: (Float) -> Unit
    ) {
        val totalSize = encryptedFile.length() - SALT_LENGTH_BYTE - IV_LENGTH_BYTE
        var processedBytes = 0L
        var lastReportedProgress = -1f

        FileInputStream(encryptedFile).use { fis ->
            val salt = ByteArray(SALT_LENGTH_BYTE)
            readFully(fis, salt)

            val iv = ByteArray(IV_LENGTH_BYTE)
            readFully(fis, iv)

            val key = deriveKey(password, salt, iterations)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))

            CipherInputStream(fis, cipher).use { cis ->
                FileOutputStream(outputFile).use { fos ->
                    val buffer = ByteArray(32768) // Increased buffer to 32KB
                    var bytesRead = cis.read(buffer)
                    while (bytesRead != -1) {
                        fos.write(buffer, 0, bytesRead)
                        processedBytes += bytesRead

                        if (totalSize > 0) {
                            val currentProgress = processedBytes.toFloat() / totalSize.toFloat()
                            // Only callback every 2%
                            if (currentProgress - lastReportedProgress >= 0.02f || currentProgress >= 1f) {
                                onProgress(currentProgress)
                                lastReportedProgress = currentProgress
                                kotlinx.coroutines.yield()
                            }
                        }
                        bytesRead = cis.read(buffer)
                    }
                }
            }
        }
    }

    // InputStream.read() is allowed to return fewer bytes than requested;
    // loop until the buffer is full so a short read is never mistaken for
    // a corrupt salt/IV.
    private fun readFully(input: FileInputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw Exception("Unexpected end of encrypted file")
            offset += read
        }
    }
}
