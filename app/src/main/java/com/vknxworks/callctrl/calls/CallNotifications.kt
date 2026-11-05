package com.vknxworks.callctrl.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import com.vknxworks.callctrl.MainActivity
import com.vknxworks.callctrl.R
import com.vknxworks.callctrl.data.Phone

/**
 * The call notifications: a full-screen one for an incoming call (the call screen on the lock screen, a banner
 * otherwise), an ongoing one with hang up, and missed calls.
 */
object CallNotifications {
    const val CH_INCOMING = "incoming_calls"
    const val CH_ONGOING = "ongoing_calls"
    const val CH_MISSED = "missed_calls"
    private const val ID_CALL = 1
    const val ID_MISSED = 2
    private val photos = android.util.LruCache<String, Bitmap>(8)

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_INCOMING, context.getString(R.string.ch_incoming), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.ch_incoming_desc)
            // Android plays the ringtone itself (with each contact's own ringtone), so the notification stays quiet.
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, context.getString(R.string.ch_ongoing), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.ch_ongoing_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_MISSED, context.getString(R.string.ch_missed), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.ch_missed_desc)
        })
    }

    private fun callScreen(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(context: Context, action: String, req: Int): PendingIntent = PendingIntent.getBroadcast(
        context, req,
        Intent(context, CallActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun person(context: Context, info: CallInfo): Person {
        val photo: Bitmap? = info.caller.photoUri?.let { uri ->
            photos.get(uri) ?: runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) } }.getOrNull()?.also { photos.put(uri, it) }
        }
        val name = info.caller.name ?: info.number?.let { Phone.format(context, it) } ?: context.getString(R.string.unknown_caller)
        return Person.Builder().setName(name).setImportant(true).apply { if (photo != null) setIcon(Icon.createWithBitmap(photo)) }.build()
    }

    /** Shows the notification for the most important call: ringing first, then the ongoing one. */
    fun update(context: Context, calls: List<CallInfo>) {
        channels(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val main = calls.firstOrNull { it.ringing } ?: calls.firstOrNull { !it.ended }
        if (main == null) {
            nm.cancel(ID_CALL)
            return
        }
        val who = person(context, main)
        val n = if (main.ringing) {
            Notification.Builder(context, CH_INCOMING)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setStyle(Notification.CallStyle.forIncomingCall(who, action(context, CallActionReceiver.DECLINE, 1), action(context, CallActionReceiver.ANSWER, 2)))
                .setContentText(main.accountLabel ?: context.getString(R.string.state_incoming))
                .setCategory(Notification.CATEGORY_CALL)
                .setFullScreenIntent(callScreen(context), true)
                .setContentIntent(callScreen(context))
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        } else {
            val text = when {
                main.held -> context.getString(R.string.state_held)
                main.outgoing -> context.getString(R.string.state_calling)
                main.choosingSim -> context.getString(R.string.state_choose_sim)
                else -> context.getString(R.string.state_ongoing)
            }
            Notification.Builder(context, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setStyle(Notification.CallStyle.forOngoingCall(who, action(context, CallActionReceiver.HANG_UP, 3)))
                .setContentText(text)
                .setCategory(Notification.CATEGORY_CALL)
                .setUsesChronometer(main.active && main.connectedAt > 0)
                .apply { if (main.connectedAt > 0) setWhen(main.connectedAt) }
                .setShowWhen(main.active)
                .setFullScreenIntent(callScreen(context), false)
                .setContentIntent(callScreen(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        }
        runCatching { nm.notify(ID_CALL, n) }
    }

    fun cancelAll(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_CALL)
    }

    /** "Missed call from Anna" (or "3 missed calls"), with call back and message. */
    fun missed(context: Context, count: Int, number: String?) {
        channels(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (count <= 0) {
            nm.cancel(ID_MISSED)
            return
        }
        val caller = number?.takeIf { it.isNotBlank() }?.let { com.vknxworks.callctrl.data.ContactsRepository.lookup(context, it) }
        val who = caller?.name ?: number?.takeIf { it.isNotBlank() }?.let { Phone.format(context, it) } ?: context.getString(R.string.unknown_caller)
        val open = PendingIntent.getActivity(
            context, 10,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_RECENTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(context, CH_MISSED)
            .setSmallIcon(R.drawable.ic_stat_missed)
            .setContentTitle(if (count == 1) context.getString(R.string.missed_one) else context.resources.getQuantityString(R.plurals.missed_many, count, count))
            .setContentText(if (count == 1) who else context.getString(R.string.missed_last, who))
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            // The caller's name only shows on the lock screen when the phone allows private notification content.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                Notification.Builder(context, CH_MISSED).setSmallIcon(R.drawable.ic_stat_missed)
                    .setContentTitle(context.resources.getQuantityString(R.plurals.missed_many, count, count)).build(),
            )
        if (count == 1 && !number.isNullOrBlank()) {
            val callBack = PendingIntent.getBroadcast(
                context, 11,
                Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.CALL_BACK).putExtra(CallActionReceiver.EXTRA_NUMBER, number),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val message = PendingIntent.getActivity(
                context, 12,
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_call), context.getString(R.string.call_back), callBack).build())
            b.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_message), context.getString(R.string.message), message).build())
        }
        runCatching { nm.notify(ID_MISSED, b.build()) }
    }
}
