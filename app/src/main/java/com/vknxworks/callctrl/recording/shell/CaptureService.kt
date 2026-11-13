package com.vknxworks.callctrl.recording.shell

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import com.vknxworks.callctrl.recording.Candidate
import com.vknxworks.callctrl.recording.CaptureFormat
import com.vknxworks.callctrl.recording.CaptureMode
import com.vknxworks.callctrl.recording.FormatProbe
import com.vknxworks.callctrl.recording.ICallCapture
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

/**
 * The call recorder's capture service. Shizuku starts it in its own process with the shell's identity (uid 2000),
 * which Android lets capture call audio; CallCtrl's own process can't. It only records call audio
 * ([MediaRecorder.AudioSource.VOICE_CALL], or the downlink and uplink separately) into the pipe CallCtrl gives it,
 * and never the microphone. CallCtrl binds it only while it records (or is about to), and Shizuku ends the process
 * when CallCtrl unbinds or dies.
 */
@Keep
class CaptureService(context: Context?) : ICallCapture.Stub() {

    /** Shizuku (13+) passes a Context; older versions use this constructor. */
    @Keep
    constructor() : this(null)

    private val shell: Context? = context?.let { ShellContext(it) }
    private val lock = Any()
    private var session: Session? = null

    override fun start(sink: ParcelFileDescriptor?): IntArray {
        if (sink == null) return IntArray(0)
        synchronized(lock) {
            session?.stop()
            session = null
            val opened = openVoiceCall() ?: openSplit()
            if (opened == null) {
                runCatching { sink.close() }
                return IntArray(0)
            }
            val s = Session(opened.first, opened.second, sink)
            session = s
            s.start()
            return opened.first.toArray()
        }
    }

    override fun stop() {
        synchronized(lock) {
            session?.stop()
            session = null
        }
    }

    override fun format(): IntArray = synchronized(lock) { session?.takeIf { it.running }?.format?.toArray() ?: IntArray(0) }

    override fun destroy() {
        stop()
        exitProcess(0)
    }

    // ------------------------------------------------------------------ opening recorders

    private fun openVoiceCall(): Pair<CaptureFormat, List<AudioRecord>>? {
        val picked = FormatProbe.select(FormatProbe.candidates(), ::minBuffer) { c, min -> open(MediaRecorder.AudioSource.VOICE_CALL, c, min) }
            ?: return null
        val (c, rec) = picked
        // Report what the recorder really runs at (Android may adjust it).
        val format = CaptureFormat(
            CaptureMode.VOICE_CALL,
            rec.sampleRate.takeIf { it > 0 } ?: c.sampleRate,
            rec.channelCount.takeIf { it in 1..2 } ?: c.channels,
            rec.audioFormat == AudioFormat.ENCODING_PCM_FLOAT,
        )
        return format to listOf(rec)
    }

    /** Fallback: the other person (downlink) and you (uplink) as two recorders, mixed into one mono stream. */
    private fun openSplit(): Pair<CaptureFormat, List<AudioRecord>>? {
        var single: Pair<CaptureFormat, List<AudioRecord>>? = null
        for (c in FormatProbe.splitCandidates()) {
            val min = minBuffer(c)
            if (min <= 0) continue
            val down = runCatching { open(MediaRecorder.AudioSource.VOICE_DOWNLINK, c, min) }.getOrNull()
            val up = runCatching { open(MediaRecorder.AudioSource.VOICE_UPLINK, c, min) }.getOrNull()
            val mode = FormatProbe.splitMode(down != null, up != null)
            if (mode == CaptureMode.SPLIT) {
                single?.second?.forEach { it.safeRelease() }
                return CaptureFormat(mode, c.sampleRate, 1, false) to listOf(down!!, up!!)
            }
            if (mode != null && single == null) {
                single = CaptureFormat(mode, c.sampleRate, 1, false) to listOfNotNull(down, up)
            } else {
                down?.safeRelease()
                up?.safeRelease()
            }
        }
        return single
    }

    private fun minBuffer(c: Candidate): Int = runCatching {
        AudioRecord.getMinBufferSize(c.sampleRate, mask(c.channels), encoding(c.float))
    }.getOrDefault(-1)

    /** Opens and starts a recorder for [source] in format [c]; null when the phone refuses it. */
    @SuppressLint("MissingPermission")
    private fun open(source: Int, c: Candidate, minBuffer: Int): AudioRecord? {
        val format = AudioFormat.Builder()
            .setSampleRate(c.sampleRate)
            .setChannelMask(mask(c.channels))
            .setEncoding(encoding(c.float))
            .build()
        val size = maxOf(minBuffer * 4, c.sampleRate / 5 * c.channels * (if (c.float) 4 else 2))
        // With the shell's identity first; without a context Android works it out from the process itself.
        val contexts: List<Context?> = if (shell != null) listOf(shell, null) else listOf(null)
        for (ctx in contexts) {
            val rec = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(size)
                    .apply { if (ctx != null) setContext(ctx) }
                    .build()
            }.getOrNull() ?: continue
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.safeRelease()
                continue
            }
            val started = runCatching { rec.startRecording(); rec.recordingState == AudioRecord.RECORDSTATE_RECORDING }.getOrDefault(false)
            if (!started) {
                rec.safeRelease()
                continue
            }
            return rec
        }
        return null
    }

    private fun mask(channels: Int) = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    private fun encoding(float: Boolean) = if (float) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT

    // ------------------------------------------------------------------ streaming

    /** Reads the recorder(s) and writes PCM into the pipe until stopped, the pipe closes or a read fails. */
    private class Session(val format: CaptureFormat, val records: List<AudioRecord>, val sink: ParcelFileDescriptor) {
        @Volatile var running = true
        private val thread = Thread({ loop() }, "CallCapture").apply { priority = Thread.MAX_PRIORITY }

        fun start() {
            thread.start()
        }

        fun stop() {
            running = false
            records.forEach { runCatching { it.stop() } }
            runCatching { thread.join(1500) }
            records.forEach { it.safeRelease() }
            runCatching { sink.close() }
        }

        private fun loop() {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            val out = FileOutputStream(sink.fileDescriptor).channel
            // About 20 ms per read.
            val frames = (format.sampleRate / 50).coerceAtLeast(160)
            val bytes = frames * format.frameBytes
            try {
                if (records.size == 1) {
                    val buf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val rec = records[0]
                    while (running) {
                        buf.clear()
                        val n = rec.read(buf, bytes, AudioRecord.READ_BLOCKING)
                        if (n < 0) break
                        if (n == 0) continue
                        buf.limit(n - n % format.frameBytes)
                        while (buf.hasRemaining()) out.write(buf)
                    }
                } else {
                    // Two 16-bit mono recorders: the other person and you, added together (clipped).
                    val a = ShortArray(frames)
                    val b = ShortArray(frames)
                    val mixed = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
                    while (running) {
                        val na = records[0].read(a, 0, frames, AudioRecord.READ_BLOCKING)
                        val nb = records[1].read(b, 0, frames, AudioRecord.READ_BLOCKING)
                        if (na < 0 || nb < 0) break
                        val n = maxOf(na, nb)
                        if (n == 0) continue
                        mixed.clear()
                        for (i in 0 until n) {
                            val s = (if (i < na) a[i].toInt() else 0) + (if (i < nb) b[i].toInt() else 0)
                            mixed.putShort(s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                        }
                        mixed.flip()
                        while (mixed.hasRemaining()) out.write(mixed)
                    }
                }
            } catch (_: IOException) {
                // CallCtrl closed the pipe (or went away): stop.
            } catch (_: RuntimeException) {
            } finally {
                running = false
                runCatching { out.close() }
                runCatching { sink.close() }
            }
        }
    }

    /**
     * Shizuku's Context belongs to CallCtrl's package, but this process runs as the shell. Audio permission
     * checks compare the two, so the recorder is attributed to the shell (uid 2000, com.android.shell), which is
     * what this process really is. Public APIs only.
     */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        private val source: AttributionSource = AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
        override fun getAttributionSource(): AttributionSource = source
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getAttributionTag(): String? = null
        override fun getDeviceId(): Int = Context.DEVICE_ID_DEFAULT
    }

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"
    }
}

private fun AudioRecord.safeRelease() {
    runCatching { stop() }
    runCatching { release() }
}
