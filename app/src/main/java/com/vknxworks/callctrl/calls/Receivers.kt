package com.vknxworks.callctrl.calls

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import com.vknxworks.callctrl.data.Phone

/** The buttons on the call and missed-call notifications. Not exported: only CallCtrl's notifications reach it. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val calls = CallManager.calls.value
        when (intent.action) {
            ANSWER -> calls.firstOrNull { it.ringing }?.let {
                CallManager.answer(it.call)
                CallManager.service?.openCallScreen()
            }
            DECLINE -> calls.firstOrNull { it.ringing }?.let { CallManager.decline(it.call) }
            HANG_UP -> (calls.firstOrNull { it.active || it.outgoing } ?: calls.firstOrNull { !it.ended })?.let { CallManager.hangUp(it.call) }
            CALL_BACK -> {
                context.getSystemService(NotificationManager::class.java)?.cancel(CallNotifications.ID_MISSED)
                intent.getStringExtra(EXTRA_NUMBER)?.let { Phone.call(context, it) }
            }
        }
    }

    companion object {
        const val ANSWER = "com.vknxworks.callctrl.ANSWER"
        const val DECLINE = "com.vknxworks.callctrl.DECLINE"
        const val HANG_UP = "com.vknxworks.callctrl.HANG_UP"
        const val CALL_BACK = "com.vknxworks.callctrl.CALL_BACK"
        const val EXTRA_NUMBER = "number"
    }
}

/**
 * As the default phone app, Android sends CallCtrl the missed-call count instead of showing its own notification.
 * Only the phone's own telephony service may send it (the receiver needs a system-only permission).
 */
class MissedCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return
        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0)
        val number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER)
        val pending = goAsync()
        Thread {
            try {
                CallNotifications.missed(context, count, number)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
