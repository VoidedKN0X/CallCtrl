package com.vknxworks.callctrl.data.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vknxworks.callctrl.calls.CallNotifications
import com.vknxworks.callctrl.container
import com.vknxworks.callctrl.data.crypto.SealedStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Calendar

/** "Remind me to call this number back at [at]". */
@Serializable
data class Reminder(
    val id: Long,
    val number: String,
    /** The caller's name when the reminder was made (shown if the contact can't be looked up later). */
    val name: String = "",
    /** Milliseconds since 1970. */
    val at: Long,
)

/** The times offered for a call-back reminder. Pure Kotlin. */
object ReminderTimes {
    const val MAX_REMINDERS = 50

    enum class Choice { MIN_15, MIN_30, HOUR_1, HOUR_3, EVENING, TOMORROW }

    /** When [choice] is, counted from [now]; null when it isn't on offer any more (this evening, after 18:30). */
    fun at(choice: Choice, now: Long, calendar: Calendar = Calendar.getInstance()): Long? = when (choice) {
        Choice.MIN_15 -> now + 15 * MINUTE
        Choice.MIN_30 -> now + 30 * MINUTE
        Choice.HOUR_1 -> now + 60 * MINUTE
        Choice.HOUR_3 -> now + 180 * MINUTE
        Choice.EVENING -> {
            calendar.timeInMillis = now
            calendar.set(Calendar.HOUR_OF_DAY, 19)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.timeInMillis.takeIf { it - now >= 30 * MINUTE }
        }
        Choice.TOMORROW -> {
            calendar.timeInMillis = now
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            calendar.set(Calendar.HOUR_OF_DAY, 9)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.timeInMillis
        }
    }

    private const val MINUTE = 60_000L
}

/**
 * Call-back reminders: kept in one file sealed with the app's data key (`files/reminders.bin`), and handed to
 * Android's alarm clock, which wakes CallCtrl at the chosen time to show a notification. The alarms are inexact
 * on purpose (no extra permission): Android may show a reminder a few minutes late while the phone is idle.
 * Alarms don't survive a restart, so they are set again when the phone starts and when CallCtrl starts.
 */
class Reminders(private val context: Context, private val file: SealedStore, private val scope: CoroutineScope) {
    private val lock = Mutex()
    private val _items = MutableStateFlow<List<Reminder>>(emptyList())
    val items: StateFlow<List<Reminder>> = _items

    private val _unreadable = MutableStateFlow(false)
    /** The reminders file is there but can't be opened right now. It is kept; reminders can't be changed until it opens. */
    val unreadable: StateFlow<Boolean> = _unreadable

    @Volatile private var loaded = false

    /** Reads the reminders and sets their alarms (again). Safe to call more than once. */
    fun start() {
        scope.launch { load() }
    }

    suspend fun load(retryKey: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!loaded || _unreadable.value) {
                when (val content = file.read(retryKey)) {
                    is SealedStore.Content.Unreadable -> _unreadable.value = true
                    is SealedStore.Content.Text -> {
                        val list = content.text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
                        if (content.text != null && list == null) {
                            _unreadable.value = true
                        } else {
                            _items.value = list.orEmpty().sortedBy { it.at }
                            _unreadable.value = false
                        }
                    }
                }
                loaded = true
            }
            _items.value.forEach { setAlarm(it) }
        }
    }

    /** Adds a reminder and sets its alarm. Null when it couldn't be saved. */
    suspend fun add(number: String, name: String, at: Long): Reminder? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!loaded || _unreadable.value || number.isBlank()) return@withLock null
            if (_items.value.size >= ReminderTimes.MAX_REMINDERS) return@withLock null
            val id = (_items.value.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(System.currentTimeMillis() / 1000) + 1
            val item = Reminder(id, number.take(64), name.take(80), at)
            val next = (_items.value + item).sortedBy { it.at }
            if (!save(next)) return@withLock null
            _items.value = next
            setAlarm(item)
            item
        }
    }

    /** Removes a reminder (cancelled, or shown). */
    suspend fun remove(id: Long): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            cancelAlarm(id)
            if (!loaded || _unreadable.value) return@withLock false
            val next = _items.value.filterNot { it.id == id }
            if (next.size == _items.value.size) return@withLock true
            if (!save(next)) return@withLock false
            _items.value = next
            true
        }
    }

    private fun save(list: List<Reminder>): Boolean =
        if (list.isEmpty()) file.clear() else file.write(json.encodeToString(serializer, list))

    private fun alarmIntent(item: Reminder): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            // One PendingIntent per reminder: the data makes them differ.
            .setData(Uri.fromParts("callctrl-reminder", item.id.toString(), null))
            .putExtra(ReminderReceiver.EXTRA_ID, item.id)
            .putExtra(ReminderReceiver.EXTRA_NUMBER, item.number)
            .putExtra(ReminderReceiver.EXTRA_NAME, item.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun setAlarm(item: Reminder) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        // Inexact, but allowed while the phone is idle. A time that has passed fires straight away.
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, alarmIntent(item)) }
    }

    private fun cancelAlarm(id: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(alarmIntent(Reminder(id, "", "", 0L))) }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val serializer = ListSerializer(Reminder.serializer())
    }
}

/**
 * Android's alarm for a call-back reminder, and the phone starting up again (alarms are forgotten then).
 * Not exported: only Android's alarm clock (through CallCtrl's own immutable PendingIntent) and the system's
 * start-up broadcast reach it.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_FIRE -> {
                val id = intent.getLongExtra(EXTRA_ID, 0L)
                val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                if (number.isBlank()) return
                val pending = goAsync()
                Thread {
                    try {
                        CallNotifications.reminder(app, id, number, name)
                        runBlocking {
                            val reminders = app.container.reminders
                            reminders.load()
                            reminders.remove(id)
                        }
                    } catch (e: Throwable) {
                        // The notification is what matters; the list is tidied at the next start.
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pending = goAsync()
                Thread {
                    try {
                        runBlocking { app.container.reminders.load() }
                    } catch (e: Throwable) {
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.vknxworks.callctrl.REMINDER"
        const val EXTRA_ID = "id"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_NAME = "name"
    }
}
