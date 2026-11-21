package com.vknxworks.callctrl.data.notes

import com.vknxworks.callctrl.data.crypto.SealedStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Your own note and tags on one call. The call itself stays in Android's call log; this only says which call
 * ([callId] and [date], the call log's own number and time of the call) and what you wrote.
 */
@Serializable
data class CallNote(
    val callId: Long,
    val date: Long,
    val note: String = "",
    val tags: List<String> = emptyList(),
) {
    val empty: Boolean get() = note.isBlank() && tags.isEmpty()
}

/** Rules for notes and tags. Pure Kotlin. */
object CallNoteRules {
    const val MAX_NOTE = 500
    const val MAX_TAGS = 8
    const val MAX_TAG = 24

    /** The key of the call a note belongs to. The time is part of it: the call log reuses numbers of deleted calls. */
    fun key(callId: Long, date: Long): String = "$callId@$date"

    /** "work, Follow up ,#work" becomes [work, follow up]: trimmed, without #, lower case, no doubles. */
    fun parseTags(text: String): List<String> = text.split(',', ';', '\n')
        .map { it.trim().trimStart('#').trim().lowercase().take(MAX_TAG) }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_TAGS)

    fun clean(note: CallNote): CallNote = note.copy(
        note = note.note.trim().take(MAX_NOTE),
        tags = parseTags(note.tags.joinToString(",")),
    )

    /** Whether [note] has [query] in its text or as (the start of) a tag. A leading # looks at tags only. */
    fun matches(note: CallNote, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        if (q.startsWith("#")) {
            val tag = q.trimStart('#')
            return tag.isNotEmpty() && note.tags.any { it.startsWith(tag) }
        }
        return note.note.lowercase().contains(q) || note.tags.any { it.startsWith(q) }
    }
}

/**
 * Notes and tags on calls, kept in one file sealed with the app's data key (`files/call_notes.bin`), like
 * finished recordings. Read once, in the background; nothing on the call or screening path touches it.
 */
class CallNotesStore(private val file: SealedStore, private val scope: CoroutineScope) {
    private val lock = Mutex()
    private val _notes = MutableStateFlow<Map<String, CallNote>>(emptyMap())
    /** Notes by [CallNoteRules.key]. */
    val notes: StateFlow<Map<String, CallNote>> = _notes

    private val _unreadable = MutableStateFlow(false)
    /** The notes file is there but can't be opened right now. It is kept; notes can't be changed until it opens. */
    val unreadable: StateFlow<Boolean> = _unreadable

    @Volatile private var loaded = false

    fun start() {
        scope.launch { load() }
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (loaded && !_unreadable.value) return@withLock
            when (val content = file.read()) {
                is SealedStore.Content.Unreadable -> _unreadable.value = true
                is SealedStore.Content.Text -> {
                    val list = content.text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
                    if (content.text != null && list == null) {
                        // Opened, but not what this version wrote: leave it alone.
                        _unreadable.value = true
                    } else {
                        _notes.value = list.orEmpty().associateBy { CallNoteRules.key(it.callId, it.date) }
                        _unreadable.value = false
                    }
                }
            }
            loaded = true
        }
    }

    /** Tries the notes file again after it couldn't be opened. */
    suspend fun retry() = load()

    /** Saves [note] (an empty one removes it). False when it couldn't be saved. */
    suspend fun put(note: CallNote): Boolean = change { map ->
        val clean = CallNoteRules.clean(note)
        val key = CallNoteRules.key(clean.callId, clean.date)
        if (clean.empty) map - key else map + (key to clean)
    }

    /** The calls are deleted: so are their notes. */
    suspend fun forget(keys: Collection<String>): Boolean = if (keys.isEmpty()) true else change { map -> map - keys.toSet() }

    /** The whole call history was cleared. */
    suspend fun forgetAll(): Boolean = change { emptyMap() }

    private suspend fun change(transform: (Map<String, CallNote>) -> Map<String, CallNote>): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!loaded || _unreadable.value) return@withLock false
            val next = transform(_notes.value)
            if (next == _notes.value) return@withLock true
            val ok = if (next.isEmpty()) file.clear() else file.write(json.encodeToString(serializer, next.values.toList()))
            if (ok) _notes.value = next
            ok
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val serializer = ListSerializer(CallNote.serializer())
    }
}
