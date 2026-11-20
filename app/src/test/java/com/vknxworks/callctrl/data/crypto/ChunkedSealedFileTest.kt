package com.vknxworks.callctrl.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Random

/**
 * Encryption at rest of long call recordings: the chunked layout (`CALLCTRL-REST2`), that damage of every kind
 * is refused, and the loss-proof move from an ordinary file. Small chunk sizes stand in for the real 1 MiB.
 */
class ChunkedSealedFileTest {
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val header = 42
    private val overhead = 28

    private fun tempDir(): File = Files.createTempDirectory("callctrl-chunk-test").toFile()

    private fun bytes(n: Int, seed: Long = 1): ByteArray = ByteArray(n).also { Random(seed).nextBytes(it) }

    /** An ordinary file with [plain] in it, sealed in place with [chunk]-byte chunks. */
    private fun sealed(plain: ByteArray, chunk: Int, dir: File = tempDir(), name: String = "call.flac"): File {
        val file = File(dir, name).apply { writeBytes(plain) }
        val tmp = File(dir, "$name.seal.tmp")
        assertTrue(ChunkedSealedFile.sealInPlace(key, file, tmp, chunk))
        assertFalse(tmp.exists())
        assertTrue(ChunkedSealedFile.isChunked(file))
        return file
    }

    private fun open(file: File, k: ByteArray = key): ByteArray {
        val out = File(file.parentFile, file.name + ".out")
        ChunkedSealedFile.openTo(k, file, out)
        return out.readBytes().also { out.delete() }
    }

    /** True when neither way of opening [file] gives any result. */
    private fun refused(file: File, k: ByteArray = key): Boolean {
        val out = File(file.parentFile, file.name + ".out")
        val a = runCatching { ChunkedSealedFile.openTo(k, file, out) }.exceptionOrNull() is SealedDataException
        val noOutput = !out.exists()
        val b = runCatching { ChunkedSealedFile.inputStream(k, file).use { it.readBytes() } }.exceptionOrNull()
        return a && noOutput && (b is IOException || b is SealedDataException)
    }

    @Test
    fun severalChunksWithAShortLastOne() {
        val plain = bytes(1000)
        val file = sealed(plain, 64)
        assertEquals((header + 1000 + 16 * overhead).toLong(), file.length())
        assertEquals(file.length(), ChunkedSealedFile.sealedLength(1000, 64))
        assertArrayEquals(plain, open(file))
        assertArrayEquals(plain, ChunkedSealedFile.inputStream(key, file).use { it.readBytes() })
    }

    @Test
    fun aLengthThatIsAnExactNumberOfChunks() {
        val plain = bytes(640)
        val file = sealed(plain, 64)
        assertEquals((header + 640 + 10 * overhead).toLong(), file.length())
        assertArrayEquals(plain, open(file))
        // One full chunk and nothing after it.
        val one = sealed(bytes(64), 64)
        assertEquals((header + 64 + overhead).toLong(), one.length())
        assertArrayEquals(bytes(64), open(one))
    }

    @Test
    fun anEmptyFileHasOneEmptyChunk() {
        val file = sealed(ByteArray(0), 64)
        assertEquals((header + overhead).toLong(), file.length())
        assertArrayEquals(ByteArray(0), open(file))
        assertEquals(-1, ChunkedSealedFile.inputStream(key, file).use { it.read() })
        // Even the empty file is checked: another key doesn't open it.
        assertTrue(refused(file, ByteArray(32)))
    }

    @Test
    fun manySizesAndChunkSizesRoundTrip() {
        val dir = tempDir()
        for (chunk in intArrayOf(1, 2, 7, 16, 64, 4096)) {
            for (size in intArrayOf(0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 3 * chunk + 5, 10_000)) {
                if (size < 0) continue
                val plain = bytes(size, (chunk * 31 + size).toLong())
                val file = sealed(plain, chunk, dir, "c$chunk-s$size.wav")
                assertArrayEquals(plain, open(file))
                // Read through the stream in odd-sized pieces and one byte at a time.
                val viaStream = java.io.ByteArrayOutputStream()
                ChunkedSealedFile.inputStream(key, file).use { input ->
                    val buf = ByteArray(13)
                    while (true) {
                        val one = input.read()
                        if (one < 0) break
                        viaStream.write(one)
                        val n = input.read(buf, 2, 9)
                        if (n < 0) break
                        viaStream.write(buf, 2, n)
                    }
                }
                assertArrayEquals(plain, viaStream.toByteArray())
            }
        }
    }

    @Test
    fun theRealChunkSizeWorksAcrossAChunkBorder() {
        val plain = bytes(ChunkedSealedFile.CHUNK_SIZE * 2 + 12345)
        val dir = tempDir()
        val file = File(dir, "long.flac").apply { writeBytes(plain) }
        assertTrue(ChunkedSealedFile.sealInPlace(key, file, File(dir, "long.flac.seal.tmp")))
        assertEquals(ChunkedSealedFile.sealedLength(plain.size.toLong()), file.length())
        assertArrayEquals(plain, open(file))
    }

    @Test
    fun wrongKeyIsRefused() {
        val file = sealed(bytes(300), 64)
        assertTrue(refused(file, ByteArray(32)))
        assertTrue(refused(file, key.copyOf().also { it[31] = (it[31] + 1).toByte() }))
        assertFalse(refused(file))
    }

    @Test
    fun everySingleFlippedBitIsRefused() {
        val plain = bytes(150)
        val file = sealed(plain, 64)
        val good = file.readBytes()
        // Header (marker, chunk size, length, file id), every IV, every ciphertext byte and every tag.
        for (i in good.indices) {
            for (bit in intArrayOf(0, 7)) {
                file.writeBytes(good.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() })
                assertTrue("byte $i bit $bit", refused(file))
            }
        }
        file.writeBytes(good)
        assertArrayEquals(plain, open(file))
    }

    @Test
    fun cutShortAnywhereIsRefused() {
        val file = sealed(bytes(200), 64)
        val good = file.readBytes()
        for (len in 0 until good.size) {
            file.writeBytes(good.copyOf(len))
            assertTrue("cut to $len", refused(file))
        }
        // Extra bytes at the end too.
        file.writeBytes(good + byteArrayOf(0))
        assertTrue(refused(file))
        file.writeBytes(good + good.copyOfRange(header, header + 64 + overhead))
        assertTrue(refused(file))
    }

    /** The bytes of chunk [i] (IV, ciphertext, tag) in a file of full [chunk]-byte chunks. */
    private fun chunkOf(all: ByteArray, i: Int, chunk: Int): ByteArray =
        all.copyOfRange(header + i * (chunk + overhead), header + (i + 1) * (chunk + overhead))

    /** A header like [all]'s, saying [total] plain bytes. */
    private fun headerWithLength(all: ByteArray, total: Long): ByteArray {
        val h = all.copyOf(header)
        for (i in 0 until 8) h[18 + i] = (total ushr (56 - 8 * i)).toByte()
        return h
    }

    @Test
    fun movedRepeatedOrDroppedChunksAreRefused() {
        val chunk = 64
        val file = sealed(bytes(5 * chunk), chunk)
        val good = file.readBytes()
        val head = good.copyOf(header)
        val c = (0 until 5).map { chunkOf(good, it, chunk) }
        fun write(h: ByteArray, vararg parts: ByteArray) = file.writeBytes(parts.fold(h) { acc, p -> acc + p })

        write(head, c[0], c[2], c[1], c[3], c[4])
        assertTrue("two chunks swapped", refused(file))
        write(head, c[0], c[1], c[1], c[3], c[4])
        assertTrue("a chunk repeated over another", refused(file))
        write(head, c[4], c[1], c[2], c[3], c[0])
        assertTrue("first and last swapped", refused(file))
        // Chunks taken out, with and without the header changed to the new length.
        write(head, c[0], c[1], c[3], c[4])
        assertTrue("a middle chunk dropped", refused(file))
        write(headerWithLength(good, 4L * chunk), c[0], c[1], c[3], c[4])
        assertTrue("a middle chunk dropped, length changed to fit", refused(file))
        write(head, c[0], c[1], c[2], c[3])
        assertTrue("the last chunk dropped", refused(file))
        write(headerWithLength(good, 4L * chunk), c[0], c[1], c[2], c[3])
        assertTrue("the last chunk dropped, length changed to fit", refused(file))
        write(headerWithLength(good, 1L * chunk), c[0])
        assertTrue("only the first chunk, length changed to fit", refused(file))
        write(headerWithLength(good, 1L * chunk), c[4])
        assertTrue("only the last chunk, length changed to fit", refused(file))
        write(headerWithLength(good, 6L * chunk), c[0], c[1], c[2], c[3], c[4], c[4])
        assertTrue("a chunk added, length changed to fit", refused(file))

        write(head, c[0], c[1], c[2], c[3], c[4])
        assertFalse(refused(file))
    }

    @Test
    fun aChunkFromAnotherFileIsRefused() {
        val chunk = 64
        val dir = tempDir()
        val a = sealed(bytes(3 * chunk, 1), chunk, dir, "a.flac").readBytes()
        val bFile = sealed(bytes(3 * chunk, 2), chunk, dir, "b.flac")
        val b = bFile.readBytes()
        // Same key, same chunk size, same length, same place: only the file id differs.
        bFile.writeBytes(b.copyOf(header) + chunkOf(b, 0, chunk) + chunkOf(a, 1, chunk) + chunkOf(b, 2, chunk))
        assertTrue(refused(bFile))
        // And the whole body of one file under the other's header.
        bFile.writeBytes(b.copyOf(header) + a.copyOfRange(header, a.size))
        assertTrue(refused(bFile))
    }

    @Test
    fun aBadHeaderIsRefusedWithoutReadingOn() {
        val dir = tempDir()
        val file = File(dir, "x.flac")
        fun head(chunk: Int, total: Long): ByteArray {
            val h = "CALLCTRL-REST2".toByteArray() + ByteArray(28)
            for (i in 0 until 4) h[14 + i] = (chunk ushr (24 - 8 * i)).toByte()
            for (i in 0 until 8) h[18 + i] = (total ushr (56 - 8 * i)).toByte()
            return h
        }
        for (h in listOf(head(0, 10), head(-1, 10), head(ChunkedSealedFile.MAX_CHUNK_SIZE + 1, 10), head(64, -1), head(64, Long.MAX_VALUE), head(64, 1L shl 40))) {
            file.writeBytes(h + ByteArray(64 + 28))
            assertTrue(refused(file))
        }
        file.writeBytes("fLaC ordinary audio, not sealed at all, long enough to hold a header".toByteArray())
        assertFalse(ChunkedSealedFile.isChunked(file))
        assertTrue(refused(file))
        assertFalse(ChunkedSealedFile.isChunked(File(dir, "missing.flac")))
    }

    @Test
    fun theThreeKindsOfFileAreToldApart() {
        val dir = tempDir()
        val plain = bytes(500)
        val ordinary = File(dir, "o.flac").apply { writeBytes("fLaC".toByteArray() + plain) }
        val first = File(dir, "one.flac").apply { writeBytes(plain) }
        assertTrue(SealedFile.sealInPlace(key, first, File(dir, "one.tmp")))
        val second = sealed(plain, 64, dir, "two.flac")

        assertFalse(SealedFile.isSealed(ordinary))
        assertFalse(ChunkedSealedFile.isChunked(ordinary))
        // isSealed(file) is "sealed in either layout"; isChunked tells which.
        assertTrue(SealedFile.isSealed(first))
        assertFalse(ChunkedSealedFile.isChunked(first))
        assertTrue(SealedFile.isSealed(second))
        assertTrue(ChunkedSealedFile.isChunked(second))
        assertArrayEquals(plain, SealedFile.open(key, first.readBytes()))
        assertArrayEquals(plain, open(second))
        // Each opener refuses the other layout instead of giving wrong audio.
        assertTrue(refused(first))
        assertTrue(runCatching { SealedFile.open(key, second.readBytes()) }.exceptionOrNull() is SealedDataException)
    }

    @Test
    fun anAlreadySealedFileIsNeverRewritten() {
        val dir = tempDir()
        val plain = bytes(500)
        val second = sealed(plain, 64, dir, "two.flac")
        val before = second.readBytes()
        assertTrue(ChunkedSealedFile.sealInPlace(key, second, File(dir, "two.tmp"), 64))
        assertTrue(SealedFile.sealInPlace(key, second, File(dir, "two.tmp")))
        assertArrayEquals(before, second.readBytes())

        val first = File(dir, "one.flac").apply { writeBytes(plain) }
        assertTrue(SealedFile.sealInPlace(key, first, File(dir, "one.tmp")))
        val firstBefore = first.readBytes()
        assertTrue(ChunkedSealedFile.sealInPlace(key, first, File(dir, "one.tmp"), 64))
        assertArrayEquals(firstBefore, first.readBytes())
        assertFalse(File(dir, "one.tmp").exists())
        assertFalse(File(dir, "two.tmp").exists())
    }

    @Test
    fun sealingThatCannotFinishLeavesTheOrdinaryFile() {
        val dir = tempDir()
        val plain = bytes(1000)
        val file = File(dir, "call.flac").apply { writeBytes(plain) }

        // The temp file can't be written (its folder doesn't exist).
        assertFalse(ChunkedSealedFile.sealInPlace(key, file, File(File(dir, "missing"), "t.tmp"), 64))
        assertArrayEquals(plain, file.readBytes())

        // Stopped part-way while writing, and part-way while checking: nothing replaced, no temp file left.
        val tmp = File(dir, "call.flac.seal.tmp")
        for (stopAt in intArrayOf(0, 1, 5, 16, 17, 20, 32)) {
            var calls = 0
            assertFalse("stop at $stopAt", ChunkedSealedFile.sealInPlace(key, file, tmp, 64) { calls++ == stopAt })
            assertArrayEquals(plain, file.readBytes())
            assertFalse(tmp.exists())
        }

        // The file changes after its sealed copy was written (here: at the first check of the read-back).
        var calls = 0
        assertFalse(ChunkedSealedFile.sealInPlace(key, file, tmp, 64) {
            if (calls++ == 16) file.writeBytes(plain.copyOf().also { it[999] = (it[999] + 1).toByte() })
            false
        })
        assertFalse(ChunkedSealedFile.isChunked(file))
        assertFalse(tmp.exists())
        // ...or grows (same first bytes) while the copy is being checked.
        file.writeBytes(plain)
        calls = 0
        assertFalse(ChunkedSealedFile.sealInPlace(key, file, tmp, 64) {
            if (calls++ == 20) file.appendBytes(byteArrayOf(1, 2, 3))
            false
        })
        assertArrayEquals(plain + byteArrayOf(1, 2, 3), file.readBytes())
        assertFalse(tmp.exists())
        // ...or grows while it is being read for sealing.
        file.writeBytes(plain)
        calls = 0
        assertFalse(ChunkedSealedFile.sealInPlace(key, file, tmp, 64) {
            if (calls++ == 3) file.appendBytes(byteArrayOf(1, 2, 3))
            false
        })
        assertFalse(ChunkedSealedFile.isChunked(file))
        assertFalse(tmp.exists())

        // Left alone, the same file seals and opens.
        file.writeBytes(plain)
        assertTrue(ChunkedSealedFile.sealInPlace(key, file, tmp, 64))
        assertArrayEquals(plain, open(file))
    }

    @Test
    fun aFailedOpenLeavesNoOutputAndTheSealedFileAlone() {
        val file = sealed(bytes(1000), 64)
        val good = file.readBytes()
        // Damage in the last chunk: everything before it decrypts, the result must still not be kept.
        file.writeBytes(good.copyOf().also { it[it.size - 20] = (it[it.size - 20] + 1).toByte() })
        val damaged = file.readBytes()
        val out = File(file.parentFile, "copy.part")
        assertTrue(runCatching { ChunkedSealedFile.openTo(key, file, out) }.exceptionOrNull() is SealedDataException)
        assertFalse(out.exists())
        assertArrayEquals(damaged, file.readBytes())
        // The stream gives the good chunks, then fails instead of ending: a copy made from it is known bad.
        val got = java.io.ByteArrayOutputStream()
        val failure = runCatching { ChunkedSealedFile.inputStream(key, file).use { it.copyTo(got) } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(got.size() < 1000)
    }

    @Test
    fun twoSealsOfTheSameFileDiffer() {
        val plain = bytes(200)
        val a = sealed(plain, 64).readBytes()
        val b = sealed(plain, 64).readBytes()
        assertFalse(a.contentEquals(b))
        // Random file id and random IVs: no chunk repeats.
        assertFalse(chunkOf(a, 0, 64).contentEquals(chunkOf(b, 0, 64)))
    }
}
