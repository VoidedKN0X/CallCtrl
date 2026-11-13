package com.vknxworks.callctrl.recording

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The call recorder's rules: which calls are recorded, which call is recorded right now, file names and
 * auto-delete. Pure Kotlin (no Android), so every decision is unit-tested.
 */

/** Settings > Phone app > Call recording > Record. */
enum class RecordMode {
    /** Never (the default). */
    OFF,
    /** Only when you tap Record on the call screen. */
    ASK,
    /** Every call. */
    ALWAYS,
    /** Calls with numbers that aren't in your contacts, and hidden numbers. */
    UNKNOWN,
    /** Calls with the contacts you picked. */
    SELECTED,
}

/** The file type recordings are saved as. */
enum class RecordFormat(val extension: String, val mime: String, val lossless: Boolean) {
    FLAC("flac", "audio/flac", true),
    WAV("wav", "audio/wav", true),
    OPUS("ogg", "audio/ogg", false),
    AAC("m4a", "audio/mp4", false),
}

/** Delete recordings older than this (Never keeps them until you delete them). */
enum class AutoDelete(val days: Int) {
    NEVER(0), DAYS_7(7), DAYS_30(30), DAYS_90(90), DAYS_365(365),
}

/** A contact picked for "Only selected contacts": their numbers when you picked them. */
@Serializable
data class SelectedContact(
    @SerialName("key") val key: String,
    @SerialName("name") val name: String,
    @SerialName("numbers") val numbers: List<String>,
)

/** What the recorder knows about a call when it decides. */
data class CallFacts(
    val number: String?,
    val hidden: Boolean,
    val isContact: Boolean,
    val isConference: Boolean = false,
)

object RecordingRules {
    /** Whether a call is recorded without you tapping Record. [forms] turns a number into the forms to compare. */
    fun autoRecord(
        mode: RecordMode,
        call: CallFacts,
        selected: List<SelectedContact>,
        forms: (String) -> Set<String> = { setOf(normalize(it)).filter { s -> s.isNotEmpty() }.toSet() },
    ): Boolean = when (mode) {
        RecordMode.OFF, RecordMode.ASK -> false
        RecordMode.ALWAYS -> true
        RecordMode.UNKNOWN -> !call.isConference && (call.hidden || call.number.isNullOrBlank() || !call.isContact)
        RecordMode.SELECTED -> {
            val number = call.number
            if (call.hidden || number.isNullOrBlank()) false
            else {
                val callForms = forms(number)
                callForms.isNotEmpty() && selected.any { c -> c.numbers.any { n -> forms(n).any { it in callForms } } }
            }
        }
    }

    /** The Record button shows on the call screen in every mode but Off. */
    fun showRecordButton(mode: RecordMode): Boolean = mode != RecordMode.OFF

    /** Keeps a leading + and the digits. */
    fun normalize(number: String?): String {
        if (number.isNullOrBlank()) return ""
        val t = number.trim()
        val digits = t.filter { it in '0'..'9' }
        return if (t.startsWith("+") && digits.isNotEmpty()) "+$digits" else digits
    }
}

/** A call as the recorder sees it: [key] identifies the call, [state] is its Telecom state. */
data class LiveCall<K>(val key: K, val state: CallState, val wanted: Boolean)

enum class CallState { RINGING, DIALING, ACTIVE, HOLDING, ENDED, OTHER }

object RecordingPlanner {
    /**
     * Which call is recorded now, or null. A call is recorded while it's active and wanted (by the rules or your
     * tap). With [keepOnHold], the call being recorded keeps recording while it's on hold, until another call
     * becomes active. Ringing and dialling calls are never recorded.
     */
    fun <K> target(calls: List<LiveCall<K>>, current: K?, keepOnHold: Boolean): K? {
        val active = calls.firstOrNull { it.state == CallState.ACTIVE }
        if (active != null) return active.key.takeIf { active.wanted }
        if (keepOnHold && current != null) {
            val cur = calls.firstOrNull { it.key == current }
            if (cur != null && cur.state == CallState.HOLDING && cur.wanted) return current
        }
        return null
    }

    /** The capture service is kept bound while a live call is (or will be) recorded. */
    fun <K> needsCapture(calls: List<LiveCall<K>>): Boolean =
        calls.any { it.wanted && it.state != CallState.ENDED }
}

object RecordingNames {
    const val MAX_NAME = 40
    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /**
     * `yyyyMMdd-HHmmss_<direction>_<contact or number>.<ext>`: letters, digits and + kept, everything else (path
     * separators, dots, spaces, emoji) becomes one dash. [who] blank gives "unknown".
     */
    fun fileName(startedAt: Long, zone: ZoneId, direction: Direction, who: String?, ext: String): String =
        "${stamp.format(Instant.ofEpochMilli(startedAt).atZone(zone))}_${direction.tag}_${sanitize(who)}.$ext"

    fun sanitize(who: String?): String {
        if (who.isNullOrBlank()) return "unknown"
        // A phone number is written compactly: +32470123456, not +32-470-12-34-56.
        val t = who.trim()
        if (t.any { it in '0'..'9' } && t.all { it in '0'..'9' || it in " +-.()/" }) {
            return RecordingRules.normalize(t).take(MAX_NAME).ifEmpty { "unknown" }
        }
        val folded = Normalizer.normalize(who.trim(), Normalizer.Form.NFKC)
        val sb = StringBuilder()
        for (ch in folded) {
            val ok = Character.isLetterOrDigit(ch) || ch == '+'
            if (ok) sb.append(ch) else if (sb.isNotEmpty() && sb.last() != '-') sb.append('-')
            if (sb.length >= MAX_NAME) break
        }
        val out = sb.toString().trim('-')
        return out.ifEmpty { "unknown" }
    }

    /** [name], or name_2, name_3… when [exists] says it's taken. */
    fun unique(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (exists("${base}_$i$ext")) i++
        return "${base}_$i$ext"
    }
}

enum class Direction(val tag: String) { IN("in"), OUT("out"), CONFERENCE("conf") }

object RecordingMatch {
    /**
     * Whether a recording belongs to a call in the call history: same number (or both hidden), and recorded
     * between the call's start and its end. The history counts the talk time only, so two minutes are added for
     * ringing and clocks.
     */
    fun belongs(
        recNumber: String?,
        recStartedAt: Long,
        callNumber: String?,
        callHidden: Boolean,
        callDate: Long,
        callDurationSeconds: Long,
        same: (String, String) -> Boolean,
    ): Boolean {
        val numberOk = when {
            callHidden || callNumber.isNullOrBlank() -> recNumber.isNullOrBlank()
            recNumber.isNullOrBlank() -> false
            else -> same(recNumber, callNumber)
        }
        if (!numberOk) return false
        return recStartedAt >= callDate - 5_000 && recStartedAt <= callDate + callDurationSeconds * 1000 + 120_000
    }
}

object AutoDeletePolicy {
    private const val DAY = 24L * 60 * 60 * 1000

    /** The recordings to delete: older than [days] (by when they were recorded), never the ones still recording. */
    fun <T> expired(items: List<T>, startedAt: (T) -> Long, inProgress: (T) -> Boolean, now: Long, days: Int): List<T> {
        if (days <= 0) return emptyList()
        val cutoff = now - days * DAY
        return items.filter { !inProgress(it) && startedAt(it) in 1 until cutoff }
    }
}

/**
 * Decides whether a stereo capture really keeps the two sides apart. Many phones give the same mixed signal on
 * both channels; then the recording is saved as mono (half the size, nothing lost). Undecided until there was
 * enough sound; after [maxFrames] of near silence it keeps stereo (nothing is lost either way).
 */
class ChannelAnalyzer(private val sampleRate: Int, private val maxSeconds: Int = 8) {
    private var energy = 0.0
    private var difference = 0.0
    private var frames = 0L
    var decision: Boolean? = null
        private set

    /** Feeds interleaved stereo samples (as -1..1). Returns the decision once made: true keeps stereo. */
    fun feed(left: FloatArray, right: FloatArray, count: Int): Boolean? {
        if (decision != null) return decision
        for (i in 0 until count) {
            val l = left[i].toDouble()
            val r = right[i].toDouble()
            energy += l * l + r * r
            val d = l - r
            difference += d * d
        }
        frames += count
        // Enough sound: about a second of normal speech level (or more, quieter).
        if (energy > sampleRate * 0.002) decision = difference / energy > DISTINCT
        else if (frames >= sampleRate.toLong() * maxSeconds) decision = true
        return decision
    }

    companion object {
        /** Relative difference between the channels above which they count as different sides. */
        const val DISTINCT = 0.01
    }
}
