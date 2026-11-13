package com.vknxworks.callctrl.data.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vknxworks.callctrl.R
import com.vknxworks.callctrl.recording.AutoDelete
import com.vknxworks.callctrl.recording.RecordFormat
import com.vknxworks.callctrl.recording.RecordMode
import com.vknxworks.callctrl.recording.SelectedContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "callctrl")

/** How long CallCtrl can be in the background before the app lock asks again (-1: only after a restart). */
enum class LockTimeout(@StringRes val label: Int, val millis: Long) {
    IMMEDIATELY(R.string.lock_again_immediately, 0L),
    MINUTE_1(R.string.lock_again_1m, 60_000L),
    MINUTES_5(R.string.lock_again_5m, 5 * 60_000L),
    MINUTES_15(R.string.lock_again_15m, 15 * 60_000L),
    HOUR_1(R.string.lock_again_1h, 60 * 60_000L),
    RESTART(R.string.lock_again_restart, -1L),
}

data class AppSettings(
    /** Keypad tones while dialling (Android's own "Dial pad tones" setting decides too). */
    val keypadTones: Boolean = true,
    /** A short vibration on every keypad press. */
    val keypadHaptics: Boolean = true,
    /** Calls in a row with the same number are shown as one line in Recents. */
    val groupRecents: Boolean = true,
    /** You chose to use CallCtrl without making it the default phone app. */
    val skippedDefault: Boolean = false,
    /** App lock: when it asks again after CallCtrl was in the background. */
    val lockTimeout: LockTimeout = LockTimeout.IMMEDIATELY,
    /** "Hide in screenshots": FLAG_SECURE on the app's windows (screenshots, recordings and the Recents preview). */
    val hideScreenshots: Boolean = false,
    /** "Hide in recent apps": the Recents view shows a blank card. Screenshots still work. */
    val hideRecents: Boolean = false,
    /** Call recording: which calls are recorded. Off by default. */
    val recordMode: RecordMode = RecordMode.OFF,
    /** The file type of new recordings. */
    val recordFormat: RecordFormat = RecordFormat.FLAC,
    /** Keep recording while the call is on hold (otherwise it stops and a new file starts when it resumes). */
    val recordOnHold: Boolean = false,
    /** Also save a copy of each recording to Music/CallCtrl. */
    val recordCopyToMusic: Boolean = false,
    /** Delete recordings older than this. */
    val recordAutoDelete: AutoDelete = AutoDelete.NEVER,
    /** The legal notice was shown and accepted (the first time recording was turned on). */
    val recordNoticeSeen: Boolean = false,
    /** "Only selected contacts": the contacts whose calls are recorded. */
    val recordContacts: List<SelectedContact> = emptyList(),
)

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val keypadTones = booleanPreferencesKey("keypad_tones")
        val keypadHaptics = booleanPreferencesKey("keypad_haptics")
        val groupRecents = booleanPreferencesKey("group_recents")
        val skippedDefault = booleanPreferencesKey("skipped_default")
        val lockTimeout = stringPreferencesKey("lock_timeout")
        val hideScreenshots = booleanPreferencesKey("hide_screenshots")
        val hideRecents = booleanPreferencesKey("hide_recents")
        val recordMode = stringPreferencesKey("record_mode")
        val recordFormat = stringPreferencesKey("record_format")
        val recordOnHold = booleanPreferencesKey("record_on_hold")
        val recordCopyToMusic = booleanPreferencesKey("record_copy_music")
        val recordAutoDelete = stringPreferencesKey("record_auto_delete")
        val recordNoticeSeen = booleanPreferencesKey("record_notice_seen")
        val recordContacts = stringPreferencesKey("record_contacts")
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val contactsSerializer = ListSerializer(SelectedContact.serializer())

    private val prefs: Flow<Preferences> = context.appDataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: StateFlow<AppSettings> = prefs
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private fun Preferences.toSettings() = AppSettings(
        keypadTones = this[K.keypadTones] ?: true,
        keypadHaptics = this[K.keypadHaptics] ?: true,
        groupRecents = this[K.groupRecents] ?: true,
        skippedDefault = this[K.skippedDefault] ?: false,
        lockTimeout = enumOr(this[K.lockTimeout], LockTimeout.IMMEDIATELY),
        hideScreenshots = this[K.hideScreenshots] ?: false,
        hideRecents = this[K.hideRecents] ?: false,
        recordMode = enumOr(this[K.recordMode], RecordMode.OFF),
        recordFormat = enumOr(this[K.recordFormat], RecordFormat.FLAC),
        recordOnHold = this[K.recordOnHold] ?: false,
        recordCopyToMusic = this[K.recordCopyToMusic] ?: false,
        recordAutoDelete = enumOr(this[K.recordAutoDelete], AutoDelete.NEVER),
        recordNoticeSeen = this[K.recordNoticeSeen] ?: false,
        recordContacts = this[K.recordContacts]?.let { runCatching { json.decodeFromString(contactsSerializer, it) }.getOrNull() }.orEmpty(),
    )

    /** The saved settings, read from disk (not the in-memory default before the first read). */
    suspend fun load(): AppSettings = prefs.first().toSettings()

    /** Saves a change. If the phone's storage fails, the change is dropped instead of closing the app. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        try {
            context.appDataStore.edit { p ->
                val s = transform(p.toSettings())
                p[K.keypadTones] = s.keypadTones
                p[K.keypadHaptics] = s.keypadHaptics
                p[K.groupRecents] = s.groupRecents
                p[K.skippedDefault] = s.skippedDefault
                p[K.lockTimeout] = s.lockTimeout.name
                p[K.hideScreenshots] = s.hideScreenshots
                p[K.hideRecents] = s.hideRecents
                p[K.recordMode] = s.recordMode.name
                p[K.recordFormat] = s.recordFormat.name
                p[K.recordOnHold] = s.recordOnHold
                p[K.recordCopyToMusic] = s.recordCopyToMusic
                p[K.recordAutoDelete] = s.recordAutoDelete.name
                p[K.recordNoticeSeen] = s.recordNoticeSeen
                p[K.recordContacts] = json.encodeToString(contactsSerializer, s.recordContacts)
            }
        } catch (e: IOException) {
            // Nothing to do: the settings stay as they were.
        }
    }
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
