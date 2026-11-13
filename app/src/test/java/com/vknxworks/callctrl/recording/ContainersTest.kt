package com.vknxworks.callctrl.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ContainersTest {

    private fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)
    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun wav_pcm16_header() {
        val h = WavHeader.build(48_000, 1, float = false, dataBytes = 96_000)
        assertEquals(44, h.size)
        val b = le(h)
        assertEquals("RIFF", ascii(h, 0))
        assertEquals(36 + 96_000, b.getInt(4))
        assertEquals("WAVE", ascii(h, 8))
        assertEquals("fmt ", ascii(h, 12))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(48_000, b.getInt(24))
        assertEquals(96_000, b.getInt(28)) // byte rate
        assertEquals(2, b.getShort(32).toInt()) // block align
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", ascii(h, 36))
        assertEquals(96_000, b.getInt(40))
    }

    @Test
    fun wav_float_stereo_header_has_fact_chunk() {
        val h = WavHeader.build(32_000, 2, float = true, dataBytes = 8 * 1000)
        assertEquals(58, h.size)
        assertEquals(WavHeader.size(true), h.size)
        val b = le(h)
        assertEquals(50 + 8000, b.getInt(4))
        assertEquals(18, b.getInt(16))
        assertEquals(3, b.getShort(20).toInt()) // IEEE float
        assertEquals(2, b.getShort(22).toInt())
        assertEquals(32_000 * 8, b.getInt(28))
        assertEquals(8, b.getShort(32).toInt())
        assertEquals(32, b.getShort(34).toInt())
        assertEquals(0, b.getShort(36).toInt()) // cbSize
        assertEquals("fact", ascii(h, 38))
        assertEquals(4, b.getInt(42))
        assertEquals(1000, b.getInt(46)) // frames
        assertEquals("data", ascii(h, 50))
        assertEquals(8000, b.getInt(54))
    }

    /** A STREAMINFO body: 48 kHz, [channels], 16-bit, [total] samples, as libFLAC writes it. */
    private fun streamInfo(channels: Int = 1, total: Long = 0): ByteArray {
        val s = ByteArray(34)
        s[0] = 0x10; s[1] = 0x00; s[2] = 0x10; s[3] = 0x00 // block sizes 4096
        val rate = 48_000
        s[10] = (rate ushr 12).toByte()
        s[11] = (rate ushr 4).toByte()
        s[12] = (((rate and 0x0F) shl 4) or ((channels - 1) shl 1) or ((16 - 1) ushr 4)).toByte()
        s[13] = ((((16 - 1) and 0x0F) shl 4) or ((total ushr 32).toInt() and 0x0F)).toByte()
        s[14] = (total ushr 24).toByte(); s[15] = (total ushr 16).toByte(); s[16] = (total ushr 8).toByte(); s[17] = total.toByte()
        return s
    }

    private val magic = "fLaC".toByteArray(Charsets.US_ASCII)
    private fun block(type: Int, last: Boolean, body: ByteArray) =
        byteArrayOf(((if (last) 0x80 else 0) or type).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body

    @Test
    fun flac_header_already_fine_is_kept() {
        val csd = magic + block(0, true, streamInfo())
        val h = FlacHeader.normalize(csd)!!
        assertArrayEquals(csd, h.bytes)
        assertEquals(8, h.streamInfoOffset)
        assertEquals(Triple(48_000, 1, 16), FlacHeader.info(h.bytes, h.streamInfoOffset))
    }

    @Test
    fun flac_header_marks_the_real_last_block() {
        // STREAMINFO not flagged last, then a vorbis comment cut off in the middle (the encoder's header buffer).
        val comment = ByteArray(40) { 7 }
        val csd = magic + block(0, false, streamInfo(2)) + block(4, false, comment).copyOf(4 + 20)
        val h = FlacHeader.normalize(csd)!!
        assertEquals(4 + 4 + 34, h.bytes.size)
        assertEquals(0x80, h.bytes[4].toInt() and 0xFF)
        assertEquals(Triple(48_000, 2, 16), FlacHeader.info(h.bytes, 8))
    }

    @Test
    fun flac_header_keeps_complete_extra_blocks() {
        val pad = ByteArray(8)
        val csd = magic + block(0, false, streamInfo()) + block(1, false, pad)
        val h = FlacHeader.normalize(csd)!!
        assertEquals(csd.size, h.bytes.size)
        assertEquals(0x00, h.bytes[4].toInt() and 0xFF) // STREAMINFO not last
        assertEquals(0x81, h.bytes[42].toInt() and 0xFF) // padding is now last
    }

    @Test
    fun flac_bare_streaminfo_is_wrapped() {
        val s = streamInfo()
        val h = FlacHeader.normalize(s)!!
        assertEquals(42, h.bytes.size)
        assertEquals("fLaC", String(h.bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(0x80, h.bytes[4].toInt() and 0xFF)
        assertEquals(34, h.bytes[7].toInt())
    }

    @Test
    fun flac_without_streaminfo_is_refused() {
        assertNull(FlacHeader.normalize(ByteArray(10)))
        assertNull(FlacHeader.normalize(magic + block(4, true, ByteArray(34))))
        assertNull(FlacHeader.normalize("OggS".toByteArray() + ByteArray(60)))
    }

    @Test
    fun flac_total_samples_are_patched_in_place() {
        val h = FlacHeader.normalize(magic + block(0, true, streamInfo(channels = 2)))!!
        val bytes = h.bytes.copyOf()
        val total = 0x9_1234_5678L // needs all 36 bits
        FlacHeader.patchTotalSamples(bytes, h.streamInfoOffset, total)
        val expect = streamInfo(channels = 2, total = total)
        assertArrayEquals(expect, bytes.copyOfRange(8, 42))
        // Rate, channels and bits untouched.
        assertEquals(Triple(48_000, 2, 16), FlacHeader.info(bytes, 8))
    }

    @Test
    fun pcm16_conversion_is_lossless_and_clamped() {
        for (s in listOf(-32768, -1, 0, 1, 12345, 32767)) {
            assertEquals(s.toShort(), Encoders.toPcm16(s / 32768f))
        }
        assertEquals(Short.MAX_VALUE, Encoders.toPcm16(1.5f))
        assertEquals(Short.MIN_VALUE, Encoders.toPcm16(-2f))
        assertEquals(Short.MAX_VALUE, Encoders.toPcm16(1f))
    }
}
