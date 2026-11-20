package com.vknxworks.callctrl

import android.content.Context
import com.vknxworks.callctrl.data.CallHistory
import com.vknxworks.callctrl.data.crypto.DataKeyStore
import com.vknxworks.callctrl.data.crypto.KeystoreWrapper
import com.vknxworks.callctrl.data.ContactsRepository
import com.vknxworks.callctrl.data.lock.LockVault
import com.vknxworks.callctrl.data.screening.ScreeningStore
import com.vknxworks.callctrl.data.settings.AppSettings
import com.vknxworks.callctrl.data.settings.SettingsRepository
import com.vknxworks.callctrl.recording.RecordingStore
import com.vknxworks.callctrl.ui.lock.AppLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Manual dependency container; one per process. */
class AppContainer(val context: Context) {
    /** Background work that outlives a screen. A failure there must never close the app. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, _ -> })

    val settings = SettingsRepository(context, appScope)
    val contacts = ContactsRepository(context, appScope)
    val history = CallHistory(context, appScope)

    /** Call screening rules (Settings > Call screening), also read by the screening service. */
    val screening = ScreeningStore(context, appScope)

    /**
     * The key finished call recordings are encrypted with at rest (`files/data.key`, wrapped by a Keystore key).
     * Making this object reads nothing; the key is only asked for on background threads, when a recording is
     * sealed or opened. Nothing on the call or screening path uses it.
     */
    private val dataKeys = DataKeyStore(context.filesDir, KeystoreWrapper(DATA_KEY_ALIAS))

    /** Call recordings (Settings > Call recording), in CallCtrl's private files. */
    val recordings = RecordingStore(context, appScope, dataKeys)

    /** Open the Recordings screen (from a call-recording notification). */
    val showRecordings = MutableStateFlow(false)

    /** A number another app (or a missed call) asked to dial; the keypad picks it up. */
    val dialNumber = MutableStateFlow<String?>(null)

    /** Open the Recents tab (from the missed-call notification). */
    val showRecents = MutableStateFlow(false)

    /** The app lock's PIN and fingerprint key (the shared Ctrl series lock). */
    val vault = LockVault(context, "callctrl")

    suspend fun enableLock(pin: String) {
        AppLock.markUnlocked()
        withContext(Dispatchers.Default) { vault.setUp(pin) }
    }

    fun disableLock() {
        vault.disable()
    }

    /**
     * "Forgot PIN?": the PIN can't be recovered. Your call history and contacts belong to the phone, so they are
     * left alone; CallCtrl's own settings are reset and the lock turned off. Call recordings are deleted only when
     * you choose to ([deleteRecordings]); the app lock was what kept them private.
     */
    suspend fun forgetEverything(deleteRecordings: Boolean) {
        settings.update { AppSettings() }
        screening.reset()
        if (deleteRecordings) recordings.deleteAll()
        vault.disable()
        AppLock.markUnlocked()
    }

    fun onLocked() {
        vault.close()
        // Decrypted copies of recordings made for playing or sharing don't stay in the cache behind the lock.
        recordings.clearTemp()
    }

    fun onUnlocked() {
        AppLock.markUnlocked()
    }

    /** Starts watching contacts and call history (once their permissions are granted). */
    fun startData() {
        contacts.start()
        history.start()
    }

    init {
        AppLock.onLocked = { onLocked() }
        // Recordings: fix any cut off by a crash, then apply "Delete after" (both off the main thread).
        appScope.launch {
            recordings.clearTemp()
            recordings.repairUnfinished()
            recordings.autoDelete(settings.load().recordAutoDelete)
            // Then, in the background and only when no call is on: encrypt finished recordings that aren't yet.
            recordings.reload()
        }
    }

    private companion object {
        const val DATA_KEY_ALIAS = "callctrl_data_at_rest"
    }
}

val Context.container: AppContainer
    get() = (applicationContext as CallCtrlApp).container
