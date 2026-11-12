package com.vknxworks.callctrl.calls

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.VideoProfile
import com.vknxworks.callctrl.data.Caller
import com.vknxworks.callctrl.data.ContactsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One call as the call screen shows it. */
data class CallInfo(
    val call: Call,
    val state: Int,
    val number: String?,
    /** The caller hid their number. */
    val hidden: Boolean,
    val caller: Caller,
    /** When it was answered (0 until then), for the call timer. */
    val connectedAt: Long,
    val canHold: Boolean,
    val canMerge: Boolean,
    val canSwap: Boolean,
    val isConference: Boolean,
    val conferenceSize: Int,
    val disconnect: DisconnectCause?,
    /** The SIM the call is on, when there's more than one. */
    val accountLabel: String?,
) {
    val ringing: Boolean get() = state == Call.STATE_RINGING
    val active: Boolean get() = state == Call.STATE_ACTIVE
    val held: Boolean get() = state == Call.STATE_HOLDING
    val outgoing: Boolean get() = state == Call.STATE_DIALING || state == Call.STATE_CONNECTING || state == Call.STATE_PULLING_CALL
    val ended: Boolean get() = state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING
    val choosingSim: Boolean get() = state == Call.STATE_SELECT_PHONE_ACCOUNT
    val title: String get() = caller.name ?: number ?: ""
}

/**
 * Every call Android hands to CallCtrl (through [CallService]), and the controls for them: answer, decline,
 * hang up, hold, mute, speaker, keypad tones, swap and merge.
 */
@SuppressLint("StaticFieldLeak")
object CallManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _calls = MutableStateFlow<List<CallInfo>>(emptyList())
    val calls: StateFlow<List<CallInfo>> = _calls

    private val _audio = MutableStateFlow<CallAudioState?>(null)
    val audio: StateFlow<CallAudioState?> = _audio

    /** The last call that ended, shown briefly as "Call ended" before the call screen closes. */
    private val _lastEnded = MutableStateFlow<CallInfo?>(null)
    val lastEnded: StateFlow<CallInfo?> = _lastEnded

    internal var service: CallService? = null
    private var appContext: Context? = null
    private val callers = HashMap<Call, Caller>()
    private var proximity: PowerManager.WakeLock? = null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = refresh()
        override fun onDetailsChanged(call: Call, details: Call.Details) = refresh()
        override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) = refresh()
        override fun onChildrenChanged(call: Call, children: MutableList<Call>) = refresh()
    }

    fun add(context: Context, call: Call) {
        appContext = context.applicationContext
        call.registerCallback(callback)
        callers[call] = Caller(call.details.contactDisplayName?.toString()?.takeIf { it.isNotBlank() }, null, null, null)
        refresh()
        // Photo and number label from the contacts, in the background.
        val number = numberOf(call)
        if (number != null) scope.launch {
            val found = withContext(Dispatchers.IO) { ContactsRepository.lookup(context.applicationContext, number) }
            val name = found.name ?: callers[call]?.name
            callers[call] = found.copy(name = name)
            refresh()
        }
    }

    fun remove(call: Call) {
        call.unregisterCallback(callback)
        val info = _calls.value.firstOrNull { it.call == call }
        callers.remove(call)
        if (info != null && _calls.value.size <= 1) {
            _lastEnded.value = info.copy(state = Call.STATE_DISCONNECTED, disconnect = call.details.disconnectCause)
            scope.launch {
                delay(1800)
                if (_calls.value.isEmpty()) _lastEnded.value = null
            }
        }
        refresh()
    }

    fun onAudio(state: CallAudioState?) {
        _audio.value = state
        updateProximity()
    }

    fun clearAll() {
        callers.clear()
        _calls.value = emptyList()
        updateProximity()
    }

    private fun refresh() {
        val svc = service
        val all = svc?.calls.orEmpty()
        // Conference children are shown as one call (the conference itself).
        val top = all.filter { it.parent == null }
        val accounts = appContext?.let { com.vknxworks.callctrl.data.Phone.callAccounts(it) }.orEmpty()
        _calls.value = top.map { c ->
            val d = c.details
            val state = d.state
            val caps = d.callCapabilities
            val number = numberOf(c)
            CallInfo(
                call = c,
                state = state,
                number = number,
                hidden = number == null && !d.hasProperty(Call.Details.PROPERTY_CONFERENCE),
                caller = callers[c] ?: Caller(d.contactDisplayName?.toString(), null, null, null),
                connectedAt = d.connectTimeMillis,
                canHold = (caps and Call.Details.CAPABILITY_HOLD) != 0,
                canMerge = ((caps and Call.Details.CAPABILITY_MERGE_CONFERENCE) != 0) || c.conferenceableCalls.isNotEmpty(),
                canSwap = (caps and Call.Details.CAPABILITY_SWAP_CONFERENCE) != 0,
                isConference = d.hasProperty(Call.Details.PROPERTY_CONFERENCE),
                conferenceSize = c.children.size,
                disconnect = if (state == Call.STATE_DISCONNECTED) d.disconnectCause else null,
                accountLabel = if (accounts.size > 1) accounts.firstOrNull { it.handle == d.accountHandle }?.label else null,
            )
        }.sortedBy { order(it.state) }
        svc?.let { CallNotifications.update(it, _calls.value) }
        updateProximity()
    }

    private fun order(state: Int) = when (state) {
        Call.STATE_RINGING -> 0
        Call.STATE_ACTIVE -> 1
        Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT -> 2
        Call.STATE_HOLDING -> 3
        else -> 4
    }

    private fun numberOf(call: Call): String? =
        call.details.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------- controls

    fun answer(call: Call) = runCatching { call.answer(VideoProfile.STATE_AUDIO_ONLY) }
    fun decline(call: Call, message: String? = null) = runCatching { call.reject(message != null, message) }
    fun hangUp(call: Call) = runCatching { if (call.details.state == Call.STATE_RINGING) call.reject(false, null) else call.disconnect() }
    fun hold(call: Call) = runCatching { call.hold() }
    fun unhold(call: Call) = runCatching { call.unhold() }
    fun dtmf(call: Call, c: Char) = runCatching { call.playDtmfTone(c) }
    fun stopDtmf(call: Call) = runCatching { call.stopDtmfTone() }
    fun simChosen(call: Call, handle: PhoneAccountHandle) = runCatching { call.phoneAccountSelected(handle, false) }

    fun setMuted(muted: Boolean) = runCatching { service?.setMuted(muted) }

    @Suppress("DEPRECATION")
    fun setRoute(route: Int) = runCatching { service?.setAudioRoute(route) }

    /** Speaker on or off (off goes back to a headset or Bluetooth when one is connected). */
    @Suppress("DEPRECATION")
    fun toggleSpeaker() {
        val a = _audio.value ?: return
        val target = if (a.route == CallAudioState.ROUTE_SPEAKER) {
            when {
                (a.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0 -> CallAudioState.ROUTE_BLUETOOTH
                (a.supportedRouteMask and CallAudioState.ROUTE_WIRED_HEADSET) != 0 -> CallAudioState.ROUTE_WIRED_HEADSET
                else -> CallAudioState.ROUTE_EARPIECE
            }
        } else CallAudioState.ROUTE_SPEAKER
        setRoute(target)
    }

    /** Swap between the active and the held call. */
    fun swap() {
        val list = _calls.value
        val held = list.firstOrNull { it.held } ?: return
        val active = list.firstOrNull { it.active }
        if (active != null && active.canSwap && active.isConference) runCatching { active.call.swapConference() }
        else unhold(held.call)
    }

    fun merge() {
        val list = _calls.value
        val active = list.firstOrNull { it.active } ?: return
        val other = active.call.conferenceableCalls.firstOrNull() ?: list.firstOrNull { it.held }?.call
        runCatching {
            if (active.call.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)) active.call.mergeConference()
            else if (other != null) active.call.conference(other)
        }
    }

    // ------------------------------------------------------------- proximity

    /** The screen turns off while the phone is at your ear (not on speaker or a headset). */
    @Suppress("DEPRECATION")
    private fun updateProximity() {
        val ctx = appContext ?: return
        val inCall = _calls.value.any { it.active || it.outgoing }
        val route = _audio.value?.route ?: CallAudioState.ROUTE_EARPIECE
        val want = inCall && route == CallAudioState.ROUTE_EARPIECE
        val lock = proximity ?: runCatching {
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (pm?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true)
                pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "CallCtrl:proximity").also { proximity = it }
            else null
        }.getOrNull() ?: return
        runCatching {
            if (want && !lock.isHeld) lock.acquire(4 * 60 * 60 * 1000L)
            else if (!want && lock.isHeld) lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        }
    }
}
