package com.vknxworks.callctrl.data.quiet

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Scheduled quiet mode: between [startMinute] and [endMinute] on the chosen [days], incoming calls don't ring.
 * They still show on the screen and land in Recents as usual; nothing is rejected.
 */
@Serializable
data class QuietHours(
    /** Off by default. */
    val enabled: Boolean = false,
    /** Minutes after midnight the quiet time starts (22:00). */
    val startMinute: Int = 22 * 60,
    /** Minutes after midnight it ends (07:00). The same as the start means the whole day. */
    val endMinute: Int = 7 * 60,
    /** The days it starts on: 1 is Monday, 7 is Sunday. A night that starts on Friday runs into Saturday morning. */
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    /** Calls from your favourite (starred) contacts still ring. */
    val favouritesRing: Boolean = true,
    /** Calls from everyone in your contacts still ring. */
    val contactsRing: Boolean = false,
) {
    fun cleaned(): QuietHours = copy(
        startMinute = startMinute.coerceIn(0, QuietRules.DAY - 1),
        endMinute = endMinute.coerceIn(0, QuietRules.DAY - 1),
        days = days.filter { it in 1..7 }.toSet(),
    )
}

/** When quiet mode applies. Pure Kotlin, unit-tested. */
object QuietRules {
    const val DAY = 24 * 60

    /**
     * Whether quiet time is running at [minute] (minutes after midnight) on [day] (1 Monday to 7 Sunday).
     * A period whose end is not after its start runs past midnight and belongs to the day it started on.
     */
    fun active(q: QuietHours, day: Int, minute: Int): Boolean {
        if (!q.enabled || q.days.isEmpty()) return false
        val start = q.startMinute
        val end = q.endMinute
        val previous = if (day == 1) 7 else day - 1
        return if (end > start) {
            day in q.days && minute >= start && minute < end
        } else {
            (day in q.days && minute >= start) || (previous in q.days && minute < end)
        }
    }

    /**
     * Whether a call that would ring is silenced instead. Emergency numbers always ring. [starred] and
     * [contact] say what your contacts know about the caller (both false for a hidden number).
     */
    fun silences(q: QuietHours, day: Int, minute: Int, emergency: Boolean, starred: Boolean, contact: Boolean): Boolean {
        if (emergency || !active(q, day, minute)) return false
        if (q.contactsRing && contact) return false
        if (q.favouritesRing && starred) return false
        return true
    }

    /** "22:00" for 1320. */
    fun clock(minute: Int): String {
        val m = ((minute % DAY) + DAY) % DAY
        return (m / 60).toString().padStart(2, '0') + ":" + (m % 60).toString().padStart(2, '0')
    }
}

/**
 * Quiet mode's settings, in CallCtrl's own files (`quiet.json`, written atomically, not encrypted: the call
 * screening service reads it for every incoming call and must never wait for a key). Read once per process.
 */
class QuietStore(context: Context, scope: CoroutineScope) {
    private val file = AtomicFile(File(context.filesDir, "quiet.json"))
    private val lock = Mutex()
    private val _quiet = MutableStateFlow(QuietHours())
    val quiet: StateFlow<QuietHours> = _quiet

    @Volatile private var loaded = false

    init {
        scope.launch(Dispatchers.IO) { ensureLoaded() }
    }

    /** The settings, read from disk the first time (call off the main thread). */
    fun current(): QuietHours {
        ensureLoaded()
        return _quiet.value
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        val read = runCatching {
            if (!file.baseFile.exists()) null
            else json.decodeFromString(QuietHours.serializer(), file.readFully().decodeToString()).cleaned()
        }.getOrNull()
        if (read != null) _quiet.value = read
        loaded = true
    }

    /** Saves a change. If the phone's storage fails, the change is kept for this session only. */
    suspend fun update(transform: (QuietHours) -> QuietHours) = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoaded()
            val next = transform(_quiet.value).cleaned()
            _quiet.value = next
            runCatching {
                val out = file.startWrite()
                try {
                    out.write(json.encodeToString(QuietHours.serializer(), next).encodeToByteArray())
                    file.finishWrite(out)
                } catch (e: Exception) {
                    file.failWrite(out)
                    throw e
                }
            }
        }
        Unit
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
