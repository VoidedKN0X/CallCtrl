package com.vknxworks.callctrl.data.crypto

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A sealed file that can't be opened: the key is missing or not the one it was sealed with, or the file is damaged. */
class SealedDataException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Files encrypted at rest (finished call recordings), sealed with AES-256-GCM under the app's data key:
 *
 *     "CALLCTRL-REST1" | IV (12) | ciphertext + tag (16)
 *
 * A file that doesn't start with that marker is an ordinary file (a FLAC starts with `fLaC`, a WAV with
 * `RIFF`, an OGG with `OggS`) and is read as it is, so both kinds work side by side. Nothing here deletes a
 * readable file: a file is only ever replaced, in one step, by a copy that was first written out in full,
 * read back and checked.
 */
object SealedFile {
    private val MAGIC = "CALLCTRL-REST1".toByteArray(Charsets.US_ASCII)
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_LEN = 16
    private val random = SecureRandom()

    /** True when [bytes] start like a sealed file. */
    fun isSealed(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return false
        return true
    }

    /** True when [file] starts like a sealed file (only its first bytes are read). False when it can't be read. */
    fun isSealed(file: File): Boolean = try {
        val head = ByteArray(MAGIC.size)
        var read = 0
        file.inputStream().use { input ->
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
        }
        read == head.size && isSealed(head)
    } catch (e: Exception) {
        false
    }

    /** Encrypts [plain] with [key] (32 bytes). The result is built in one array (recordings are big). */
    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, iv))
        cipher.updateAAD(MAGIC)
        val headerEnd = MAGIC.size + IV_LEN
        val out = ByteArray(headerEnd + plain.size + TAG_LEN)
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        System.arraycopy(iv, 0, out, MAGIC.size, IV_LEN)
        val written = cipher.doFinal(plain, 0, plain.size, out, headerEnd)
        if (written != plain.size + TAG_LEN) throw IllegalStateException("unexpected ciphertext length")
        return out
    }

    /** Decrypts what [seal] made. Throws [SealedDataException] for a wrong key or a damaged file. */
    fun open(key: ByteArray, sealed: ByteArray): ByteArray {
        val headerEnd = MAGIC.size + IV_LEN
        if (!isSealed(sealed) || sealed.size < headerEnd + TAG_LEN) throw SealedDataException("not a sealed file")
        try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, sealed, MAGIC.size, IV_LEN))
            cipher.updateAAD(MAGIC)
            return cipher.doFinal(sealed, headerEnd, sealed.size - headerEnd)
        } catch (e: Exception) {
            throw SealedDataException("can't open the sealed file", e)
        }
    }

    /**
     * [plain] sealed with [key], checked by decrypting it again, or null when that check fails (the caller
     * then keeps writing the ordinary file).
     */
    fun sealChecked(key: ByteArray, plain: ByteArray): ByteArray? = try {
        val sealed = seal(key, plain)
        if (open(key, sealed).contentEquals(plain)) sealed else null
    } catch (e: Exception) {
        null
    }

    /**
     * Writes [bytes] to [tmp], makes sure they're on the disk, then puts [tmp] in [target]'s place in one step:
     * after a crash or power cut [target] is either the old file or the new one, never half of one.
     */
    fun writeAtomic(target: File, tmp: File, bytes: ByteArray) {
        writeSynced(tmp, bytes)
        if (!tmp.renameTo(target)) {
            // Only for a file system that can't rename over an existing file (Android's can). If the app
            // dies between these two lines, [tmp] still holds the newest copy.
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("can't replace ${target.name}")
        }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    /**
     * Turns the ordinary file [file] into a sealed one, without ever being without a readable copy:
     *
     * 1. the sealed copy is written to [tmp] and synced to disk; [file] isn't touched;
     * 2. [tmp] is read back from disk, decrypted, and compared byte for byte with [file] as it is on disk;
     * 3. only when they match, [tmp] is renamed over [file] (one atomic step; there is no separate delete).
     *
     * Any failure (running out of memory included) removes [tmp] and leaves [file] exactly as it was. A crash
     * before step 3 leaves the ordinary file plus a leftover [tmp]; a crash after it leaves the sealed file.
     * Returns true when [file] is sealed afterwards (also when it already was).
     *
     * The whole file is held in memory (twice, briefly): the caller keeps big files away from here.
     */
    fun sealInPlace(key: ByteArray, file: File, tmp: File): Boolean {
        try {
            if (isSealed(file)) return true
            writeSealedCopy(key, file, tmp)
            val back = open(key, tmp.readBytes())
            if (!sameAsFile(back, file)) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return false
            }
            return true
        } catch (e: Throwable) {
            try {
                tmp.delete()
            } catch (ignored: Throwable) {
            }
            return false
        }
    }

    /** Step 1 of [sealInPlace], on its own so the file's plain bytes are let go of before the read-back. */
    private fun writeSealedCopy(key: ByteArray, file: File, tmp: File) {
        writeSynced(tmp, seal(key, file.readBytes()))
    }

    /** True when [file] on disk holds exactly [bytes]. */
    private fun sameAsFile(bytes: ByteArray, file: File): Boolean {
        if (file.length() != bytes.size.toLong()) return false
        val buf = ByteArray(64 * 1024)
        var pos = 0
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (pos + n > bytes.size) return false
                for (i in 0 until n) if (buf[i] != bytes[pos + i]) return false
                pos += n
            }
        }
        return pos == bytes.size
    }
}
