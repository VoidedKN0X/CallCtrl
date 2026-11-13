package com.vknxworks.callctrl.recording

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WAV (RIFF) headers for 16-bit PCM and 32-bit float recordings. Pure Kotlin, unit-tested. */
object WavHeader {
    /** Size of the header [build] writes: RIFF + fmt (+ fact for float) + data chunk header. */
    fun size(float: Boolean): Int = if (float) 58 else 44

    /** The header for [dataBytes] bytes of interleaved little-endian samples. */
    fun build(sampleRate: Int, channels: Int, float: Boolean, dataBytes: Long): ByteArray {
        val bytesPerSample = if (float) 4 else 2
        val blockAlign = channels * bytesPerSample
        val data = dataBytes.coerceIn(0, 0xFFFF_FFFFL - size(float))
        val b = ByteBuffer.allocate(size(float)).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt((size(float) - 8 + data).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(if (float) 18 else 16)
        b.putShort((if (float) 3 else 1).toShort()) // 1 = PCM, 3 = IEEE float
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(sampleRate * blockAlign)
        b.putShort(blockAlign.toShort())
        b.putShort((bytesPerSample * 8).toShort())
        if (float) {
            b.putShort(0) // cbSize
            b.put("fact".toByteArray(Charsets.US_ASCII))
            b.putInt(4)
            b.putInt((data / blockAlign).toInt())
        }
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(data.toInt())
        return b.array()
    }
}

/**
 * FLAC file framing around Android's FLAC encoder. The encoder hands its stream header ("fLaC" and the metadata
 * blocks, STREAMINFO first) as codec config, then encoded frames. CallCtrl writes the header with the last block
 * correctly marked, the frames after it, and at the end puts the total number of samples into STREAMINFO (the
 * encoder can't go back to do it), so players show the length and can seek.
 */
object FlacHeader {
    private val MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
    const val STREAMINFO_LENGTH = 34

    /** The header to write and where STREAMINFO's body starts in it. */
    class Header(val bytes: ByteArray, val streamInfoOffset: Int)

    /**
     * Turns the encoder's codec config into a valid FLAC header: "fLaC", then every complete metadata block, the
     * last one flagged as last. A bare 34-byte STREAMINFO (no magic) is wrapped too. Null when there's no
     * STREAMINFO.
     */
    fun normalize(csd: ByteArray): Header? {
        if (csd.size == STREAMINFO_LENGTH) {
            return Header(MAGIC + byteArrayOf(0x80.toByte(), 0, 0, STREAMINFO_LENGTH.toByte()) + csd, 8)
        }
        if (csd.size < 8 + STREAMINFO_LENGTH || !csd.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val blocks = ArrayList<Pair<Int, Int>>() // start of block header, total length
        var p = 4
        while (p + 4 <= csd.size) {
            val type = csd[p].toInt() and 0x7F
            val len = ((csd[p + 1].toInt() and 0xFF) shl 16) or ((csd[p + 2].toInt() and 0xFF) shl 8) or (csd[p + 3].toInt() and 0xFF)
            if (p + 4 + len > csd.size) break // truncated block: dropped
            if (blocks.isEmpty() && (type != 0 || len != STREAMINFO_LENGTH)) return null
            blocks += p to 4 + len
            val last = (csd[p].toInt() and 0x80) != 0
            p += 4 + len
            if (last) break
        }
        if (blocks.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream()
        out.write(MAGIC)
        blocks.forEachIndexed { i, (start, len) ->
            val block = csd.copyOfRange(start, start + len)
            val type = block[0].toInt() and 0x7F
            block[0] = (if (i == blocks.lastIndex) type or 0x80 else type).toByte()
            out.write(block)
        }
        return Header(out.toByteArray(), 8)
    }

    /** Writes [totalSamples] (per channel) into a STREAMINFO body in place (36 bits, from byte 13). */
    fun patchTotalSamples(streamInfo: ByteArray, offset: Int, totalSamples: Long) {
        val t = totalSamples.coerceIn(0, (1L shl 36) - 1)
        val i = offset + 13
        streamInfo[i] = ((streamInfo[i].toInt() and 0xF0) or ((t ushr 32).toInt() and 0x0F)).toByte()
        streamInfo[i + 1] = (t ushr 24).toByte()
        streamInfo[i + 2] = (t ushr 16).toByte()
        streamInfo[i + 3] = (t ushr 8).toByte()
        streamInfo[i + 4] = t.toByte()
    }

    /** Reads the sample rate, channels and bits per sample from a STREAMINFO body. */
    fun info(streamInfo: ByteArray, offset: Int): Triple<Int, Int, Int> {
        val b = { i: Int -> streamInfo[offset + i].toInt() and 0xFF }
        val rate = (b(10) shl 12) or (b(11) shl 4) or (b(12) ushr 4)
        val channels = ((b(12) ushr 1) and 0x07) + 1
        val bits = (((b(12) and 0x01) shl 4) or (b(13) ushr 4)) + 1
        return Triple(rate, channels, bits)
    }
}
