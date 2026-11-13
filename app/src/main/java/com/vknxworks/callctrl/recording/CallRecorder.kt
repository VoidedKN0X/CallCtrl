package com.vknxworks.callctrl.recording

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.telecom.Call
import com.vknxworks.callctrl.calls.CallInfo
import com.vknxworks.callctrl.calls.ScreeningService
import com.vknxworks.callctrl.container
import com.vknxworks.callctrl.data.ContactsRepository
import com.vknxworks.callctrl.data.Phone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.Executors

/** Why a recording didn't start or stopped early. */
enum class RecordProblem {
    /** Shizuku isn't installed, running or allowed. */
    SHIZUKU_NOT_READY,
    /** Shizuku stopped during the call; what was recorded until then is kept. */
    SHIZUKU_LOST,
    /** The phone refused every call-audio source. */
    REFUSED,
    /** Not enough free storage. */
    STORAGE_FULL,
    /** Something else went wrong writing the file. */
    ERROR,
}

/** What the call screen shows. */
data class RecorderState(
    /** The call being recorded now. */
    val call: Call? = null,
    /** SystemClock.elapsedRealtime() when this file started. */
    val since: Long = 0,
    val capture: CaptureFormat? = null,
    /** Asked to record, waiting for Shizuku and the capture to start. */
    val starting: Call? = null,
    val problem: RecordProblem? = null,
    val problemCall: Call? = null,
)

/**
 * The call recorder. Follows the calls (from [com.vknxworks.callctrl.calls.CallManager]), decides which ones are
 * recorded (Settings > Call recording, or your tap on Record), records the active one through the Shizuku capture
 * service, and splits files when the active call changes (hold, swap, conference). Main thread, except the file
 * work, which runs on its own threads.
 */
@SuppressLint("StaticFieldLeak")
object CallRecorder {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "CallRecorder") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var app: Context? = null

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state

    /** Per call: recorded (by the rules or your tap). Missing: not decided yet. */
    private val wanted = HashMap<Call, Boolean>()
    private val facts = HashMap<Call, CallFacts>()
    private val names = HashMap<Call, String?>()
    private val parts = HashMap<Call, Int>()
    /** The capture failed for this call: don't try again on its own (a tap on Record does). */
    private val failed = HashSet<Call>()
    private var calls: List<CallInfo> = emptyList()
    private var current: Call? = null

    // Worker-thread state.
    @Volatile private var workerTarget: Call? = null
    private val segmentLock = Any()
    private var segment: Segment? = null
    private var segmentMeta: RecordingMeta? = null
    private var captureFormat: CaptureFormat? = null
    private var captureService: ICallCapture? = null
    private var readPipe: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    @Volatile private var stoppingCapture = false

    private fun ctx(context: Context) {
        if (app == null) {
            app = context.applicationContext
            ShizukuGate.start(context)
            ShizukuGate.onDead = { onCaptureLost() }
        }
    }

    // ------------------------------------------------------------------ following the calls

    /** A new call: works out whether the rules record it (contacts lookup off the main thread). */
    fun onAdded(context: Context, call: Call) {
        ctx(context)
        val c = context.applicationContext
        val settings = c.container.settings.settings.value
        if (settings.recordMode == RecordMode.OFF) {
            wanted[call] = false
            return
        }
        val number = call.details.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        val hidden = number == null || call.details.handlePresentation != android.telecom.TelecomManager.PRESENTATION_ALLOWED
        val conference = call.details.hasProperty(Call.Details.PROPERTY_CONFERENCE)
        scope.launch {
            val looked = if (!hidden && number != null && Phone.has(c, android.Manifest.permission.READ_CONTACTS)) {
                withContext(Dispatchers.IO) { ContactsRepository.lookup(c, number) }
            } else null
            val f = CallFacts(number, hidden, looked?.name != null, conference)
            facts[call] = f
            names[call] = looked?.name ?: call.details.contactDisplayName?.toString()?.takeIf { it.isNotBlank() }
            val auto = withContext(Dispatchers.IO) {
                RecordingRules.autoRecord(settings.recordMode, f, settings.recordContacts) { ScreeningService.forms(c, it) }
            }
            // A conference is recorded when one of the calls merged into it was.
            val fromChildren = conference && call.children.any { wanted[it] == true }
            if (wanted[call] == null) wanted[call] = auto || fromChildren
            update()
        }
    }

    fun onRemoved(call: Call) {
        calls = calls.filter { it.call != call }
        update()
        wanted.remove(call)
        facts.remove(call)
        names.remove(call)
        parts.remove(call)
        failed.remove(call)
    }

    /** Every change in the calls (state, hold, merge). */
    fun onCalls(list: List<CallInfo>) {
        calls = list
        // A new conference: recorded when one of its calls was.
        for (c in list) {
            if (c.isConference && wanted[c.call] != true && c.call.children.any { wanted[it] == true }) wanted[c.call] = true
        }
        update()
    }

    /** Record / Stop on the call screen. Returns the problem when it can't start. */
    fun toggle(context: Context, call: Call): RecordProblem? {
        ctx(context)
        if (current == call || _state.value.starting == call) {
            wanted[call] = false
            update()
            return null
        }
        ShizukuGate.refresh()
        if (ShizukuGate.status.value != ShizukuStatus.READY) return RecordProblem.SHIZUKU_NOT_READY
        failed.remove(call)
        wanted[call] = true
        if (_state.value.problemCall == call) _state.value = _state.value.copy(problem = null, problemCall = null)
        update()
        return null
    }

    private fun live(): List<LiveCall<Call>> = calls.map { c ->
        val s = when {
            c.ringing -> CallState.RINGING
            c.active -> CallState.ACTIVE
            c.held -> CallState.HOLDING
            c.ended -> CallState.ENDED
            c.outgoing -> CallState.DIALING
            else -> CallState.OTHER
        }
        LiveCall(c.call, s, wanted[c.call] == true && c.call !in failed)
    }

    private fun update() {
        val context = app ?: return
        val settings = context.container.settings.settings.value
        val list = live()
        val target = RecordingPlanner.target(list, current, settings.recordOnHold)
        val needed = RecordingPlanner.needsCapture(list)
        // About to record (ringing or dialling): start Shizuku's capture process now, so it's ready on answer.
        if (needed && target == null && current == null && !ShizukuGate.active && ShizukuGate.status.value == ShizukuStatus.READY) {
            ShizukuGate.bind { }
        }
        if (target != current) switchTo(target)
        if (!needed && current == null && _state.value.starting == null) releaseCapture()
    }

    private fun switchTo(target: Call?) {
        val context = app ?: return
        val previous = current
        current = target
        workerTarget = target
        if (previous != null) {
            worker.execute { endSegment(null) }
            _state.value = _state.value.copy(call = null, capture = null)
        }
        if (target == null) {
            _state.value = _state.value.copy(starting = null)
            return
        }
        _state.value = _state.value.copy(starting = target)
        ShizukuGate.bind { service ->
            if (current != target) return@bind
            if (service == null) {
                fail(target, RecordProblem.SHIZUKU_NOT_READY)
                return@bind
            }
            val settings = context.container.settings.settings.value
            val part = (parts[target] ?: 0) + 1
            parts[target] = part
            val info = Info(
                facts = facts[target] ?: CallFacts(null, true, false),
                name = names[target],
                direction = when {
                    target.details.hasProperty(Call.Details.PROPERTY_CONFERENCE) -> Direction.CONFERENCE
                    target.details.callDirection == Call.Details.DIRECTION_OUTGOING -> Direction.OUT
                    else -> Direction.IN
                },
                callStartedAt = target.details.creationTimeMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
                format = settings.recordFormat,
                part = part,
            )
            worker.execute { beginSegment(context, target, service, info) }
        }
    }

    private fun fail(call: Call, problem: RecordProblem) {
        failed += call
        if (current == call) {
            current = null
            workerTarget = null
        }
        _state.value = _state.value.copy(call = if (_state.value.call == call) null else _state.value.call, starting = null, problem = problem, problemCall = call)
        app?.let { RecordingNotifications.problem(it, problem) }
        update()
    }

    // ------------------------------------------------------------------ worker: capture and files

    private class Info(
        val facts: CallFacts,
        val name: String?,
        val direction: Direction,
        val callStartedAt: Long,
        val format: RecordFormat,
        val part: Int,
    )

    private fun beginSegment(context: Context, call: Call, service: ICallCapture, info: Info) {
        if (workerTarget != call) return
        val store = context.container.recordings
        val dir = store.dir
        if (dir.usableSpace < RecordingStore.MIN_FREE_START) {
            main.post { fail(call, RecordProblem.STORAGE_FULL) }
            return
        }
        val format = captureFormat?.takeIf { captureService === service && reader?.isAlive == true } ?: startCapture(service)
        if (format == null) {
            main.post { fail(call, RecordProblem.REFUSED) }
            return
        }
        if (workerTarget != call) return
        val fileFormat = runCatching { Encoders.available(info.format, format.sampleRate, format.channels) }.getOrDefault(info.format)
        val started = System.currentTimeMillis()
        val who = when {
            info.direction == Direction.CONFERENCE -> "conference"
            info.facts.hidden -> "private"
            else -> info.name ?: info.facts.number
        }
        val name = RecordingNames.unique(RecordingNames.fileName(started, ZoneId.systemDefault(), info.direction, who, fileFormat.extension)) { File(dir, it).exists() }
        val file = File(dir, name)
        val seg = runCatching { Segment(file, format, fileFormat) }.getOrNull()
        if (seg == null) {
            main.post { fail(call, RecordProblem.ERROR) }
            return
        }
        val meta = RecordingMeta(
            file = name,
            startedAt = started,
            number = info.facts.number.takeIf { !info.facts.hidden },
            name = info.name,
            direction = info.direction.tag,
            callStartedAt = info.callStartedAt,
            format = fileFormat.name,
            sampleRate = format.sampleRate,
            channels = format.channels,
            bits = if (fileFormat == RecordFormat.WAV && format.float) 32 else 16,
            captureRate = format.sampleRate,
            captureChannels = format.channels,
            captureFloat = format.float,
            capture = format.mode.code,
            part = info.part,
        )
        store.inProgress = store.inProgress + name
        store.writeMeta(file, meta)
        synchronized(segmentLock) {
            segment = seg
            segmentMeta = meta
        }
        val since = SystemClock.elapsedRealtime()
        main.post {
            if (current == call) _state.value = _state.value.copy(call = call, since = since, capture = format, starting = null, problem = null, problemCall = null)
        }
    }

    /** Ends the current file. [reason] null for a normal end. */
    private fun endSegment(reason: String?) {
        val (seg, meta) = synchronized(segmentLock) {
            val s = segment to segmentMeta
            segment = null
            segmentMeta = null
            s
        }
        if (seg == null || meta == null) return
        val context = app ?: return
        val store = context.container.recordings
        val frames = runCatching { seg.close() }.getOrElse { seg.framesWritten }
        store.inProgress = store.inProgress - meta.file
        if (frames <= 0 || !seg.file.exists() || seg.file.length() == 0L) {
            seg.file.delete()
            File(seg.file.parentFile, seg.file.name + ".json").delete()
        } else {
            val done = meta.copy(
                durationMs = frames * 1000 / seg.outRate.coerceAtLeast(1),
                sampleRate = seg.outRate,
                channels = seg.outChannels,
                bits = seg.outBits,
                complete = true,
                endReason = reason,
            )
            store.writeMeta(seg.file, done)
            val settings = context.container.settings.settings.value
            if (settings.recordCopyToMusic) {
                kotlinx.coroutines.runBlocking { store.copyToMusic(Recording(seg.file, done)) }
            }
            kotlinx.coroutines.runBlocking { store.autoDelete(settings.recordAutoDelete) }
        }
        store.reload()
    }

    /** Starts the capture into a new pipe; returns its format, or null when the phone refused. */
    private fun startCapture(service: ICallCapture): CaptureFormat? {
        stopCaptureNow()
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return null
        val result = runCatching { service.start(pipe[1]) }.getOrNull()
        runCatching { pipe[1].close() }
        val format = CaptureFormat.of(result)
        if (format == null) {
            runCatching { pipe[0].close() }
            return null
        }
        captureService = service
        captureFormat = format
        readPipe = pipe[0]
        stoppingCapture = false
        reader = Thread({ readLoop(pipe[0], format) }, "CallRecorderReader").apply { start() }
        return format
    }

    private fun readLoop(pfd: ParcelFileDescriptor, format: CaptureFormat) {
        val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val frame = format.frameBytes
        val buf = ByteArray(frame * (format.sampleRate / 25).coerceAtLeast(320))
        var filled = 0
        var lastCheck = SystemClock.elapsedRealtime()
        var problem: String? = null
        try {
            while (true) {
                val n = input.read(buf, filled, buf.size - filled)
                if (n < 0) break
                filled += n
                val whole = filled - filled % frame
                if (whole > 0) {
                    synchronized(segmentLock) {
                        try {
                            segment?.write(buf, whole)
                        } catch (e: Exception) {
                            problem = if (spaceLow()) "storage" else "error"
                        }
                    }
                    System.arraycopy(buf, whole, buf, 0, filled - whole)
                    filled -= whole
                }
                if (problem != null) break
                val now = SystemClock.elapsedRealtime()
                if (now - lastCheck > 5_000) {
                    lastCheck = now
                    if (spaceLow()) {
                        problem = "storage"
                        break
                    }
                    saveProgress()
                }
            }
        } catch (_: IOException) {
        } finally {
            runCatching { input.close() }
        }
        if (stoppingCapture && problem == null) return
        val p = problem
        main.post {
            when (p) {
                "storage" -> onWriteProblem(RecordProblem.STORAGE_FULL, "storage")
                "error" -> onWriteProblem(RecordProblem.ERROR, "error")
                else -> onCaptureLost()
            }
        }
    }

    private fun spaceLow(): Boolean = (app?.container?.recordings?.dir?.usableSpace ?: Long.MAX_VALUE) < RecordingStore.MIN_FREE_KEEP

    /** Every few seconds: the length and channels so far, so a recording cut off by a crash still lists right. */
    private fun saveProgress() {
        val (seg, meta) = synchronized(segmentLock) { segment to segmentMeta }
        if (seg == null || meta == null) return
        app?.container?.recordings?.writeMeta(
            seg.file,
            meta.copy(durationMs = seg.framesWritten * 1000 / seg.outRate.coerceAtLeast(1), channels = seg.outChannels, sampleRate = seg.outRate, bits = seg.outBits),
        )
    }

    private fun onWriteProblem(problem: RecordProblem, reason: String) {
        val call = current ?: return
        worker.execute {
            endSegment(reason)
            stopCaptureNow()
        }
        fail(call, problem)
    }

    /** Shizuku or the capture process went away while recording: keep what was recorded and say so. */
    private fun onCaptureLost() {
        val call = current
        worker.execute {
            if (segment != null) endSegment("shizuku")
            captureService = null
            captureFormat = null
            reader = null
            readPipe = null
        }
        ShizukuGate.unbind()
        if (call != null) fail(call, RecordProblem.SHIZUKU_LOST)
    }

    private fun stopCaptureNow() {
        stoppingCapture = true
        runCatching { captureService?.stop() }
        runCatching { readPipe?.close() }
        runCatching { reader?.join(2000) }
        reader = null
        readPipe = null
        captureFormat = null
        captureService = null
    }

    private fun releaseCapture() {
        if (!ShizukuGate.active && captureService == null) return
        worker.execute { stopCaptureNow() }
        worker.execute { main.post { if (current == null && _state.value.starting == null) ShizukuGate.unbind() } }
    }
}
