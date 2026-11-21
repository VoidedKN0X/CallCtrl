package com.vknxworks.callctrl.data.settings

import com.vknxworks.callctrl.data.quiet.QuietHours
import com.vknxworks.callctrl.recording.RecordFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A switch or choice that travels in a settings file. */
enum class SettingField(val key: String) {
    KEYPAD_TONES("keypad_tones"),
    KEYPAD_HAPTICS("keypad_haptics"),
    GROUP_RECENTS("group_recents"),
    CONFIRM_CALLS("confirm_calls"),
    FLIP_TO_SILENCE("flip_to_silence"),
    HIDE_SCREENSHOTS("hide_screenshots"),
    HIDE_RECENTS("hide_recents"),
    RECORD_FORMAT("record_format"),
    RECORD_ON_HOLD("record_on_hold"),
}

/** One setting an import would change, with the new value as it is written in the file. */
data class SettingChange(val field: SettingField, val to: String)

/** What importing a settings file would do, worked out before anything is changed. */
data class SettingsImport(
    /** The settings as they would be afterwards (speed dial and decline messages included). */
    val settings: AppSettings,
    val changes: List<SettingChange>,
    /** Left as they are, because the file would make CallCtrl less private. */
    val refused: List<SettingField>,
    /** The file's speed dial replaces yours. */
    val speedDialChanges: Boolean,
    /** The file's decline messages replace yours. */
    val messagesChange: Boolean,
    /** Quiet mode as it would be afterwards, or null when the file has none or it is the same as now. */
    val quiet: QuietHours?,
) {
    val nothing: Boolean get() = changes.isEmpty() && !speedDialChanges && !messagesChange && quiet == null
}

/**
 * CallCtrl's settings as a file: export and import. Pure Kotlin, unit-tested.
 *
 * In the file: the keypad and Recents switches, "Confirm before calling", "Flip to silence", the two "Hide in"
 * switches, the recording file type and "Keep recording on hold", your speed dial, your decline messages and
 * quiet mode. Never in the file: the app lock (PIN, fingerprint, when it locks again), whether and whose calls
 * are recorded, the copy to Music and "Delete after" (an import must never start recording, publish or delete
 * recordings), call screening rules (they have their own export), notes, reminders and call history.
 * An import can turn "Hide in screenshots" and "Hide in recent apps" on but never off.
 */
object SettingsFile {
    const val APP = "CallCtrl"
    const val FORMAT = 1
    /** Larger files aren't read at all. */
    const val MAX_BYTES = 256 * 1024

    @Serializable
    private data class Doc(
        val app: String = "",
        val format: Int = 0,
        val settings: Map<String, String> = emptyMap(),
        val speedDial: List<SpeedDial>? = null,
        val declineMessages: List<String>? = null,
        val quiet: QuietHours? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private fun read(s: AppSettings, f: SettingField): String = when (f) {
        SettingField.KEYPAD_TONES -> s.keypadTones.toString()
        SettingField.KEYPAD_HAPTICS -> s.keypadHaptics.toString()
        SettingField.GROUP_RECENTS -> s.groupRecents.toString()
        SettingField.CONFIRM_CALLS -> s.confirmCalls.toString()
        SettingField.FLIP_TO_SILENCE -> s.flipToSilence.toString()
        SettingField.HIDE_SCREENSHOTS -> s.hideScreenshots.toString()
        SettingField.HIDE_RECENTS -> s.hideRecents.toString()
        SettingField.RECORD_FORMAT -> s.recordFormat.name
        SettingField.RECORD_ON_HOLD -> s.recordOnHold.toString()
    }

    private fun bool(raw: String): Boolean? = when (raw) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /** [s] with [f] set to [raw], or null when [raw] isn't a value CallCtrl knows. */
    private fun write(s: AppSettings, f: SettingField, raw: String): AppSettings? = when (f) {
        SettingField.KEYPAD_TONES -> bool(raw)?.let { s.copy(keypadTones = it) }
        SettingField.KEYPAD_HAPTICS -> bool(raw)?.let { s.copy(keypadHaptics = it) }
        SettingField.GROUP_RECENTS -> bool(raw)?.let { s.copy(groupRecents = it) }
        SettingField.CONFIRM_CALLS -> bool(raw)?.let { s.copy(confirmCalls = it) }
        SettingField.FLIP_TO_SILENCE -> bool(raw)?.let { s.copy(flipToSilence = it) }
        SettingField.HIDE_SCREENSHOTS -> bool(raw)?.let { s.copy(hideScreenshots = it) }
        SettingField.HIDE_RECENTS -> bool(raw)?.let { s.copy(hideRecents = it) }
        SettingField.RECORD_FORMAT -> RecordFormat.entries.firstOrNull { it.name == raw }?.let { s.copy(recordFormat = it) }
        SettingField.RECORD_ON_HOLD -> bool(raw)?.let { s.copy(recordOnHold = it) }
    }

    private fun weakens(f: SettingField, from: String, to: String): Boolean =
        (f == SettingField.HIDE_SCREENSHOTS || f == SettingField.HIDE_RECENTS) && from == "true" && to == "false"

    fun export(settings: AppSettings, quiet: QuietHours): String = json.encodeToString(
        Doc.serializer(),
        Doc(
            app = APP,
            format = FORMAT,
            settings = SettingField.entries.associate { it.key to read(settings, it) },
            speedDial = DialRules.cleanSpeedDial(settings.speedDial),
            declineMessages = DialRules.cleanMessages(settings.declineMessages),
            quiet = quiet.cleaned(),
        ),
    )

    /**
     * Reads a settings file and works out what it would change. Null when [text] isn't a CallCtrl settings
     * file (or one from a newer format). Unknown settings and values are skipped.
     */
    fun plan(text: String, current: AppSettings, currentQuiet: QuietHours): SettingsImport? {
        val doc = runCatching { json.decodeFromString(Doc.serializer(), text) }.getOrNull() ?: return null
        if (doc.app != APP || doc.format != FORMAT) return null
        var next = current
        val changes = ArrayList<SettingChange>()
        val refused = ArrayList<SettingField>()
        for (f in SettingField.entries) {
            val raw = doc.settings[f.key] ?: continue
            val from = read(next, f)
            if (raw == from) continue
            val changed = write(next, f, raw) ?: continue
            if (weakens(f, from, raw)) {
                refused += f
                continue
            }
            next = changed
            changes += SettingChange(f, raw)
        }
        val speedDial = doc.speedDial?.let(DialRules::cleanSpeedDial)
        val speedDialChanges = speedDial != null && speedDial != DialRules.cleanSpeedDial(current.speedDial)
        if (speedDialChanges && speedDial != null) next = next.copy(speedDial = speedDial)
        val messages = doc.declineMessages?.let(DialRules::cleanMessages)
        val messagesChange = messages != null && messages != DialRules.cleanMessages(current.declineMessages)
        if (messagesChange && messages != null) next = next.copy(declineMessages = messages)
        val quiet = doc.quiet?.cleaned()?.takeIf { it != currentQuiet.cleaned() }
        return SettingsImport(next, changes, refused, speedDialChanges, messagesChange, quiet)
    }
}
