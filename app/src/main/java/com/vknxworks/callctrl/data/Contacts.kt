package com.vknxworks.callctrl.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone as CPhone
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

data class PhoneEntry(val number: String, val type: Int, val label: String?, val primary: Boolean)

/** A contact with at least one phone number. */
data class Person(
    val id: Long,
    val lookupKey: String,
    val name: String,
    val photoUri: String?,
    val starred: Boolean,
    val phones: List<PhoneEntry>,
) {
    val lookupUri: Uri get() = ContactsContract.Contacts.getLookupUri(id, lookupKey)
    val searchName: String = fold(name)
    /** The name on a phone keypad: "Anna" is 2662, so typing 266 finds her. */
    val t9: List<String> = searchName.split(' ', '-', '.').filter { it.isNotEmpty() }.map { w -> w.map(::t9Digit).joinToString("") }
    val digits: List<String> = phones.map { p -> p.number.filter { it.isDigit() } }

    fun matches(query: String): Boolean {
        val q = fold(query.trim())
        if (q.isEmpty()) return true
        if (searchName.contains(q)) return true
        val qd = query.filter { it.isDigit() }
        return qd.length >= 2 && digits.any { it.contains(qd) }
    }

    /** Keypad search: the typed digits start a word of the name (T9), or appear in a number. */
    fun matchesDigits(typed: String): Boolean {
        val d = typed.filter { it.isDigit() }
        if (d.isEmpty()) return false
        val joined = t9.joinToString("")
        return t9.any { it.startsWith(d) } || joined.startsWith(d) || (d.length >= 3 && digits.any { it.contains(d) })
    }

    companion object {
        fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

        private fun t9Digit(c: Char): Char = when (c) {
            in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'; in 'j'..'l' -> '5'
            in 'm'..'o' -> '6'; in 'p'..'s' -> '7'; in 't'..'v' -> '8'; in 'w'..'z' -> '9'
            else -> if (c.isDigit()) c else '1'
        }
    }
}

/** Who a number belongs to, for the call screen, notifications and call history. */
data class Caller(val name: String?, val photoUri: String?, val lookupUri: Uri?, val label: String?)

/**
 * The phone's contacts, read from Android's contacts storage (CallCtrl keeps no copy). Reloaded whenever the
 * contacts change.
 */
class ContactsRepository(private val context: Context, private val scope: CoroutineScope) {
    private val _people = MutableStateFlow<List<Person>>(emptyList())
    val people: StateFlow<List<Person>> = _people
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    private val cache = LruCache<String, Caller>(200)
    private var observing = false
    private var job: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            cache.evictAll()
            reload(500)
        }
    }

    fun start() {
        if (!Phone.has(context, android.Manifest.permission.READ_CONTACTS)) return
        if (!observing) {
            runCatching { context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer) }
            observing = true
        }
        reload(0)
    }

    private fun reload(delayMs: Long) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            val list = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() }
            if (list != null) _people.value = list
            _loaded.value = true
        }
    }

    private fun load(): List<Person> {
        val map = LinkedHashMap<Long, Person>()
        val proj = arrayOf(
            CPhone.CONTACT_ID, CPhone.LOOKUP_KEY, CPhone.DISPLAY_NAME_PRIMARY, CPhone.PHOTO_THUMBNAIL_URI,
            CPhone.STARRED, CPhone.NUMBER, CPhone.TYPE, CPhone.LABEL, CPhone.IS_SUPER_PRIMARY,
        )
        context.contentResolver.query(CPhone.CONTENT_URI, proj, null, null, "${CPhone.SORT_KEY_PRIMARY}, ${CPhone.IS_SUPER_PRIMARY} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val number = c.getString(5) ?: continue
                val entry = PhoneEntry(number, c.getInt(6), c.getString(7), c.getInt(8) != 0)
                val existing = map[id]
                if (existing == null) {
                    val name = c.getString(2) ?: number
                    map[id] = Person(id, c.getString(1) ?: continue, name, c.getString(3), c.getInt(4) != 0, listOf(entry))
                } else if (existing.phones.none { Phone.same(it.number, number) }) {
                    map[id] = existing.copy(phones = existing.phones + entry)
                }
            }
        }
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
        return map.values.sortedWith { a, b ->
            val la = a.name.firstOrNull()?.isLetter() == true
            val lb = b.name.firstOrNull()?.isLetter() == true
            if (la != lb) (if (la) -1 else 1) else collator.compare(a.name, b.name)
        }
    }

    /** Looks a number up in the contacts (cached). Null name when it isn't saved. */
    suspend fun caller(number: String?): Caller {
        if (number.isNullOrBlank()) return Caller(null, null, null, null)
        cache.get(number)?.let { return it }
        if (!Phone.has(context, android.Manifest.permission.READ_CONTACTS)) return Caller(null, null, null, null)
        val c = withContext(Dispatchers.IO) { lookup(context, number) }
        cache.put(number, c)
        return c
    }

    companion object {
        fun lookup(context: Context, number: String): Caller = runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val proj = arrayOf(
                ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY, ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI, ContactsContract.PhoneLookup.TYPE, ContactsContract.PhoneLookup.LABEL,
            )
            context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val label = CPhone.getTypeLabel(context.resources, c.getInt(4), c.getString(5)).toString()
                Caller(
                    c.getString(2),
                    c.getString(3),
                    ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1) ?: ""),
                    label,
                )
            }
        }.getOrNull() ?: Caller(null, null, null, null)

        /** A contact's own page in your contacts app. */
        fun contactUri(id: Long): Uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
    }
}
