package com.vknxworks.callctrl.data.crypto

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Big files encrypted at rest (long call recordings), sealed in pieces so that sealing and opening never hold
 * more than one piece in memory. Same data key and cipher (AES-256-GCM) as [SealedFile], second layout:
 *
 *     header (42 bytes):  "CALLCTRL-REST2" (14) | chunk size (int, 4) | plain length (long, 8) | file id (16, random)
 *     then, per chunk:    IV (12, random) | ciphertext (chunk size; the last one: what is left) | tag (16)
 *
 * Numbers are big-endian. A file always has at least one chunk (an empty file has one empty chunk), and only
 * the last chunk may be shorter than the chunk size. Every chunk is its own AES-GCM message whose associated
 * data is the whole header, the chunk's index (long) and one byte that is 1 for the last chunk and 0 otherwise.
 * So a chunk only opens at its own place in its own file:
 *
 * - swapping, repeating or leaving out chunks changes an index, and the chunk is refused;
 * - cutting the file short loses the chunk marked "last", and changing the length or chunk size in the header to
 *   hide that changes every chunk's associated data;
 * - a chunk from another sealed file has another file id.
 *
 * The file's length must also be exactly what the header says. Any of this failing is a [SealedDataException];
 * nothing here ever deletes or changes the sealed file it reads.
 */
object ChunkedSealedFile {
    private val MAGIC = "CALLCTRL-REST2".toByteArray(Charsets.US_ASCII)
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_LEN = 16
    private const val FILE_ID_LEN = 16
    private val HEADER_LEN = MAGIC.size + 4 + 8 + FILE_ID_LEN
    private const val AAD_EXTRA = 8 + 1
    private const val CHUNK_OVERHEAD = (IV_LEN + TAG_LEN).toLong()

    /** Plain bytes per chunk in the files this app writes. */
    const val CHUNK_SIZE = 1024 * 1024

    /** The biggest chunk size a reader accepts (what bounds its memory). */
    const val MAX_CHUNK_SIZE = 8 * 1024 * 1024

    private val random = SecureRandom()

    /** True when [file] starts like a chunked sealed file (only its first bytes are read). False when it can't be read. */
    fun isChunked(file: File): Boolean = try {
        val head = ByteArray(MAGIC.size)
        val read = FileInputStream(file).use { readUpTo(it, head, head.size) }
        read == head.size && head.contentEquals(MAGIC)
    } catch (e: Exception) {
        false
    }

    /** How many chunks a file of [total] plain bytes has. */
    private fun chunkCount(total: Long, chunkSize: Int): Long =
        if (total == 0L) 1L else (total - 1) / chunkSize + 1

    /** The exact size of the sealed file for [total] plain bytes. */
    fun sealedLength(total: Long, chunkSize: Int = CHUNK_SIZE): Long =
        HEADER_LEN + total + chunkCount(total, chunkSize) * CHUNK_OVERHEAD

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private fun putLong(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v ushr (56 - 8 * i)).toByte()
    }

    private fun getInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun getLong(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    /** Reads until [buf] holds [len] bytes or the stream ends; returns how many were read. */
    private fun readUpTo(input: InputStream, buf: ByteArray, len: Int): Int {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    /** The associated data of chunk [index]: the header, the index, and whether it is the last chunk. */
    private fun fillAad(aad: ByteArray, index: Long, last: Boolean) {
        putLong(aad, HEADER_LEN, index)
        aad[HEADER_LEN + 8] = if (last) 1 else 0
    }

    /**
     * Writes the sealed form of [source] to [out], one chunk at a time. [source] must hold exactly as many
     * bytes now as its length said when this started, or this throws. Throws when [abort] says to stop.
     */
    private fun sealStream(key: ByteArray, source: File, out: FileOutputStream, chunkSize: Int, abort: () -> Boolean) {
        require(chunkSize in 1..MAX_CHUNK_SIZE) { "bad chunk size" }
        val total = source.length()
        val header = ByteArray(HEADER_LEN)
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.size)
        putInt(header, MAGIC.size, chunkSize)
        putLong(header, MAGIC.size + 4, total)
        val fileId = ByteArray(FILE_ID_LEN).also(random::nextBytes)
        System.arraycopy(fileId, 0, header, MAGIC.size + 12, FILE_ID_LEN)
        val aad = ByteArray(HEADER_LEN + AAD_EXTRA)
        System.arraycopy(header, 0, aad, 0, HEADER_LEN)

        val secret = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance(AES_GCM)
        val plain = ByteArray(minOf(chunkSize.toLong(), maxOf(total, 1L)).toInt())
        val sealed = ByteArray(plain.size + TAG_LEN)
        val iv = ByteArray(IV_LEN)
        val chunks = chunkCount(total, chunkSize)

        out.write(header)
        FileInputStream(source).use { input ->
            var done = 0L
            for (index in 0 until chunks) {
                if (abort()) throw IOException("sealing was stopped")
                val want = minOf(chunkSize.toLong(), total - done).toInt()
                if (readUpTo(input, plain, want) != want) throw IOException("the file got shorter while it was sealed")
                random.nextBytes(iv)
                cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(TAG_LEN * 8, iv))
                fillAad(aad, index, index == chunks - 1)
                cipher.updateAAD(aad)
                val n = cipher.doFinal(plain, 0, want, sealed, 0)
                if (n != want + TAG_LEN) throw IllegalStateException("unexpected ciphertext length")
                out.write(iv)
                out.write(sealed, 0, n)
                done += want
            }
            if (input.read() >= 0) throw IOException("the file got longer while it was sealed")
        }
    }

    /**
     * Reads a chunked sealed file one chunk at a time, checking each chunk before handing it out. The header
     * and the file's length are checked when this is made. Close it when done.
     */
    class Reader(key: ByteArray, file: File) : Closeable {
        /** Plain bytes per chunk: the smallest buffer [next] can be given. */
        val chunkSize: Int

        /** The length of the whole plain file. */
        val total: Long

        private val input: FileInputStream
        private val secret = SecretKeySpec(key, "AES")
        private val cipher: Cipher
        private val aad = ByteArray(HEADER_LEN + AAD_EXTRA)
        private val iv = ByteArray(IV_LEN)
        private val sealed: ByteArray
        /** Where a chunk is decrypted into. A tag's length longer than a chunk: some AES-GCM providers ask for
         *  room for as many bytes as go in, although the tag doesn't come out. */
        private val opened: ByteArray
        private val chunks: Long
        private var index = 0L
        private var given = 0L
        private var ended = false

        init {
            val length = file.length()
            val stream = try {
                FileInputStream(file)
            } catch (e: Exception) {
                throw SealedDataException("can't read the sealed file", e)
            }
            try {
                val header = ByteArray(HEADER_LEN)
                if (readUpTo(stream, header, HEADER_LEN) != HEADER_LEN) throw SealedDataException("not a chunked sealed file")
                for (i in MAGIC.indices) if (header[i] != MAGIC[i]) throw SealedDataException("not a chunked sealed file")
                chunkSize = getInt(header, MAGIC.size)
                total = getLong(header, MAGIC.size + 4)
                if (chunkSize < 1 || chunkSize > MAX_CHUNK_SIZE) throw SealedDataException("bad chunk size")
                if (total < 0 || total > length) throw SealedDataException("bad length")
                chunks = chunkCount(total, chunkSize)
                if (length != sealedLength(total, chunkSize)) throw SealedDataException("the sealed file is not the size it should be")
                System.arraycopy(header, 0, aad, 0, HEADER_LEN)
                sealed = ByteArray(minOf(chunkSize.toLong(), maxOf(total, 1L)).toInt() + TAG_LEN)
                opened = ByteArray(sealed.size)
                cipher = Cipher.getInstance(AES_GCM)
                input = stream
            } catch (e: Throwable) {
                try {
                    stream.close()
                } catch (ignored: Throwable) {
                }
                if (e is SealedDataException) throw e
                throw SealedDataException("can't open the sealed file", e)
            }
        }

        /**
         * Decrypts the next chunk into [into] (at least `min(chunkSize, total)` bytes long) and returns how many
         * plain bytes it holds, or -1 once every chunk was read and the file ended exactly there. Throws
         * [SealedDataException] for a wrong key, damage, or chunks that are missing, moved or cut short.
         */
        fun next(into: ByteArray): Int {
            if (ended) return -1
            try {
                if (index == chunks) {
                    if (input.read() >= 0) throw SealedDataException("the sealed file has extra data at the end")
                    if (given != total) throw SealedDataException("the sealed file is incomplete")
                    ended = true
                    return -1
                }
                val want = minOf(chunkSize.toLong(), total - given).toInt()
                if (into.size < want) throw IllegalArgumentException("buffer too small")
                if (readUpTo(input, iv, IV_LEN) != IV_LEN) throw SealedDataException("the sealed file is cut short")
                if (readUpTo(input, sealed, want + TAG_LEN) != want + TAG_LEN) throw SealedDataException("the sealed file is cut short")
                cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_LEN * 8, iv))
                fillAad(aad, index, index == chunks - 1)
                cipher.updateAAD(aad)
                val n = cipher.doFinal(sealed, 0, want + TAG_LEN, opened, 0)
                if (n != want) throw SealedDataException("unexpected chunk length")
                System.arraycopy(opened, 0, into, 0, n)
                index++
                given += n
                return n
            } catch (e: SealedDataException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw SealedDataException("can't open the sealed file", e)
            }
        }

        override fun close() {
            try {
                input.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * Decrypts [sealed] into the new file [out], one chunk at a time. [out] is only complete when this returns;
     * on any failure it is deleted and a [SealedDataException] is thrown, so give it a name nothing else reads
     * yet and rename it afterwards. [sealed] is never changed.
     */
    fun openTo(key: ByteArray, sealed: File, out: File) {
        try {
            Reader(key, sealed).use { reader ->
                val buf = ByteArray(minOf(reader.chunkSize.toLong(), maxOf(reader.total, 1L)).toInt())
                FileOutputStream(out).use { o ->
                    while (true) {
                        val n = reader.next(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                    }
                    o.flush()
                }
            }
        } catch (e: Throwable) {
            try {
                out.delete()
            } catch (ignored: Throwable) {
            }
            if (e is SealedDataException) throw e
            throw SealedDataException("can't open the sealed file", e)
        }
    }

    /**
     * The plain bytes of [sealed] as a stream that decrypts one chunk at a time. Reading throws an
     * [IOException] at the first chunk that doesn't check out, and at the end when the file stops early, so a
     * reader that got to the end without an exception has the whole, checked file. Throws
     * [SealedDataException] right away when the header or the file's size is wrong.
     */
    fun inputStream(key: ByteArray, sealed: File): InputStream = ChunkStream(Reader(key, sealed))

    private class ChunkStream(private val reader: Reader) : InputStream() {
        private val buf = ByteArray(minOf(reader.chunkSize.toLong(), maxOf(reader.total, 1L)).toInt())
        private var pos = 0
        private var len = 0
        private var done = false

        /** Makes sure [buf] has unread bytes; false at the (checked) end. */
        private fun fill(): Boolean {
            while (pos >= len) {
                if (done) return false
                val n = try {
                    reader.next(buf)
                } catch (e: SealedDataException) {
                    throw IOException(e.message, e)
                }
                if (n < 0) {
                    done = true
                    return false
                }
                pos = 0
                len = n
            }
            return true
        }

        override fun read(): Int = if (fill()) buf[pos++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, n: Int): Int {
            if (n == 0) return 0
            if (!fill()) return -1
            val take = minOf(n, len - pos)
            System.arraycopy(buf, pos, b, off, take)
            pos += take
            return take
        }

        override fun close() = reader.close()
    }

    /** True when [sealed], decrypted chunk by chunk, is byte for byte the file [plain] as it is on disk now. */
    private fun sameAsFile(key: ByteArray, sealed: File, plain: File, abort: () -> Boolean): Boolean {
        Reader(key, sealed).use { reader ->
            if (reader.total != plain.length()) return false
            val size = minOf(reader.chunkSize.toLong(), maxOf(reader.total, 1L)).toInt()
            val a = ByteArray(size)
            val b = ByteArray(size)
            FileInputStream(plain).use { input ->
                var compared = 0L
                while (true) {
                    if (abort()) return false
                    val n = reader.next(a)
                    if (n < 0) break
                    if (readUpTo(input, b, n) != n) return false
                    for (i in 0 until n) if (a[i] != b[i]) return false
                    compared += n
                }
                if (input.read() >= 0) return false
                return compared == reader.total
            }
        }
    }

    /**
     * Turns the ordinary file [file] into a chunked sealed one, without ever being without a readable copy and
     * without holding more than a chunk in memory. The same steps as [SealedFile.sealInPlace]:
     *
     * 1. the sealed copy is written to [tmp], chunk by chunk, and synced to disk; [file] isn't touched;
     * 2. [tmp] is read back from disk, decrypted chunk by chunk, and compared byte for byte with [file] as it
     *    is on disk then (so a file that changed in the meantime is not replaced);
     * 3. only when they match, [tmp] is renamed over [file] (one atomic step; there is no separate delete).
     *
     * Any failure, and [abort] returning true at any chunk, removes [tmp] and leaves [file] exactly as it was.
     * A crash before step 3 leaves the ordinary file plus a leftover [tmp]; a crash after it leaves the sealed
     * file. Returns true when [file] is sealed afterwards (also when it already was, in either layout); a file
     * that is already sealed is never rewritten.
     */
    fun sealInPlace(key: ByteArray, file: File, tmp: File, chunkSize: Int = CHUNK_SIZE, abort: () -> Boolean = { false }): Boolean {
        try {
            if (isChunked(file) || SealedFile.isSealed(file)) return true
            FileOutputStream(tmp).use { out ->
                sealStream(key, file, out, chunkSize, abort)
                out.flush()
                out.fd.sync()
            }
            if (!sameAsFile(key, tmp, file, abort)) {
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
}
