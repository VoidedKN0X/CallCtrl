package com.vknxworks.callctrl.data

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.BlockedNumberContract
import android.provider.CallLog.Calls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CallEntry(
    val id: Long,
    val number: String,
    /** The name Android saved with the call (if the number was a contact then). */
    val cachedName: String?,
    val type: Int,
    val date: Long,
    val duration: Long,
    val accountId: String?,
    val isNew: Boolean,
    /** The number was hidden by the caller. */
    val presentation: Int,
) {
    val missed: Boolean get() = type == Calls.MISSED_TYPE || type == Calls.REJECTED_TYPE
    val hidden: Boolean get() = presentation != Calls.PRESENTATION_ALLOWED || number.isBlank()
}

/** Calls in a row to and from the same number, shown as one line. */
data class CallGroup(val calls: List<CallEntry>) {
    val first: CallEntry get() = calls.first()
    val key: String get() = "${first.id}"
}

/** The call history, read from Android's call log (written by the phone itself; CallCtrl keeps no copy). */
class CallHistory(private val context: Context, private val scope: CoroutineScope) {
    private val _calls = MutableStateFlow<List<CallEntry>>(emptyList())
    val calls: StateFlow<List<CallEntry>> = _calls
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded
    private var observing = false
    private var job: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reload(300)
    }

    fun start() {
        if (!Phone.has(context, android.Manifest.permission.READ_CALL_LOG)) return
        if (!observing) {
            runCatching { context.contentResolver.registerContentObserver(Calls.CONTENT_URI, true, observer) }
            observing = true
        }
        reload(0)
    }

    private fun reload(delayMs: Long) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            val list = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() }
            if (list != null) _calls.value = list
            _loaded.value = true
        }
    }

    private fun load(): List<CallEntry> {
        val out = ArrayList<CallEntry>()
        val proj = arrayOf(
            Calls._ID, Calls.NUMBER, Calls.CACHED_NAME, Calls.TYPE, Calls.DATE, Calls.DURATION,
            Calls.PHONE_ACCOUNT_ID, Calls.NEW, Calls.NUMBER_PRESENTATION,
        )
        // The newest 1000 calls are plenty for a phone screen and keep loading instant.
        context.contentResolver.query(Calls.CONTENT_URI.buildUpon().appendQueryParameter(Calls.LIMIT_PARAM_KEY, "1000").build(), proj, null, null, "${Calls.DATE} DESC")?.use { c ->
            while (c.moveToNext()) {
                out += CallEntry(
                    id = c.getLong(0),
                    number = c.getString(1).orEmpty(),
                    cachedName = c.getString(2)?.takeIf { it.isNotBlank() },
                    type = c.getInt(3),
                    date = c.getLong(4),
                    duration = c.getLong(5),
                    accountId = c.getString(6),
                    isNew = c.getInt(7) != 0,
                    presentation = c.getInt(8),
                )
            }
        }
        return out
    }

    suspend fun delete(ids: Collection<Long>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        runCatching {
            ids.chunked(200).sumOf { chunk ->
                context.contentResolver.delete(Calls.CONTENT_URI, "${Calls._ID} IN (${chunk.joinToString(",")})", null)
            }
        }.getOrDefault(0)
    }

    suspend fun clearAll(): Boolean = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.delete(Calls.CONTENT_URI, null, null); true }.getOrDefault(false)
    }

    /** Missed calls are seen: they stop counting as new (and their notification goes away). */
    suspend fun markMissedSeen() = withContext(Dispatchers.IO) {
        runCatching {
            val v = ContentValues().apply { put(Calls.NEW, 0); put(Calls.IS_READ, 1) }
            context.contentResolver.update(Calls.CONTENT_URI, v, "${Calls.NEW}=1 AND ${Calls.TYPE}=?", arrayOf(Calls.MISSED_TYPE.toString()))
        }
        runCatching { Phone.telecom(context)?.cancelMissedCallsNotification() }
        Unit
    }

    companion object {
        /** Groups calls in a row with the same number and the same kind (missed stays apart from answered). */
        fun group(calls: List<CallEntry>): List<CallGroup> {
            val out = ArrayList<CallGroup>()
            var cur = ArrayList<CallEntry>()
            for (c in calls) {
                val last = cur.lastOrNull()
                val sameDay = last != null && java.time.Instant.ofEpochMilli(last.date).atZone(java.time.ZoneId.systemDefault()).toLocalDate() ==
                    java.time.Instant.ofEpochMilli(c.date).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                if (last != null && sameDay && last.missed == c.missed && (Phone.same(last.number, c.number) || (last.hidden && c.hidden))) {
                    cur += c
                } else {
                    if (cur.isNotEmpty()) out += CallGroup(cur)
                    cur = arrayListOf(c)
                }
            }
            if (cur.isNotEmpty()) out += CallGroup(cur)
            return out
        }
    }
}

/** Numbers that can't call you. Android keeps the list and blocks the calls and texts itself. */
object Blocked {
    fun canBlock(context: Context): Boolean = runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(context) }.getOrDefault(false)

    suspend fun list(context: Context): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val out = ArrayList<Pair<Long, String>>()
            context.contentResolver.query(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ID, BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
                null, null, null,
            )?.use { c -> while (c.moveToNext()) out += c.getLong(0) to (c.getString(1) ?: "") }
            out
        }.getOrDefault(emptyList())
    }

    suspend fun isBlocked(context: Context, number: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { BlockedNumberContract.isBlocked(context, number) }.getOrDefault(false)
    }

    suspend fun block(context: Context, number: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val v = ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }
            context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, v) != null
        }.getOrDefault(false)
    }

    suspend fun unblock(context: Context, number: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { BlockedNumberContract.unblock(context, number) > 0 }.getOrDefault(false)
    }
}
