package com.vknxworks.callctrl.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatProbeTest {

    /** A fake phone: which formats have a valid minimum buffer, and which ones actually open. */
    private class FakeDevice(val buffers: Map<Candidate, Int>, val opens: Set<Candidate>) {
        val tried = ArrayList<Candidate>()
        fun min(c: Candidate) = buffers[c] ?: -2 // AudioRecord.ERROR_BAD_VALUE
        fun open(c: Candidate, @Suppress("UNUSED_PARAMETER") min: Int): String? {
            tried += c
            return if (c in opens) "rec:$c" else null
        }
    }

    private fun all(value: Int) = FormatProbe.candidates().associateWith { value }

    @Test
    fun order_is_rate_then_stereo_then_float() {
        val c = FormatProbe.candidates()
        assertEquals(20, c.size)
        assertEquals(Candidate(48_000, 2, true), c[0])
        assertEquals(Candidate(48_000, 2, false), c[1])
        assertEquals(Candidate(48_000, 1, true), c[2])
        assertEquals(Candidate(48_000, 1, false), c[3])
        assertEquals(Candidate(32_000, 2, true), c[4])
        assertEquals(Candidate(8_000, 1, false), c.last())
        assertEquals(listOf(48_000, 32_000, 24_000, 16_000, 8_000), c.map { it.sampleRate }.distinct())
    }

    @Test
    fun picks_the_best_format_that_opens() {
        val dev = FakeDevice(all(3840), setOf(Candidate(48_000, 1, false), Candidate(16_000, 2, true)))
        val picked = FormatProbe.select(FormatProbe.candidates(), dev::min, dev::open)!!
        assertEquals(Candidate(48_000, 1, false), picked.first)
        // It stopped at the first success and tried the better ones before it, in order.
        assertEquals(FormatProbe.candidates().take(4), dev.tried)
    }

    @Test
    fun skips_formats_without_a_valid_minimum_buffer() {
        val buffers = all(-2).toMutableMap()
        buffers[Candidate(24_000, 1, false)] = 960
        buffers[Candidate(16_000, 2, false)] = 1280
        val dev = FakeDevice(buffers, FormatProbe.candidates().toSet())
        val picked = FormatProbe.select(FormatProbe.candidates(), dev::min, dev::open)!!
        assertEquals(Candidate(24_000, 1, false), picked.first)
        // Formats without a valid buffer were never opened.
        assertEquals(listOf(Candidate(24_000, 1, false)), dev.tried)
    }

    @Test
    fun a_zero_buffer_counts_as_invalid() {
        val buffers = all(0).toMutableMap()
        buffers[Candidate(8_000, 1, false)] = 640
        val dev = FakeDevice(buffers, FormatProbe.candidates().toSet())
        assertEquals(Candidate(8_000, 1, false), FormatProbe.select(FormatProbe.candidates(), dev::min, dev::open)!!.first)
    }

    @Test
    fun nothing_opens_gives_null() {
        val dev = FakeDevice(all(1000), emptySet())
        assertNull(FormatProbe.select(FormatProbe.candidates(), dev::min, dev::open))
        assertEquals(20, dev.tried.size)
    }

    @Test
    fun an_exception_while_opening_is_a_refusal() {
        val picked = FormatProbe.select(
            FormatProbe.candidates(),
            { 1000 },
            { c, _ -> if (c.sampleRate == 48_000) throw SecurityException("no") else if (c == Candidate(32_000, 2, false)) "ok" else null },
        )
        assertEquals(Candidate(32_000, 2, false), picked!!.first)
    }

    @Test
    fun split_candidates_are_mono_16_bit_best_first() {
        assertEquals(FormatProbe.RATES.map { Candidate(it, 1, false) }, FormatProbe.splitCandidates())
    }

    @Test
    fun split_mode_says_what_was_captured() {
        assertEquals(CaptureMode.SPLIT, FormatProbe.splitMode(downlink = true, uplink = true))
        assertEquals(CaptureMode.DOWNLINK_ONLY, FormatProbe.splitMode(downlink = true, uplink = false))
        assertEquals(CaptureMode.UPLINK_ONLY, FormatProbe.splitMode(downlink = false, uplink = true))
        assertNull(FormatProbe.splitMode(downlink = false, uplink = false))
    }

    @Test
    fun capture_format_round_trips_and_rejects_garbage() {
        val f = CaptureFormat(CaptureMode.VOICE_CALL, 48_000, 2, true)
        assertEquals(f, CaptureFormat.of(f.toArray()))
        assertEquals(8, f.frameBytes)
        assertEquals(2, CaptureFormat(CaptureMode.SPLIT, 16_000, 1, false).frameBytes)
        assertNull(CaptureFormat.of(IntArray(0)))
        assertNull(CaptureFormat.of(null))
        assertNull(CaptureFormat.of(intArrayOf(9, 48_000, 2, 1)))
        assertNull(CaptureFormat.of(intArrayOf(1, 48_000, 3, 1)))
        assertNull(CaptureFormat.of(intArrayOf(1, 1_000_000, 2, 1)))
        assertNull(CaptureFormat.of(intArrayOf(1, 48_000, 2, 7)))
    }
}
