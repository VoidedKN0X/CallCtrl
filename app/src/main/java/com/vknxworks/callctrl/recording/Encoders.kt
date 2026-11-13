package com.vknxworks.callctrl.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes interleaved samples (-1..1) into one recording file. */
interface Encoder {
    val sampleRate: Int
    val channels: Int
    /** Bits per sample in the file (16, or 32 for float WAV). */
    val bits: Int
    fun write(samples: FloatArray, frames: Int)
    /** Finishes the file; returns the frames written. */
    fun close(): Long
}

object Encoders {
    /** The encoder for [format]. Opus only takes 8, 12, 16, 24 or 48 kHz, so other rates are resampled to 48 kHz. */
    fun create(format: RecordFormat, file: File, sampleRate: Int, channels: Int, float: Boolean): Encoder = when (format) {
        RecordFormat.WAV -> WavEncoder(file, sampleRate, channels, float)
        RecordFormat.FLAC -> CodecEncoder(file, MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels, Output.FLAC)
        RecordFormat.AAC -> CodecEncoder(file, MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels, Output.MP4)
        RecordFormat.OPUS -> {
            val rate = if (sampleRate in OPUS_RATES) sampleRate else 48_000
            val enc = CodecEncoder(file, MediaFormat.MIMETYPE_AUDIO_OPUS, rate, channels, Output.OGG)
            if (rate == sampleRate) enc else ResamplingEncoder(enc, sampleRate)
        }
    }

    private val OPUS_RATES = setOf(8_000, 12_000, 16_000, 24_000, 48_000)

    /**
     * [wanted], or WAV when this phone has no encoder for it at this rate (WAV needs none), so a call is never lost
     * to a missing codec. The recording's details show the file type actually used.
     */
    fun available(wanted: RecordFormat, sampleRate: Int, channels: Int): RecordFormat {
        val mime = when (wanted) {
            RecordFormat.WAV -> return wanted
            RecordFormat.FLAC -> MediaFormat.MIMETYPE_AUDIO_FLAC
            RecordFormat.AAC -> MediaFormat.MIMETYPE_AUDIO_AAC
            RecordFormat.OPUS -> MediaFormat.MIMETYPE_AUDIO_OPUS
        }
        val rate = if (wanted == RecordFormat.OPUS && sampleRate !in OPUS_RATES) 48_000 else sampleRate
        val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        val ok = (1..channels).all { ch ->
            runCatching { list.findEncoderForFormat(MediaFormat.createAudioFormat(mime, rate, ch)) }.getOrNull() != null
        }
        return if (ok) wanted else RecordFormat.WAV
    }

    fun toPcm16(x: Float): Short = (x * 32768f).let { v ->
        when {
            v >= 32767f -> Short.MAX_VALUE
            v <= -32768f -> Short.MIN_VALUE
            else -> Math.round(v).toShort()
        }
    }
}

enum class Output { FLAC, MP4, OGG }

/** WAV: 16-bit PCM, or 32-bit float when the capture was float. The header's sizes are filled in at the end. */
class WavEncoder(file: File, override val sampleRate: Int, override val channels: Int, private val float: Boolean) : Encoder {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var buf = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
    override val bits: Int get() = if (float) 32 else 16

    init {
        raf.setLength(0)
        raf.write(WavHeader.build(sampleRate, channels, float, 0))
    }

    override fun write(samples: FloatArray, frames: Int) {
        val n = frames * channels
        val need = n * (if (float) 4 else 2)
        if (buf.capacity() < need) buf = ByteBuffer.allocate(need).order(ByteOrder.LITTLE_ENDIAN)
        buf.clear()
        if (float) for (i in 0 until n) buf.putFloat(samples[i]) else for (i in 0 until n) buf.putShort(Encoders.toPcm16(samples[i]))
        raf.write(buf.array(), 0, need)
        dataBytes += need
    }

    override fun close(): Long {
        try {
            raf.seek(0)
            raf.write(WavHeader.build(sampleRate, channels, float, dataBytes))
        } finally {
            raf.close()
        }
        return dataBytes / (channels * (if (float) 4 else 2))
    }
}

/**
 * Android's own encoders (FLAC, AAC, Opus) through MediaCodec. FLAC frames go straight into a .flac file (with the
 * header fixed up at the end, see [FlacHeader]); AAC goes into an .m4a and Opus into an .ogg through MediaMuxer.
 */
class CodecEncoder(
    private val file: File,
    mime: String,
    override val sampleRate: Int,
    override val channels: Int,
    private val output: Output,
) : Encoder {
    override val bits: Int = 16
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var framesIn = 0L
    private var pcm = ByteArray(0)

    // FLAC
    private var flac: RandomAccessFile? = null
    private var flacHeader: FlacHeader.Header? = null
    private var pendingCsd: ByteArray? = null

    // MP4 / OGG
    private var muxer: MediaMuxer? = null
    private var track = -1

    init {
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channels)
        when (output) {
            Output.FLAC -> format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
            Output.MP4 -> {
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                format.setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 2) 128_000 else 96_000)
            }
            Output.OGG -> format.setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 2) 64_000 else 48_000)
        }
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, sampleRate / 10 * channels * 2)
        val c = MediaCodec.createEncoderByType(mime)
        try {
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
        } catch (e: Exception) {
            c.release()
            throw IOException("encoder", e)
        }
        codec = c
        when (output) {
            Output.FLAC -> flac = RandomAccessFile(file, "rw").also { it.setLength(0) }
            Output.MP4 -> muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            Output.OGG -> muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        }
    }

    override fun write(samples: FloatArray, frames: Int) {
        val n = frames * channels
        if (pcm.size < n * 2) pcm = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = Encoders.toPcm16(samples[i]).toInt()
            pcm[2 * i] = s.toByte()
            pcm[2 * i + 1] = (s shr 8).toByte()
        }
        var offset = 0
        val total = n * 2
        while (offset < total) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) {
                drain(false)
                continue
            }
            val buffer = codec.getInputBuffer(index) ?: throw IOException("no input buffer")
            buffer.clear()
            val chunk = minOf(buffer.remaining() - buffer.remaining() % (channels * 2), total - offset)
            buffer.put(pcm, offset, chunk)
            codec.queueInputBuffer(index, 0, chunk, framesIn * 1_000_000L / sampleRate, 0)
            framesIn += chunk / (channels * 2)
            offset += chunk
            drain(false)
        }
    }

    override fun close(): Long {
        try {
            var queued = false
            for (i in 0 until 50) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, framesIn * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                    break
                }
                drain(false)
            }
            if (queued) drain(true)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            finishFile()
        }
        return framesIn
    }

    private fun finishFile() {
        flac?.let { f ->
            try {
                val header = flacHeader
                if (header != null) {
                    val bytes = header.bytes.copyOf()
                    FlacHeader.patchTotalSamples(bytes, header.streamInfoOffset, framesIn)
                    f.seek(0)
                    f.write(bytes)
                }
            } finally {
                f.close()
            }
        }
        muxer?.let { m ->
            try {
                if (track >= 0) m.stop()
            } finally {
                runCatching { m.release() }
            }
        }
    }

    /** Moves encoded output into the file. [untilEnd] waits for the end of the stream. */
    private fun drain(untilEnd: Boolean) {
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEnd || ++idle > 200) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    if (output == Output.FLAC) {
                        if (pendingCsd == null) format.getByteBuffer("csd-0")?.let { b -> pendingCsd = ByteArray(b.remaining()).also { b.duplicate().get(it) } }
                    } else {
                        val m = muxer ?: return
                        track = m.addTrack(format)
                        m.start()
                    }
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (output == Output.FLAC) {
                            val bytes = ByteArray(info.size).also { buffer.get(it) }
                            if (config) {
                                // A full header replaces what the output format gave; anything else follows it.
                                val prev = pendingCsd
                                pendingCsd = if (prev == null || isFlacMagic(bytes)) bytes else prev + bytes
                            } else {
                                writeFlacFrame(bytes)
                            }
                        } else if (!config && track >= 0) {
                            muxer?.writeSampleData(track, buffer, info)
                        }
                    }
                    val end = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(index, false)
                    if (end) return
                }
            }
        }
    }

    private fun isFlacMagic(b: ByteArray) = b.size >= 4 && b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() && b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte()

    private fun writeFlacFrame(bytes: ByteArray) {
        val f = flac ?: return
        if (flacHeader == null) {
            val header = pendingCsd?.let { FlacHeader.normalize(it) } ?: throw IOException("FLAC encoder gave no stream header")
            flacHeader = header
            f.seek(0)
            f.write(header.bytes)
        }
        f.write(bytes)
    }
}

/** Linear resampling in front of an encoder that can't take the capture's sample rate (Opus at 32 kHz). */
class ResamplingEncoder(private val inner: Encoder, private val inRate: Int) : Encoder {
    override val sampleRate: Int get() = inner.sampleRate
    override val channels: Int get() = inner.channels
    override val bits: Int get() = inner.bits
    private val step = inRate.toDouble() / inner.sampleRate
    private var pos = 0.0
    private val last = FloatArray(inner.channels)
    private var hasLast = false
    private var out = FloatArray(0)

    override fun write(samples: FloatArray, frames: Int) {
        if (frames <= 0) return
        val ch = channels
        val maxOut = ((frames + 1) / step).toInt() + 2
        if (out.size < maxOut * ch) out = FloatArray(maxOut * ch)
        var produced = 0
        // Position is relative to the previous block's last frame (index -1).
        while (true) {
            val i = Math.floor(pos).toInt()
            if (i + 1 > frames - 1) break
            val frac = (pos - i).toFloat()
            for (c in 0 until ch) {
                val a = if (i < 0) (if (hasLast) last[c] else samples[c]) else samples[i * ch + c]
                val b = samples[(i + 1) * ch + c]
                out[produced * ch + c] = a + (b - a) * frac
            }
            produced++
            pos += step
        }
        pos -= frames
        for (c in 0 until ch) last[c] = samples[(frames - 1) * ch + c]
        hasLast = true
        if (produced > 0) inner.write(out, produced)
    }

    override fun close(): Long = inner.close()
}
