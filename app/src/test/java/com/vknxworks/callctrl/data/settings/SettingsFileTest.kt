package com.vknxworks.callctrl.data.settings

import com.vknxworks.callctrl.data.quiet.QuietHours
import com.vknxworks.callctrl.recording.AutoDelete
import com.vknxworks.callctrl.recording.RecordFormat
import com.vknxworks.callctrl.recording.RecordMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFileTest {
    private val dial = listOf(SpeedDial(2, "+32470123456", "Sam"), SpeedDial(9, "0470 11 22 33"))
    private val quiet = QuietHours(enabled = true, startMinute = 23 * 60, endMinute = 6 * 60, days = setOf(1, 2, 3))

    @Test fun roundTrip() {
        val mine = AppSettings(
            keypadTones = false,
            confirmCalls = true,
            flipToSilence = true,
            hideScreenshots = true,
            recordFormat = RecordFormat.OPUS,
            speedDial = dial,
            declineMessages = listOf("Bel je zo terug", "In vergadering"),
        )
        val plan = SettingsFile.plan(SettingsFile.export(mine, quiet), AppSettings(), QuietHours())!!
        assertEquals(mine, plan.settings)
        assertEquals(5, plan.changes.size)
        assertTrue(plan.speedDialChanges)
        assertTrue(plan.messagesChange)
        assertEquals(quiet, plan.quiet)
        assertTrue(plan.refused.isEmpty())
        assertFalse(plan.nothing)
        // The same file again changes nothing.
        assertTrue(SettingsFile.plan(SettingsFile.export(mine, quiet), mine, quiet)!!.nothing)
    }

    @Test fun recordingChoicesAndTheLockNeverTravel() {
        val text = SettingsFile.export(
            AppSettings(recordMode = RecordMode.ALWAYS, recordCopyToMusic = true, recordAutoDelete = AutoDelete.entries.last(), lockTimeout = LockTimeout.HOUR_1),
            QuietHours(),
        )
        assertFalse(text.contains("record_mode"))
        assertFalse(text.contains("record_copy"))
        assertFalse(text.contains("auto_delete"))
        assertFalse(text.contains("lock"))
        val forged = """{"app":"CallCtrl","format":1,"settings":{"record_mode":"ALWAYS","record_copy_music":"true","record_auto_delete":"DAYS_7","lock_timeout":"RESTART","record_notice_seen":"true"}}"""
        val plan = SettingsFile.plan(forged, AppSettings(), QuietHours())!!
        assertEquals(AppSettings(), plan.settings)
        assertTrue(plan.nothing)
    }

    @Test fun anImportNeverTurnsHidingOff() {
        val careful = AppSettings(hideScreenshots = true, hideRecents = true)
        val plan = SettingsFile.plan(SettingsFile.export(AppSettings(), QuietHours()), careful, QuietHours())!!
        assertEquals(careful, plan.settings)
        assertEquals(setOf(SettingField.HIDE_SCREENSHOTS, SettingField.HIDE_RECENTS), plan.refused.toSet())
        assertTrue(plan.changes.isEmpty())
    }

    @Test fun otherFilesAreRefusedAndOddValuesCleaned() {
        assertNull(SettingsFile.plan("nope", AppSettings(), QuietHours()))
        assertNull(SettingsFile.plan("""{"app":"TextCtrl","format":1}""", AppSettings(), QuietHours()))
        assertNull(SettingsFile.plan("""{"app":"CallCtrl","format":9}""", AppSettings(), QuietHours()))
        val odd = """{"app":"CallCtrl","format":1,"settings":{"keypad_tones":"perhaps","record_format":"MP3"},
            "speedDial":[{"key":1,"number":"112"},{"key":5,"number":"abc"},{"key":4,"number":"0470"},{"key":4,"number":"0471"}],
            "declineMessages":[" ", "Later", "Later"],
            "quiet":{"enabled":true,"startMinute":99999,"endMinute":-4,"days":[0,7,8]}}"""
        val plan = SettingsFile.plan(odd, AppSettings(), QuietHours())!!
        assertTrue(plan.changes.isEmpty())
        assertEquals(listOf(SpeedDial(4, "0471")), plan.settings.speedDial)
        assertEquals(listOf("Later"), plan.settings.declineMessages)
        assertEquals(QuietHours(enabled = true, startMinute = 24 * 60 - 1, endMinute = 0, days = setOf(7)), plan.quiet)
    }
}
