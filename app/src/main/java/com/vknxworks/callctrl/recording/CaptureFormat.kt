package com.vknxworks.callctrl.recording

/**
 * What the capture service records and how. Pure Kotlin (no Android), shared by CallCtrl and the capture process,
 * so the probing order and the choice are unit-tested.
 */
enum class CaptureMode(val code: Int) {
    /** Android's call-audio source: both sides of the call, mixed by the phone. */
    VOICE_CALL(1),
    /** The other person and you captured separately (downlink and uplink) and mixed by CallCtrl. */
    SPLIT(2),
    /** Only the other person's voice (the phone refused the uplink). */
    DOWNLINK_ONLY(3),
    /** Only your voice as the network gets it (the phone refused the downlink). */
    UPLINK_ONLY(4),
    ;

    companion object {
        fun of(code: Int): CaptureMode? = entries.firstOrNull { it.code == code }
    }
}

/** A capture format the service is asked to try. */
data class Candidate(val sampleRate: Int, val channels: Int, val float: Boolean)

/** The format the capture service actually records in. */
data class CaptureFormat(val mode: CaptureMode, val sampleRate: Int, val channels: Int, val float: Boolean) {
    val bytesPerSample: Int get() = if (float) 4 else 2
    val frameBytes: Int get() = bytesPerSample * channels

    fun toArray(): IntArray = intArrayOf(mode.code, sampleRate, channels, if (float) 1 else 0)

    companion object {
        /** Reads what [toArray] wrote; null for an empty or invalid array (the capture failed). */
        fun of(a: IntArray?): CaptureFormat? {
            if (a == null || a.size < 4) return null
            val mode = CaptureMode.of(a[0]) ?: return null
            if (a[1] !in 4_000..192_000 || a[2] !in 1..2 || a[3] !in 0..1) return null
            return CaptureFormat(mode, a[1], a[2], a[3] == 1)
        }
    }
}

object FormatProbe {
    /** Sample rates to try, best first. */
    val RATES = listOf(48_000, 32_000, 24_000, 16_000, 8_000)

    /**
     * Every format to try for the call-audio source, best first: the highest sample rate wins, then stereo
     * (which may keep you and the other person apart) before mono, then 32-bit float before 16-bit.
     */
    fun candidates(): List<Candidate> = RATES.flatMap { rate ->
        listOf(2, 1).flatMap { ch -> listOf(true, false).map { f -> Candidate(rate, ch, f) } }
    }

    /** For the separate downlink and uplink fallback: mono 16-bit at each rate, best first. */
    fun splitCandidates(): List<Candidate> = RATES.map { Candidate(it, 1, false) }

    /**
     * The first candidate that opens, with what opening it returned. [minBuffer] is AudioRecord's minimum buffer
     * for the candidate (a candidate without a valid one, i.e. <= 0, is skipped without trying it); [open] tries
     * to open and start a recorder and returns it, or null when the phone refuses.
     */
    fun <T : Any> select(
        candidates: List<Candidate>,
        minBuffer: (Candidate) -> Int,
        open: (Candidate, Int) -> T?,
    ): Pair<Candidate, T>? {
        for (c in candidates) {
            val min = minBuffer(c)
            if (min <= 0) continue
            val opened = runCatching { open(c, min) }.getOrNull() ?: continue
            return c to opened
        }
        return null
    }

    /** Which of the two one-sided sources opened decides what the fallback captured. */
    fun splitMode(downlink: Boolean, uplink: Boolean): CaptureMode? = when {
        downlink && uplink -> CaptureMode.SPLIT
        downlink -> CaptureMode.DOWNLINK_ONLY
        uplink -> CaptureMode.UPLINK_ONLY
        else -> null
    }
}
