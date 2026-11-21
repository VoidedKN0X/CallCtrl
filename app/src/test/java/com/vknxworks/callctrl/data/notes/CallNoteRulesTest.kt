package com.vknxworks.callctrl.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallNoteRulesTest {
    @Test fun tagsAreTidied() {
        assertEquals(listOf("work", "follow up"), CallNoteRules.parseTags(" work, Follow up ,#Work,, "))
        assertEquals(CallNoteRules.MAX_TAGS, CallNoteRules.parseTags((1..20).joinToString(",") { "t$it" }).size)
        assertEquals(CallNoteRules.MAX_TAG, CallNoteRules.parseTags("x".repeat(100)).single().length)
    }

    @Test fun cleaningCutsAndEmptiesCount() {
        val clean = CallNoteRules.clean(CallNote(1, 2, "  " + "n".repeat(900) + " ", listOf("#A", "a", "b")))
        assertEquals(CallNoteRules.MAX_NOTE, clean.note.length)
        assertEquals(listOf("a", "b"), clean.tags)
        assertTrue(CallNoteRules.clean(CallNote(1, 2, "   ", listOf(" ", "#"))).empty)
    }

    @Test fun searchLooksAtTextAndTags() {
        val note = CallNote(1, 2, "Quote for the kitchen", listOf("work", "plumber"))
        assertTrue(CallNoteRules.matches(note, "kitchen"))
        assertTrue(CallNoteRules.matches(note, "KITCH"))
        assertTrue(CallNoteRules.matches(note, "plum"))
        assertTrue(CallNoteRules.matches(note, "#work"))
        assertFalse(CallNoteRules.matches(note, "#kitchen"))
        assertFalse(CallNoteRules.matches(note, "garden"))
        assertFalse(CallNoteRules.matches(note, " "))
        assertFalse(CallNoteRules.matches(note, "#"))
    }

    @Test fun theKeyHoldsTheTimeOfTheCall() {
        assertEquals("7@1000", CallNoteRules.key(7, 1000))
    }
}
