package com.vknxworks.callctrl.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class RecordingRulesTest {
    private val contact = CallFacts("+32 470 12 34 56", hidden = false, isContact = true)
    private val stranger = CallFacts("0470 99 88 77", hidden = false, isContact = false)
    private val hidden = CallFacts(null, hidden = true, isContact = false)
    private val anna = SelectedContact("k1", "Anna", listOf("0470 12 34 56"))

    /** Like the app: the number as given plus its Belgian national/international form. */
    private val belgian: (String) -> Set<String> = { n ->
        val d = RecordingRules.normalize(n)
        buildSet {
            add(d)
            if (d.startsWith("+32")) add("0" + d.removePrefix("+32"))
            if (d.startsWith("0")) add("+32" + d.removePrefix("0"))
        }
    }

    @Test
    fun off_and_ask_never_record_by_themselves() {
        for (m in listOf(RecordMode.OFF, RecordMode.ASK)) {
            assertFalse(RecordingRules.autoRecord(m, contact, listOf(anna)))
            assertFalse(RecordingRules.autoRecord(m, stranger, emptyList()))
            assertFalse(RecordingRules.autoRecord(m, hidden, emptyList()))
        }
    }

    @Test
    fun always_records_everything() {
        assertTrue(RecordingRules.autoRecord(RecordMode.ALWAYS, contact, emptyList()))
        assertTrue(RecordingRules.autoRecord(RecordMode.ALWAYS, hidden, emptyList()))
        assertTrue(RecordingRules.autoRecord(RecordMode.ALWAYS, CallFacts(null, false, false, isConference = true), emptyList()))
    }

    @Test
    fun unknown_records_strangers_and_hidden_numbers_only() {
        assertFalse(RecordingRules.autoRecord(RecordMode.UNKNOWN, contact, emptyList()))
        assertTrue(RecordingRules.autoRecord(RecordMode.UNKNOWN, stranger, emptyList()))
        assertTrue(RecordingRules.autoRecord(RecordMode.UNKNOWN, hidden, emptyList()))
        assertTrue(RecordingRules.autoRecord(RecordMode.UNKNOWN, CallFacts("", hidden = false, isContact = false), emptyList()))
        // A conference has no number of its own: not "unknown".
        assertFalse(RecordingRules.autoRecord(RecordMode.UNKNOWN, CallFacts(null, false, false, isConference = true), emptyList()))
    }

    @Test
    fun selected_matches_the_picked_contacts_numbers_in_any_form() {
        assertTrue(RecordingRules.autoRecord(RecordMode.SELECTED, contact, listOf(anna), belgian))
        assertFalse(RecordingRules.autoRecord(RecordMode.SELECTED, stranger, listOf(anna), belgian))
        assertFalse(RecordingRules.autoRecord(RecordMode.SELECTED, contact, emptyList(), belgian))
        assertFalse(RecordingRules.autoRecord(RecordMode.SELECTED, hidden, listOf(anna), belgian))
        // Without the country forms, +32… and 0… are different numbers.
        assertFalse(RecordingRules.autoRecord(RecordMode.SELECTED, contact, listOf(anna)))
        assertTrue(RecordingRules.autoRecord(RecordMode.SELECTED, CallFacts("0470-12.34.56", false, true), listOf(anna)))
    }

    @Test
    fun record_button_shows_in_every_mode_but_off() {
        assertFalse(RecordingRules.showRecordButton(RecordMode.OFF))
        RecordMode.entries.filter { it != RecordMode.OFF }.forEach { assertTrue(RecordingRules.showRecordButton(it)) }
    }

    // ------------------------------------------------------------------ planner

    private fun call(key: String, state: CallState, wanted: Boolean = true) = LiveCall(key, state, wanted)

    @Test
    fun records_only_the_active_wanted_call() {
        assertEquals("a", RecordingPlanner.target(listOf(call("a", CallState.ACTIVE)), null, false))
        assertNull(RecordingPlanner.target(listOf(call("a", CallState.ACTIVE, wanted = false)), null, false))
        assertNull(RecordingPlanner.target(listOf(call("a", CallState.RINGING)), null, false))
        assertNull(RecordingPlanner.target(listOf(call("a", CallState.DIALING)), null, false))
    }

    @Test
    fun hold_stops_unless_kept() {
        val held = listOf(call("a", CallState.HOLDING))
        assertNull(RecordingPlanner.target(held, "a", keepOnHold = false))
        assertEquals("a", RecordingPlanner.target(held, "a", keepOnHold = true))
        // Kept on hold only for the call already being recorded.
        assertNull(RecordingPlanner.target(held, null, keepOnHold = true))
    }

    @Test
    fun a_second_call_splits_the_recording() {
        // A on hold, B answered: B is recorded (a new file), even with keep-on-hold.
        val calls = listOf(call("a", CallState.HOLDING), call("b", CallState.ACTIVE))
        assertEquals("b", RecordingPlanner.target(calls, "a", keepOnHold = true))
        // B isn't wanted: nothing is recorded while A waits on hold.
        val notB = listOf(call("a", CallState.HOLDING), call("b", CallState.ACTIVE, wanted = false))
        assertNull(RecordingPlanner.target(notB, "a", keepOnHold = true))
        // Swap back to A.
        val back = listOf(call("a", CallState.ACTIVE), call("b", CallState.HOLDING))
        assertEquals("a", RecordingPlanner.target(back, "b", keepOnHold = false))
    }

    @Test
    fun ended_calls_stop_and_release_the_capture() {
        assertNull(RecordingPlanner.target(listOf(call("a", CallState.ENDED)), "a", true))
        assertFalse(RecordingPlanner.needsCapture(listOf(call("a", CallState.ENDED))))
        assertTrue(RecordingPlanner.needsCapture(listOf(call("a", CallState.RINGING))))
        assertFalse(RecordingPlanner.needsCapture(listOf(call("a", CallState.ACTIVE, wanted = false))))
    }

    // ------------------------------------------------------------------ names

    private val utc: ZoneId = ZoneOffset.UTC
    private val t = 1_763_040_305_000L // 2025-11-13 13:25:05 UTC

    @Test
    fun file_name_format() {
        assertEquals("20251113-132505_in_Anna-Peeters.flac", RecordingNames.fileName(t, utc, Direction.IN, "Anna Peeters", "flac"))
        assertEquals("20251113-132505_out_+32470123456.wav", RecordingNames.fileName(t, utc, Direction.OUT, "+32 470 12 34 56", "wav"))
        assertEquals("20251113-132505_conf_conference.ogg", RecordingNames.fileName(t, utc, Direction.CONFERENCE, "conference", "ogg"))
        assertEquals("20251113-132505_in_unknown.m4a", RecordingNames.fileName(t, utc, Direction.IN, null, "m4a"))
    }

    @Test
    fun sanitising_removes_paths_and_odd_characters() {
        assertEquals("etc-passwd", RecordingNames.sanitize("../../etc/passwd"))
        assertEquals("a-b-c", RecordingNames.sanitize("a\\b:c"))
        assertEquals("Zoë-Dupré", RecordingNames.sanitize("  Zoë  Dupré  "))
        assertEquals("Mum", RecordingNames.sanitize("Mum ❤️"))
        assertEquals("unknown", RecordingNames.sanitize("...///"))
        assertEquals("unknown", RecordingNames.sanitize("   "))
        assertEquals("unknown", RecordingNames.sanitize(""))
        assertEquals("o-Brien", RecordingNames.sanitize("o'Brien"))
        val long = RecordingNames.sanitize("x".repeat(200))
        assertEquals(RecordingNames.MAX_NAME, long.length)
        // Fullwidth digits fold to plain ones.
        assertEquals("0470", RecordingNames.sanitize("０４７０"))
    }

    @Test
    fun unique_adds_a_counter() {
        val taken = setOf("a.flac", "a_2.flac")
        assertEquals("b.flac", RecordingNames.unique("b.flac") { it in taken })
        assertEquals("a_3.flac", RecordingNames.unique("a.flac") { it in taken })
    }

    // ------------------------------------------------------------------ auto-delete

    private data class Rec(val name: String, val startedAt: Long, val recording: Boolean = false)

    @Test
    fun auto_delete_selects_only_old_finished_recordings() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        val items = listOf(
            Rec("new", now - 1 * day),
            Rec("edge", now - 30 * day),
            Rec("old", now - 31 * day),
            Rec("ancient-but-recording", now - 90 * day, recording = true),
            Rec("no-date", 0),
        )
        val expired = AutoDeletePolicy.expired(items, { it.startedAt }, { it.recording }, now, 30)
        assertEquals(listOf("old"), expired.map { it.name })
        assertTrue(AutoDeletePolicy.expired(items, { it.startedAt }, { it.recording }, now, 0).isEmpty())
        assertEquals(listOf("new", "edge", "old"), AutoDeletePolicy.expired(items, { it.startedAt }, { it.recording }, now + 365 * day, 7).map { it.name })
    }

    // ------------------------------------------------------------------ Recents

    @Test
    fun recordings_belong_to_their_call() {
        val same = { a: String, b: String -> RecordingRules.normalize(a) == RecordingRules.normalize(b) }
        val date = 1_000_000L
        assertTrue(RecordingMatch.belongs("0470 12", date + 20_000, "047012", false, date, 60, same))
        assertTrue(RecordingMatch.belongs("0470 12", date + 175_000, "047012", false, date, 60, same))
        assertFalse(RecordingMatch.belongs("0470 12", date + 190_000, "047012", false, date, 60, same))
        assertFalse(RecordingMatch.belongs("0470 12", date - 60_000, "047012", false, date, 60, same))
        assertFalse(RecordingMatch.belongs("0470 13", date + 20_000, "047012", false, date, 60, same))
        assertTrue(RecordingMatch.belongs(null, date + 20_000, "", true, date, 60, same))
        assertFalse(RecordingMatch.belongs("0470", date + 20_000, "", true, date, 60, same))
        assertFalse(RecordingMatch.belongs(null, date + 20_000, "047012", false, date, 60, same))
    }

    // ------------------------------------------------------------------ stereo or mono

    @Test
    fun identical_channels_become_mono() {
        val a = ChannelAnalyzer(16_000)
        val l = FloatArray(16_000) { (Math.sin(it / 10.0) * 0.3).toFloat() }
        assertEquals(false, a.feed(l, l.copyOf(), l.size))
    }

    @Test
    fun separate_sides_stay_stereo() {
        val a = ChannelAnalyzer(16_000)
        val l = FloatArray(16_000) { (Math.sin(it / 10.0) * 0.3).toFloat() }
        val r = FloatArray(16_000) // the other side is silent for now
        assertEquals(true, a.feed(l, r, l.size))
    }

    @Test
    fun silence_is_undecided_then_kept_stereo() {
        val a = ChannelAnalyzer(8_000, maxSeconds = 2)
        val z = FloatArray(8_000)
        assertNull(a.feed(z, z, z.size))
        assertEquals(true, a.feed(z, z, z.size))
    }
}
