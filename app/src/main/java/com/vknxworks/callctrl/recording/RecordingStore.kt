package com.vknxworks.callctrl.recording

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import com.vknxworks.callctrl.calls.CallManager
import com.vknxworks.callctrl.data.crypto.DataKeyStore
import com.vknxworks.callctrl.data.crypto.SealedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

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
 *
 * Encryption at rest: a recording is written as an ordinary audio file while the call lasts (the recorder is not
 * involved in any of this). Once it's finished and no call is going on, [sealPass] replaces it, in the background,
 * with a copy encrypted under the app's data key ([SealedFile]); recordings above [MAX_SEAL_BYTES] stay ordinary
 * files. Playing, sharing and "Save a copy" decrypt it again, so what leaves CallCtrl is always a normal audio
 * file. The `<file>.json` notes next to a recording are not encrypted: the list, auto-delete and the recorder read
 * and write them, also during calls, and none of that may wait for the key.
 */
class RecordingStore(private val context: Context, private val scope: CoroutineScope, private val keys: DataKeyStore) {
    val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    private val _list = MutableStateFlow<List<Recording>>(emptyList())
    val list: StateFlow<List<Recording>> = _list

    /** Files being written right now (never deleted by auto-delete, never sealed). */
    @Volatile var inProgress: Set<String> = emptySet()

    /** How many encrypted recordings can't be opened because the data key is missing (0: all fine). */
    private val _unreadable = MutableStateFlow(0)
    val unreadable: StateFlow<Int> = _unreadable

    /** The data key couldn't be had the last time it was needed: auto-delete then leaves encrypted recordings alone. */
    @Volatile private var keyLost = false

    /** Held while a recording file is sealed or deleted, so sealing never brings back a deleted recording. */
    private val fileLock = Any()

    /** Ordinary files handed to another app (Share) in this run: left as they are while that app may still read them. */
    @Volatile private var pinned: Set<String> = emptySet()

    private val sealing = AtomicBoolean(false)

    /** Reads the list again, then (in the background) seals finished recordings and checks the key. */
    fun reload() {
        scope.launch(Dispatchers.IO) {
            val all = load()
            _list.value = all
            runCatching { sealPass(all) }
            runCatching { checkKey(all) }
        }
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
            synchronized(fileLock) {
                r.file.delete()
                metaFile(r.file).delete()
            }
        }
        _list.value = load()
    }

    /** "Forgot PIN?" with "delete recordings": everything in the folder. */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            dir.listFiles()?.forEach { if (it.name !in inProgress && !it.name.removeSuffix(".json").let { n -> n in inProgress }) it.delete() }
        }
        wipeTemp(playback = true, sharedOlderThanMs = 0)
        val all = load()
        _list.value = all
        runCatching { checkKey(all) }
    }

    /** Deletes recordings older than [policy]; returns how many. */
    suspend fun autoDelete(policy: AutoDelete): Int = withContext(Dispatchers.IO) {
        if (policy == AutoDelete.NEVER) return@withContext 0
        val all = load()
        val old = AutoDeletePolicy.expired(all, { it.meta.startedAt }, { it.file.name in inProgress }, System.currentTimeMillis(), policy.days)
        var deleted = 0
        for (r in old) {
            // An encrypted recording that can't be opened right now (the key is missing) is never deleted on its own.
            if (keyLost && SealedFile.isSealed(r.file)) continue
            synchronized(fileLock) {
                r.file.delete()
                metaFile(r.file).delete()
            }
            deleted++
        }
        _list.value = load()
        deleted
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
            // The ordinary audio, decrypted when the recording is sealed: the copy in Music is a normal file.
            val input = plainStream(r.file)
            if (input == null) false
            else input.use { src -> resolver.openOutputStream(uri, "w")?.use { out -> src.copyTo(out) } != null }
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
                if (r.file.name.endsWith(".wav") && !SealedFile.isSealed(r.file)) runCatching { repairWav(r) }
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

    // ------------------------------------------------------------------ encryption at rest

    /** A call is on, or a file is being written: no sealing now. */
    private fun busy(): Boolean = inProgress.isNotEmpty() || CallManager.calls.value.isNotEmpty()

    /** A finished recording that is still an ordinary file and small enough to seal. */
    private fun sealable(r: Recording): Boolean {
        val f = r.file
        if (!r.meta.complete || f.name in inProgress || f.name in pinned) return false
        // Without its notes file there's no telling whether it's finished: only when it's been left alone for an hour.
        if (!metaFile(f).baseFile.exists() && System.currentTimeMillis() - f.lastModified() < 3_600_000L) return false
        val size = f.length()
        return size in 1..MAX_SEAL_BYTES && !SealedFile.isSealed(f)
    }

    /**
     * Encrypts the finished recordings in [all] that are still ordinary files, one at a time, never while a call
     * is on. Each one is replaced in a single step by a sealed copy that was first written out, read back and
     * compared ([SealedFile.sealInPlace]); one that can't be sealed stays as it is and still plays. Safe to stop
     * at any point: the next run carries on with what's left. Without a data key nothing changes.
     */
    private fun sealPass(all: List<Recording>) {
        if (!sealing.compareAndSet(false, true)) return
        try {
            wipeTemp(playback = false, sharedOlderThanMs = SHARED_KEEP_MS)
            if (busy()) return
            // Sealed copies a crash left half-made (nothing is being sealed right now).
            synchronized(fileLock) {
                dir.listFiles()?.forEach { if (it.name.endsWith(SEAL_TMP) && it.name.removeSuffix(SEAL_TMP) !in inProgress) it.delete() }
            }
            val pending = all.filter { sealable(it) }
            if (pending.isEmpty()) return
            val key = keys.get(create = true) ?: return
            for (r in pending) {
                if (busy()) return
                synchronized(fileLock) {
                    if (r.file.isFile && sealable(r)) SealedFile.sealInPlace(key, r.file, File(r.file.parentFile, r.file.name + SEAL_TMP))
                }
            }
        } finally {
            sealing.set(false)
        }
    }

    /** Whether the encrypted recordings in [all] can be opened (asks the secure hardware once per run). */
    private fun checkKey(all: List<Recording>) {
        val sealed = all.count { SealedFile.isSealed(it.file) }
        if (sealed == 0) {
            keyLost = false
            _unreadable.value = 0
            return
        }
        val ok = keys.get(create = false) != null
        keyLost = !ok
        _unreadable.value = if (ok) 0 else sealed
    }

    /** "Try again" on the Recordings screen: asks the secure hardware for the key once more. */
    fun retryKey() {
        scope.launch(Dispatchers.IO) {
            keys.reset()
            runCatching { checkKey(load()) }
        }
    }

    /** The decrypted audio of the sealed recording [file], or null when it can't be opened. */
    private fun openSealed(file: File): ByteArray? = try {
        val key = keys.get(create = false)
        if (key == null) null else SealedFile.open(key, file.readBytes())
    } catch (e: Throwable) {
        null
    }

    /** The recording's ordinary audio as a stream, or null when it's sealed and can't be opened. */
    private fun plainStream(file: File): InputStream? {
        // Opened under the lock: a file sealed right after this is still read as the ordinary file it was.
        synchronized(fileLock) {
            if (!SealedFile.isSealed(file)) return file.inputStream()
        }
        return openSealed(file)?.let { ByteArrayInputStream(it) }
    }

    /**
     * A file Android's player can open: the recording itself when it's an ordinary file, else a decrypted copy
     * in `cache/playback` (removed by [clearPlayback] when the player closes, and by [clearTemp] when the app
     * locks and at the next start). Null when the recording is sealed and can't be opened.
     */
    suspend fun playable(r: Recording): File? = withContext(Dispatchers.IO) {
        if (!SealedFile.isSealed(r.file)) return@withContext r.file
        val plain = openSealed(r.file) ?: return@withContext null
        runCatching {
            val out = File(context.cacheDir, PLAY_DIR).apply { mkdirs() }
            out.listFiles()?.forEach { it.delete() }
            File(out, r.file.name).also { it.writeBytes(plain) }
        }.getOrNull()
    }

    /** Removes the decrypted copy of [r] made for playing (nothing to do for an ordinary file). */
    fun clearPlayback(r: Recording) {
        scope.launch(Dispatchers.IO) { runCatching { File(File(context.cacheDir, PLAY_DIR), r.file.name).delete() } }
    }

    /**
     * A file to hand to another app (Share): the recording itself when it's an ordinary file (it then stays one
     * for the rest of this run), else a decrypted copy in `cache/shared` (removed after an hour, or at the next
     * start). Null when the recording is sealed and can't be opened.
     */
    suspend fun shareable(r: Recording): File? = withContext(Dispatchers.IO) {
        val ordinary = synchronized(fileLock) {
            if (SealedFile.isSealed(r.file)) false
            else {
                pinned = pinned + r.file.name
                true
            }
        }
        if (ordinary) return@withContext r.file
        val plain = openSealed(r.file) ?: return@withContext null
        runCatching {
            val out = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            File(out, r.file.name).also { it.writeBytes(plain) }
        }.getOrNull()
    }

    /** Decrypted copies in the cache: the one for playing, and shared ones older than [sharedOlderThanMs]. */
    private fun wipeTemp(playback: Boolean, sharedOlderThanMs: Long) {
        runCatching {
            if (playback) File(context.cacheDir, PLAY_DIR).listFiles()?.forEach { it.delete() }
            val now = System.currentTimeMillis()
            File(context.cacheDir, SHARE_DIR).listFiles()?.forEach { if (now - it.lastModified() >= sharedOlderThanMs) it.delete() }
        }
    }

    /** App start and app lock: decrypted copies left in the cache go (shared ones once they're an hour old). */
    fun clearTemp() {
        scope.launch(Dispatchers.IO) { wipeTemp(playback = true, sharedOlderThanMs = SHARED_KEEP_MS) }
    }

    companion object {
        const val DIR = "recordings"
        /** Recordings above this size stay ordinary files: sealing and opening hold the whole file in memory. */
        const val MAX_SEAL_BYTES = 32L * 1024 * 1024
        private const val SEAL_TMP = ".seal.tmp"
        private const val PLAY_DIR = "playback"
        private const val SHARE_DIR = "shared"
        private const val SHARED_KEEP_MS = 3_600_000L
        /** Below this much free space a recording doesn't start, or stops. */
        const val MIN_FREE_START = 50L * 1024 * 1024
        const val MIN_FREE_KEEP = 20L * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
