package com.vknxworks.callctrl

import android.content.Context
import com.vknxworks.callctrl.data.CallHistory
import com.vknxworks.callctrl.data.ContactsRepository
import com.vknxworks.callctrl.data.lock.LockVault
import com.vknxworks.callctrl.data.settings.AppSettings
import com.vknxworks.callctrl.data.settings.SettingsRepository
import com.vknxworks.callctrl.ui.lock.AppLock
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
     * left alone; CallCtrl's own settings are reset and the lock turned off.
     */
    suspend fun forgetEverything() {
        settings.update { AppSettings() }
        vault.disable()
        AppLock.markUnlocked()
    }

    fun onLocked() {
        vault.close()
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
    }
}

val Context.container: AppContainer
    get() = (applicationContext as CallCtrlApp).container
