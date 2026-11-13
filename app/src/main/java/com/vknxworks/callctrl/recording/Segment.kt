package com.vknxworks.callctrl.recording

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One recording file: takes the capture's raw PCM, decides between stereo and mono (see [ChannelAnalyzer]),
 * and feeds the encoder. Not thread-safe: the recorder calls it from its reader thread only.
 */
class Segment(
    val file: File,
    private val capture: CaptureFormat,
    private val format: RecordFormat,
) {
    private var encoder: Encoder? = null
    private val analyzer = if (capture.channels == 2) ChannelAnalyzer(capture.sampleRate) else null
    private val pending = ArrayList<FloatArray>()
    private var pendingFrames = 0
    private var samples = FloatArray(0)
    private var left = FloatArray(0)
    private var right = FloatArray(0)
    private var mono = FloatArray(0)
    private var keepStereo: Boolean? = if (capture.channels == 2) null else false
    var framesWritten = 0L
        private set

    val outChannels: Int get() = if (keepStereo == true) 2 else 1
    val outBits: Int get() = encoder?.bits ?: if (format == RecordFormat.WAV && capture.float) 32 else 16
    val outRate: Int get() = encoder?.sampleRate ?: capture.sampleRate

    /** [len] bytes of whole frames in the capture format. */
    fun write(bytes: ByteArray, len: Int) {
        val frames = len / capture.frameBytes
        if (frames <= 0) return
        val n = frames * capture.channels
        if (samples.size < n) samples = FloatArray(n)
        val bb = ByteBuffer.wrap(bytes, 0, frames * capture.frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (capture.float) for (i in 0 until n) samples[i] = bb.getFloat() else for (i in 0 until n) samples[i] = bb.getShort() / 32768f

        if (keepStereo == null) {
            if (left.size < frames) { left = FloatArray(frames); right = FloatArray(frames) }
            for (i in 0 until frames) { left[i] = samples[2 * i]; right[i] = samples[2 * i + 1] }
            pending += samples.copyOf(n)
            pendingFrames += frames
            val decided = analyzer!!.feed(left, right, frames)
            if (decided != null) {
                keepStereo = decided
                flushPending()
            }
            return
        }
        emit(samples, frames)
    }

    private fun flushPending() {
        val list = ArrayList(pending)
        pending.clear()
        pendingFrames = 0
        for (block in list) emit(block, block.size / 2)
    }

    /** Interleaved capture samples → the encoder, downmixed to mono when the two channels are the same. */
    private fun emit(src: FloatArray, frames: Int) {
        val enc = encoder ?: Encoders.create(format, file, capture.sampleRate, outChannels, capture.float).also { encoder = it }
        if (capture.channels == 2 && outChannels == 1) {
            if (mono.size < frames) mono = FloatArray(frames)
            for (i in 0 until frames) mono[i] = (src[2 * i] + src[2 * i + 1]) * 0.5f
            enc.write(mono, frames)
        } else {
            enc.write(src, frames)
        }
        framesWritten += frames
    }

    /** Finishes the file. Still undecided (hardly any sound): stereo is kept, so nothing is lost. */
    fun close(): Long {
        if (keepStereo == null) {
            keepStereo = true
            flushPending()
        }
        val enc = encoder ?: Encoders.create(format, file, capture.sampleRate, outChannels, capture.float).also { encoder = it }
        return enc.close()
    }
}
