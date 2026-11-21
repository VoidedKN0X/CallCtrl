package com.vknxworks.callctrl.calls

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.widget.Toast
import com.vknxworks.callctrl.R
import com.vknxworks.callctrl.container
import kotlinx.coroutines.runBlocking
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
                context.getSystemService(NotificationManager::class.java)?.cancel(intent.getIntExtra(EXTRA_NOTIFICATION, CallNotifications.ID_MISSED))
                intent.getStringExtra(EXTRA_NUMBER)?.let { Phone.call(context, it) }
            }
            // "Remind me in an hour" on the missed-call notification.
            REMIND -> {
                val number = intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() } ?: return
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                val app = context.applicationContext
                val pending = goAsync()
                Thread {
                    val saved = try {
                        runBlocking {
                            val reminders = app.container.reminders
                            reminders.load()
                            reminders.add(number, name, System.currentTimeMillis() + 60 * 60_000L) != null
                        }
                    } catch (e: Throwable) {
                        false
                    }
                    if (saved) app.getSystemService(NotificationManager::class.java)?.cancel(CallNotifications.ID_MISSED)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(app, app.getString(if (saved) R.string.reminder_set_hour else R.string.reminder_failed), Toast.LENGTH_SHORT).show()
                    }
                    pending.finish()
                }.start()
            }
        }
    }

    companion object {
        const val ANSWER = "com.vknxworks.callctrl.ANSWER"
        const val DECLINE = "com.vknxworks.callctrl.DECLINE"
        const val HANG_UP = "com.vknxworks.callctrl.HANG_UP"
        const val CALL_BACK = "com.vknxworks.callctrl.CALL_BACK"
        const val REMIND = "com.vknxworks.callctrl.REMIND"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_NAME = "name"
        /** The notification to take away once its "Call back" was tapped. */
        const val EXTRA_NOTIFICATION = "notification"
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
