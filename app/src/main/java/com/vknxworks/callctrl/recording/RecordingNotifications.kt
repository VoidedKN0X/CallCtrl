package com.vknxworks.callctrl.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vknxworks.callctrl.MainActivity
import com.vknxworks.callctrl.R

/** A notification when a call couldn't be recorded, or a recording stopped early. Nothing while all goes well. */
object RecordingNotifications {
    private const val CHANNEL = "call_recording"
    private const val ID = 30

    fun problem(context: Context, problem: RecordProblem) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.ch_recording), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.ch_recording_desc)
                setShowBadge(false)
            },
        )
        val (title, text) = when (problem) {
            RecordProblem.SHIZUKU_NOT_READY -> R.string.rec_problem_not_ready to R.string.rec_problem_not_ready_text
            RecordProblem.SHIZUKU_LOST -> R.string.rec_problem_lost to R.string.rec_problem_lost_text
            RecordProblem.REFUSED -> R.string.rec_problem_refused to R.string.rec_problem_refused_text
            RecordProblem.STORAGE_FULL -> R.string.rec_problem_storage to R.string.rec_problem_storage_text
            RecordProblem.ERROR -> R.string.rec_problem_error to R.string.rec_problem_error_text
        }
        val open = PendingIntent.getActivity(
            context, 30,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_RECORDINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setStyle(Notification.BigTextStyle().bigText(context.getString(text)))
            .setCategory(Notification.CATEGORY_ERROR)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        runCatching { nm.notify(ID, n) }
    }
}
