package com.vknxworks.callctrl.calls

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService

/**
 * Android binds this service while there are calls, as long as CallCtrl is the default phone app. Android itself
 * keeps ringing (with the caller's own ringtone); CallCtrl shows the call screen and notification.
 */
class CallService : InCallService() {

    override fun onCreate() {
        super.onCreate()
        CallManager.service = this
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.service = this
        CallManager.add(this, call)
        // Calls you make open the call screen right away. Incoming calls ring with a full-screen notification,
        // which Android shows as the call screen when the phone is locked and as a banner when you're using it.
        if (call.details.state != Call.STATE_RINGING) openCallScreen()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        CallManager.remove(call)
    }

    @Deprecated("Still delivered on every Android version CallCtrl supports")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        @Suppress("DEPRECATION")
        super.onCallAudioStateChanged(audioState)
        CallManager.onAudio(audioState)
    }

    override fun onDestroy() {
        CallNotifications.cancelAll(this)
        if (CallManager.service === this) CallManager.service = null
        CallManager.clearAll()
        super.onDestroy()
    }

    fun openCallScreen() {
        runCatching {
            startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        }
    }
}
