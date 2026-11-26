package com.awd.teledrive.data.secure

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class VaultCryptoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val encryptionManager = EncryptionManager()
    private val random = SecureRandom()

    private fun writeLegacyV1EncryptedFile(
        plaintext: ByteArray,
        pin: String,
        iterations: Int,
        outFile: File
    ) {
        val salt = ByteArray(EncryptionManager.SALT_LENGTH_BYTE).also { random.nextBytes(it) }
        val iv = ByteArray(EncryptionManager.IV_LENGTH_BYTE).also { random.nextBytes(it) }
        val keyBytes = encryptionManager.deriveKekPbkdf2(pin.toCharArray(), salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)
        FileOutputStream(outFile).use { fos ->
            fos.write(salt)
            fos.write(iv)
            fos.write(ciphertext)
        }
        keyBytes.fill(0)
    }

    @Test
    fun generateDek_producesDistinct256BitKeys() {
        val dek1 = encryptionManager.generateDek()
        val dek2 = encryptionManager.generateDek()
        assertEquals(32, dek1.size)
        assertEquals(32, dek2.size)
        assertFalse(Arrays.equals(dek1, dek2))
    }

    @Test
    fun wrapAndUnwrapDekWithPin_pbkdf2_roundTripAndPinChange() {
        val dek = encryptionManager.generateDek()
        val wrappedOld = encryptionManager.wrapDekWithPin(dek, "1234".toCharArray())
        assertTrue(wrappedOld.startsWith("v2:pbkdf2:600000:"))

        // Correct PIN recovers exact DEK.
        val unwrapped = encryptionManager.unwrapDekWithPin(wrappedOld, "1234".toCharArray())
        assertNotNull(unwrapped)
        assertArrayEquals(dek, unwrapped)

        // Wrong PIN fails closed.
        assertNull(encryptionManager.unwrapDekWithPin(wrappedOld, "9999".toCharArray()))

        // Tampered wrapped ciphertext fails closed.
        val tampered = wrappedOld.dropLast(2) + if (wrappedOld.endsWith("ff")) "00" else "ff"
        assertNull(encryptionManager.unwrapDekWithPin(tampered, "1234".toCharArray()))

        // Re-wrapping the DEK under a new PIN preserves the exact same DEK.
        val wrappedNew = encryptionManager.wrapDekWithPin(unwrapped!!, "567890".toCharArray())
        assertNull(encryptionManager.unwrapDekWithPin(wrappedNew, "1234".toCharArray()))
        val unwrappedNew = encryptionManager.unwrapDekWithPin(wrappedNew, "567890".toCharArray())
        assertArrayEquals(dek, unwrappedNew)
    }

    @Test
    fun wrapAndUnwrapDekWithPin_argon2DeriverHook() {
        val dek = encryptionManager.generateDek()
        val fakeArgon2: (CharArray, ByteArray) -> ByteArray? = { pin, salt ->
            // Deterministic 32-byte derivation simulating Argon2id in JVM tests.
            encryptionManager.deriveKekPbkdf2(pin, salt, 1_000)
        }
        val wrapped = encryptionManager.wrapDekWithPin(dek, "4321".toCharArray(), fakeArgon2)
        assertTrue(wrapped.startsWith("v2:argon2id:t3:m65536:p1:"))

        val recovered = encryptionManager.unwrapDekWithPin(wrapped, "4321".toCharArray(), fakeArgon2)
        assertArrayEquals(dek, recovered)
        assertNull(encryptionManager.unwrapDekWithPin(wrapped, "0000".toCharArray(), fakeArgon2))
    }

    @Test
    fun wrapAndUnwrapDekWithCipher_keystoreCryptoObjectSemantics() {
        val dek = encryptionManager.generateDek()
        val ksKey = SecretKeySpec(encryptionManager.generateDek(), "AES")
        val wrongKey = SecretKeySpec(encryptionManager.generateDek(), "AES")

        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, ksKey)
        }
        val wrapped = encryptionManager.wrapDekWithCipher(dek, encryptCipher)
        assertTrue(wrapped.startsWith("v2:ks:"))

        val iv = encryptionManager.extractBiometricWrapIv(wrapped)
        assertNotNull(iv)
        assertEquals(12, iv!!.size)

        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, ksKey, GCMParameterSpec(128, iv))
        }
        assertArrayEquals(dek, encryptionManager.unwrapDekWithCipher(wrapped, decryptCipher))

        val wrongCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, wrongKey, GCMParameterSpec(128, iv))
        }
        assertNull(encryptionManager.unwrapDekWithCipher(wrapped, wrongCipher))
    }

    @Test
    fun v2FileEncryptDecrypt_emptySmallAndMultiChunk() = runBlocking {
        val dek = encryptionManager.generateDek()
        val payloads = listOf(
            ByteArray(0),
            "hello mycloud vault".toByteArray(),
            ByteArray(65536) { (it % 251).toByte() },
            ByteArray(210_000) { ((it * 31) % 256).toByte() }
        )

        payloads.forEachIndexed { idx, data ->
            val plainIn = tempFolder.newFile("in_$idx.bin").apply { writeBytes(data) }
            val encFile = tempFolder.newFile("enc_$idx.enc")
            val plainOut = File(tempFolder.root, "out_$idx.bin")

            encryptionManager.encryptFile(plainIn, encFile, dek)
            assertTrue(encryptionManager.isV2EncryptedFile(encFile))

            encryptionManager.decryptFile(encFile, plainOut, dek)
            assertArrayEquals(data, plainOut.readBytes())
        }
    }

    @Test
    fun v2FileDecrypt_rejectsWrongKeyTamperTruncationAndTrailingBytes() = runBlocking {
        val dek = encryptionManager.generateDek()
        val wrongDek = encryptionManager.generateDek()
        val payload = ByteArray(150_000) { (it % 199).toByte() }

        val plainIn = tempFolder.newFile("source.bin").apply { writeBytes(payload) }
        val encFile = tempFolder.newFile("source.enc")
        encryptionManager.encryptFile(plainIn, encFile, dek)

        // 1. Wrong DEK must throw AEADBadTagException and leave no output file.
        val outWrongKey = File(tempFolder.root, "wrong_key.bin")
        try {
            encryptionManager.decryptFile(encFile, outWrongKey, wrongDek)
            fail("Expected AEADBadTagException for wrong DEK")
        } catch (_: AEADBadTagException) {
            assertFalse(outWrongKey.exists())
        }

        // 2. Single-bit ciphertext tamper must fail and leave no output file.
        val tamperedFile = tempFolder.newFile("tampered.enc").apply {
            val bytes = encFile.readBytes()
            bytes[bytes.size - 10] = (bytes[bytes.size - 10].toInt() xor 0x01).toByte()
            writeBytes(bytes)
        }
        val outTampered = File(tempFolder.root, "tampered.bin")
        try {
            encryptionManager.decryptFile(tamperedFile, outTampered, dek)
            fail("Expected AEADBadTagException for tampered ciphertext")
        } catch (_: AEADBadTagException) {
            assertFalse(outTampered.exists())
        }

        // 3. Truncated file (missing final chunk) must fail.
        val truncatedFile = tempFolder.newFile("truncated.enc").apply {
            val bytes = encFile.readBytes()
            writeBytes(bytes.copyOf(bytes.size - 32))
        }
        val outTruncated = File(tempFolder.root, "truncated.bin")
        try {
            encryptionManager.decryptFile(truncatedFile, outTruncated, dek)
            fail("Expected AEADBadTagException for truncated ciphertext")
        } catch (_: AEADBadTagException) {
            assertFalse(outTruncated.exists())
        }

        // 4. Trailing byte after final chunk must fail.
        val trailingFile = tempFolder.newFile("trailing.enc").apply {
            writeBytes(encFile.readBytes() + byteArrayOf(0x42))
        }
        val outTrailing = File(tempFolder.root, "trailing.bin")
        try {
            encryptionManager.decryptFile(trailingFile, outTrailing, dek)
            fail("Expected AEADBadTagException for trailing bytes")
        } catch (_: AEADBadTagException) {
            assertFalse(outTrailing.exists())
        }
    }

    @Test
    fun legacyDecrypt_supportsBoth600kAnd10kIterations_andFailsClosedOnWrongPin() = runBlocking {
        val payload600k = "legacy 600k secret payload".toByteArray()
        val payload10k = "legacy 10k ancient payload".toByteArray()

        val legacy600kFile = tempFolder.newFile("legacy_600k.enc")
        val legacy10kFile = tempFolder.newFile("legacy_10k.enc")
        writeLegacyV1EncryptedFile(payload600k, "1234", EncryptionManager.LEGACY_CURRENT_ITERATION_COUNT, legacy600kFile)
        writeLegacyV1EncryptedFile(payload10k, "1234", EncryptionManager.LEGACY_OLD_ITERATION_COUNT, legacy10kFile)

        assertFalse(encryptionManager.isV2EncryptedFile(legacy600kFile))
        assertFalse(encryptionManager.isV2EncryptedFile(legacy10kFile))

        // v2 decryptFile must identify legacy format with LegacyEncryptedFileException.
        val dek = encryptionManager.generateDek()
        try {
            encryptionManager.decryptFile(legacy600kFile, File(tempFolder.root, "nope.bin"), dek)
            fail("Expected LegacyEncryptedFileException")
        } catch (_: LegacyEncryptedFileException) {
            // Expected
        }

        // Fixed legacy decrypt handles 600k directly.
        val out600k = File(tempFolder.root, "out_600k.bin")
        encryptionManager.decryptLegacyFile(legacy600kFile, out600k, "1234".toCharArray())
        assertArrayEquals(payload600k, out600k.readBytes())

        // Fixed legacy decrypt retries and succeeds on 10k (previously dead code!).
        val out10k = File(tempFolder.root, "out_10k.bin")
        encryptionManager.decryptLegacyFile(legacy10kFile, out10k, "1234".toCharArray())
        assertArrayEquals(payload10k, out10k.readBytes())

        // Wrong PIN fails with BadPaddingException / AEADBadTagException and leaves no partial output.
        val outBadPin = File(tempFolder.root, "out_bad_pin.bin")
        try {
            encryptionManager.decryptLegacyFile(legacy10kFile, outBadPin, "0000".toCharArray())
            fail("Expected BadPaddingException for wrong legacy PIN")
        } catch (_: BadPaddingException) {
            assertFalse(outBadPin.exists())
        }
    }

    @Test
    fun pinChangeMigration_migratesLegacyFilesToDekWithoutOrphaning() = runBlocking {
        val oldPin = "1234"
        val newPin = "987654"
        val payload600k = "doc encrypted under old pin 600k".toByteArray()
        val payload10k = "photo encrypted under old pin 10k".toByteArray()

        val file600k = tempFolder.newFile("doc.enc")
        val file10k = tempFolder.newFile("photo.enc")
        writeLegacyV1EncryptedFile(payload600k, oldPin, EncryptionManager.LEGACY_CURRENT_ITERATION_COUNT, file600k)
        writeLegacyV1EncryptedFile(payload10k, oldPin, EncryptionManager.LEGACY_OLD_ITERATION_COUNT, file10k)

        // Generate per-vault DEK and migrate both legacy files at PIN-change time.
        val dek = encryptionManager.generateDek()
        assertTrue(encryptionManager.migrateLegacyFileToDek(file600k, oldPin.toCharArray(), dek))
        assertTrue(encryptionManager.migrateLegacyFileToDek(file10k, oldPin.toCharArray(), dek))
        // Second call on already-v2 file is a no-op returning false.
        assertFalse(encryptionManager.migrateLegacyFileToDek(file600k, oldPin.toCharArray(), dek))

        // Re-wrap DEK under new PIN only.
        val wrappedUnderNewPin = encryptionManager.wrapDekWithPin(dek, newPin.toCharArray())
        dek.fill(0)

        // Unwrap DEK using the NEW PIN and decrypt both migrated files cleanly.
        val recoveredDek = encryptionManager.unwrapDekWithPin(wrappedUnderNewPin, newPin.toCharArray())
        assertNotNull(recoveredDek)

        val dec600k = File(tempFolder.root, "dec_doc.bin")
        val dec10k = File(tempFolder.root, "dec_photo.bin")
        encryptionManager.decryptFile(file600k, dec600k, recoveredDek!!)
        encryptionManager.decryptFile(file10k, dec10k, recoveredDek)

        assertArrayEquals(payload600k, dec600k.readBytes())
        assertArrayEquals(payload10k, dec10k.readBytes())
    }

    @Test
    fun secureSessionManager_defensiveCopiesAndWipes() {
        val session = SecureSessionManager()
        assertFalse(session.isUnlocked())
        assertFalse(session.isSessionUnlocked.value)
        assertNull(session.getSessionKeyCopy())

        val dek = encryptionManager.generateDek()
        val expected = dek.copyOf()
        session.setSessionKey(dek)

        // Mutating caller's original array must not alter stored session DEK.
        dek.fill(0)
        assertTrue(session.isUnlocked())
        assertTrue(session.isSessionUnlocked.value)

        val copy1 = session.getSessionKeyCopy()
        assertArrayEquals(expected, copy1)

        // Zeroing caller's copy must not alter stored session DEK.
        copy1!!.fill(0)
        val copy2 = session.getSessionKeyCopy()
        assertArrayEquals(expected, copy2)
        copy2!!.fill(0)

        session.clearSession()
        assertFalse(session.isUnlocked())
        assertFalse(session.isSessionUnlocked.value)
        assertNull(session.getSessionKeyCopy())
    }
}
