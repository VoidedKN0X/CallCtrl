package com.vknxworks.callctrl.recording

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What CallCtrl knows about a recording, kept next to it as `<file>.json`. */
@Serializable
data class RecordingMeta(
    val file: String,
    /** When this file started (the call was active). */
    val startedAt: Long,
    val durationMs: Long = 0,
    val number: String? = null,
    val name: String? = null,
    /** "in", "out" or "conf". */
    val direction: String = "in",
    /** When the call itself started (for Recents). */
    val callStartedAt: Long = 0,
    /** The file type (FLAC, WAV, OPUS, AAC). */
    val format: String = "FLAC",
    /** What's in the file. */
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bits: Int = 16,
    /** What the phone captured: its rate, channels, float or 16-bit, and the source (see [CaptureMode]). */
    val captureRate: Int = 0,
    val captureChannels: Int = 0,
    val captureFloat: Boolean = false,
    val capture: Int = CaptureMode.VOICE_CALL.code,
    /** Why it ended early: "shizuku", "storage", "error"; null for a normal end. */
    val endReason: String? = null,
    /** False while recording (or when CallCtrl stopped in the middle). */
    val complete: Boolean = false,
    /** A copy was saved to Music/CallCtrl. */
    val copied: Boolean = false,
    /** Part number when the call was split into several files (hold, swap, conference). */
    val part: Int = 1,
)

data class Recording(val file: File, val meta: RecordingMeta) {
    val key: String get() = file.name
}

/**
 * The recordings, in CallCtrl's private files (`files/recordings`, left out of every backup). Nothing leaves the
 * phone unless you share a recording or turn on "Save copies to Music".
 */
class RecordingStore(private val context: Context, private val scope: CoroutineScope) {
    val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    private val _list = MutableStateFlow<List<Recording>>(emptyList())
    val list: StateFlow<List<Recording>> = _list

    /** Files being written right now (never deleted by auto-delete). */
    @Volatile var inProgress: Set<String> = emptySet()

    fun reload() {
        scope.launch(Dispatchers.IO) { _list.value = load() }
    }

    private fun load(): List<Recording> {
        val files = dir.listFiles().orEmpty()
        val audio = files.filter { f -> f.isFile && RecordFormat.entries.any { f.name.endsWith("." + it.extension) } }
        return audio.map { f ->
            val meta = readMeta(f) ?: RecordingMeta(file = f.name, startedAt = f.lastModified(), complete = true)
            Recording(f, meta)
        }.sortedByDescending { it.meta.startedAt }
    }

    private fun metaFile(audio: File) = AtomicFile(File(audio.parentFile, audio.name + ".json"))

    fun readMeta(audio: File): RecordingMeta? = runCatching {
        val f = metaFile(audio)
        if (!f.baseFile.exists()) null else json.decodeFromString(RecordingMeta.serializer(), f.readFully().decodeToString())
    }.getOrNull()

    fun writeMeta(audio: File, meta: RecordingMeta) {
        runCatching {
            val f = metaFile(audio)
            val out = f.startWrite()
            try {
                out.write(json.encodeToString(RecordingMeta.serializer(), meta).encodeToByteArray())
                f.finishWrite(out)
            } catch (e: Exception) {
                f.failWrite(out)
                throw e
            }
        }
    }

    suspend fun delete(recordings: Collection<Recording>) = withContext(Dispatchers.IO) {
        for (r in recordings) {
            if (r.file.name in inProgress) continue
            r.file.delete()
            metaFile(r.file).delete()
        }
        _list.value = load()
    }

    /** "Forgot PIN?" with "delete recordings": everything in the folder. */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { if (it.name !in inProgress && !it.name.removeSuffix(".json").let { n -> n in inProgress }) it.delete() }
        _list.value = load()
    }

    /** Deletes recordings older than [policy]; returns how many. */
    suspend fun autoDelete(policy: AutoDelete): Int = withContext(Dispatchers.IO) {
        if (policy == AutoDelete.NEVER) return@withContext 0
        val all = load()
        val old = AutoDeletePolicy.expired(all, { it.meta.startedAt }, { it.file.name in inProgress }, System.currentTimeMillis(), policy.days)
        for (r in old) {
            r.file.delete()
            metaFile(r.file).delete()
        }
        _list.value = load()
        old.size
    }

    /** Copies a recording to Music/CallCtrl through MediaStore (no storage permission needed). */
    suspend fun copyToMusic(r: Recording): Boolean = withContext(Dispatchers.IO) {
        val format = RecordFormat.entries.firstOrNull { r.file.name.endsWith("." + it.extension) } ?: return@withContext false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, r.file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, format.mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/CallCtrl")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }.getOrNull()
            ?: return@withContext false
        val ok = runCatching {
            resolver.openOutputStream(uri, "w")?.use { out -> r.file.inputStream().use { it.copyTo(out) } } != null
        }.getOrDefault(false)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext false
        }
        runCatching { resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) }
        readMeta(r.file)?.let { writeMeta(r.file, it.copy(copied = true)) }
        _list.value = load()
        true
    }

    /** Recordings that were cut off (CallCtrl stopped mid-call): WAV headers get their sizes, so they still play. */
    fun repairUnfinished() {
        scope.launch(Dispatchers.IO) {
            for (r in load()) {
                if (r.meta.complete || r.file.name in inProgress) continue
                if (r.file.name.endsWith(".wav")) runCatching { repairWav(r) }
                writeMeta(r.file, r.meta.copy(complete = true, endReason = r.meta.endReason ?: "error"))
            }
            _list.value = load()
        }
    }

    private fun repairWav(r: Recording) {
        val float = r.meta.bits == 32
        val header = WavHeader.size(float)
        val data = (r.file.length() - header).coerceAtLeast(0)
        val ch = r.meta.channels.takeIf { it > 0 } ?: 1
        java.io.RandomAccessFile(r.file, "rw").use {
            it.seek(0)
            it.write(WavHeader.build(r.meta.sampleRate.takeIf { s -> s > 0 } ?: 48_000, ch, float, data))
        }
    }

    companion object {
        const val DIR = "recordings"
        /** Below this much free space a recording doesn't start, or stops. */
        const val MIN_FREE_START = 50L * 1024 * 1024
        const val MIN_FREE_KEEP = 20L * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
