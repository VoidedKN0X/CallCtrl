package com.vknxworks.callctrl.ui.recents

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.provider.CallLog.Calls
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallMissed
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Voicemail
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.callctrl.R
import com.vknxworks.callctrl.calls.formatDuration
import com.vknxworks.callctrl.container
import com.vknxworks.callctrl.data.Blocked
import com.vknxworks.callctrl.data.CallAccount
import com.vknxworks.callctrl.data.CallEntry
import com.vknxworks.callctrl.data.CallGroup
import com.vknxworks.callctrl.data.CallHistory
import com.vknxworks.callctrl.data.Caller
import com.vknxworks.callctrl.data.Phone
import com.vknxworks.callctrl.recording.Recording
import com.vknxworks.callctrl.recording.RecordingMatch
import com.vknxworks.callctrl.ui.recordings.RecordingSheet
import com.vknxworks.callctrl.ui.recordings.recordingDetails
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonChecked
import com.vknxworks.callctrl.ui.common.ContactAvatar
import com.vknxworks.callctrl.ui.components.ConfirmDialog
import com.vknxworks.callctrl.ui.components.CtrlBottomSheet
import com.vknxworks.callctrl.ui.components.CtrlSquareButton
import com.vknxworks.callctrl.ui.components.GroupRow
import com.vknxworks.callctrl.ui.components.MessageView
import com.vknxworks.callctrl.ui.components.TabTitle
import com.vknxworks.callctrl.ui.components.bottomClearance
import com.vknxworks.callctrl.ui.dialer.SimSheet
import com.vknxworks.callctrl.ui.dialer.addToContacts
import com.vknxworks.callctrl.ui.dialer.openContact
import com.vknxworks.callctrl.ui.dialer.sendMessage
import com.vknxworks.callctrl.ui.dialer.startCall
import com.vknxworks.callctrl.ui.theme.CtrlColors
import com.vknxworks.callctrl.ui.theme.CtrlShapes
import com.vknxworks.callctrl.ui.theme.CtrlType
import com.vknxworks.callctrl.ui.theme.Gutter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.TextButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.vknxworks.callctrl.data.Person
import com.vknxworks.callctrl.data.notes.CallNote
import com.vknxworks.callctrl.data.notes.CallNoteRules
import com.vknxworks.callctrl.data.reminders.ReminderTimes
import com.vknxworks.callctrl.ui.components.CtrlAlertDialog
import com.vknxworks.callctrl.ui.components.CtrlTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun RecentsScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val calls by c.history.calls.collectAsStateWithLifecycle()
    val loaded by c.history.loaded.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var missedOnly by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val notes by c.notes.notes.collectAsStateWithLifecycle()
    val people by c.contacts.people.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<CallGroup?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var sims by remember { mutableStateOf<Pair<String, List<CallAccount>>?>(null) }

    // Opening Recents counts as seeing the missed calls.
    LaunchedEffect(loaded) { if (loaded) c.history.markMissedSeen() }

    // Search: worked out off the main thread (it walks the contacts), so typing never waits for it.
    val found by produceState<List<CallEntry>?>(null, calls, query, searching, people, notes) {
        val q = query.trim()
        value = if (!searching || q.isEmpty()) null else withContext(Dispatchers.Default) { searchCalls(calls, q, people, notes) }
    }
    val shown = found ?: calls
    val groups = remember(shown, missedOnly, settings.groupRecents) {
        val list = if (missedOnly) shown.filter { it.missed } else shown
        if (settings.groupRecents) CallHistory.group(list) else list.map { CallGroup(listOf(it)) }
    }
    BackHandler(enabled = searching) { searching = false; query = "" }

    // Call recordings, shown on their call.
    val recordingList by c.recordings.list.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { c.recordings.reload() }
    val recorded = remember(groups, recordingList) {
        if (recordingList.isEmpty()) emptyMap()
        else groups.associate { g ->
            g.key to recordingList.filter { r ->
                g.calls.any { e -> RecordingMatch.belongs(r.meta.number, r.meta.startedAt, e.number, e.hidden, e.date, e.duration) { a, b -> Phone.same(a, b) } }
            }.sortedBy { it.meta.startedAt }
        }.filterValues { it.isNotEmpty() }
    }
    var playing by remember { mutableStateOf<Recording?>(null) }

    fun call(number: String) {
        startCall(context, number) { sims = number to it }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabTitle(
            stringResource(R.string.tab_recents),
            trailing = {
                if (calls.isNotEmpty()) {
                    CtrlSquareButton(
                        if (searching) Icons.Outlined.SearchOff else Icons.Outlined.Search,
                        stringResource(if (searching) R.string.search_close else R.string.search_history),
                        onClick = { searching = !searching; query = "" },
                    )
                    CtrlSquareButton(Icons.Outlined.DeleteSweep, stringResource(R.string.clear_history), onClick = { confirmClear = true })
                }
            },
        )
        if (searching) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            CtrlTextField(
                value = query,
                onValueChange = { query = it.take(60) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp).focusRequester(focus),
                placeholder = { Text(stringResource(R.string.search_history_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = CtrlColors.Muted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
        Row(Modifier.padding(horizontal = Gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(stringResource(R.string.filter_all), !missedOnly) { missedOnly = false }
            FilterChip(stringResource(R.string.filter_missed), missedOnly) { missedOnly = true }
        }
        when {
            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = CtrlColors.AccentText) }
            groups.isEmpty() -> MessageView(
                if (found != null) Icons.Outlined.SearchOff else Icons.Outlined.History,
                stringResource(if (found != null) R.string.search_history_none else if (missedOnly) R.string.no_missed else R.string.no_calls),
                if (found != null) stringResource(R.string.search_history_none_body) else null,
                modifier = Modifier.bottomClearance(),
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(groups, key = { it.key }) { g ->
                    CallRow(
                        g,
                        recorded = recorded.containsKey(g.key),
                        noted = g.calls.any { notes.containsKey(CallNoteRules.key(it.id, it.date)) },
                        onOpen = { open = g },
                        onCall = { call(g.first.number) },
                    )
                }
                item { Spacer(Modifier.bottomClearance(0.dp)) }
            }
        }
    }

    open?.let { g ->
        CallDetails(
            g,
            recordings = recorded[g.key].orEmpty(),
            onPlay = { r -> open = null; playing = r },
            onDismiss = { open = null },
            onCall = { open = null; call(g.first.number) },
            onDelete = {
                open = null
                scope.launch {
                    c.history.delete(g.calls.map { it.id })
                    c.notes.forget(g.calls.map { CallNoteRules.key(it.id, it.date) })
                }
            },
        )
    }
    playing?.let { r -> RecordingSheet(r, onDismiss = { playing = null }) }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.clear_history_title),
            body = stringResource(R.string.clear_history_body),
            confirm = stringResource(R.string.clear_history),
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                scope.launch { if (c.history.clearAll()) c.notes.forgetAll() }
            },
        )
    }
    sims?.let { (number, list) ->
        SimSheet(list, onDismiss = { sims = null }) { a ->
            sims = null
            if (!Phone.call(context, number, a.handle)) Toast.makeText(context, context.getString(R.string.call_failed), Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(CtrlShapes.Chip)
            .background(if (selected) CtrlColors.Accent else CtrlColors.Surface)
            .border(1.dp, if (selected) CtrlColors.Accent else CtrlColors.ControlOutline, CtrlShapes.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) CtrlColors.OnAccent else CtrlColors.Text) }
}

/** The caller's contact (if saved), looked up once per number. */
@Composable
fun rememberCaller(number: String): Caller {
    val c = LocalContext.current.container
    val people by c.contacts.people.collectAsStateWithLifecycle()
    val caller by produceState(Caller(null, null, null, null), number, people) { value = c.contacts.caller(number) }
    return caller
}

/**
 * The calls that match [query]: by number (digits), by the name saved with the call, by a contact's name
 * (also typed on the keypad letters), or by your own note or tag on the call ("#work" looks at tags only).
 */
private fun searchCalls(calls: List<CallEntry>, query: String, people: List<Person>, notes: Map<String, CallNote>): List<CallEntry> {
    val digits = query.filter { it.isDigit() }
    val byDigits = digits.length >= 2 && query.all { it.isDigit() || it in " +-()./" }
    val folded = Person.fold(query)
    // The last nine digits of every number of every contact whose name matches.
    val named = HashSet<String>()
    if (!query.startsWith("#")) {
        for (p in people) if (p.matches(query)) for (d in p.digits) if (d.isNotEmpty()) named += d.takeLast(9)
    }
    return calls.filter { e ->
        val own = e.number.filter { it.isDigit() }
        (byDigits && own.contains(digits)) ||
            (!query.startsWith("#") && e.cachedName != null && Person.fold(e.cachedName).contains(folded)) ||
            (own.isNotEmpty() && own.takeLast(9) in named) ||
            (notes[CallNoteRules.key(e.id, e.date)]?.let { CallNoteRules.matches(it, query) } == true)
    }
}

@Composable
private fun CallRow(g: CallGroup, recorded: Boolean, noted: Boolean, onOpen: () -> Unit, onCall: () -> Unit) {
    val context = LocalContext.current
    val e = g.first
    val caller = if (e.hidden) Caller(null, null, null, null) else rememberCaller(e.number)
    val title = when {
        e.hidden -> stringResource(R.string.private_number)
        else -> caller.name ?: e.cachedName ?: Phone.format(context, e.number)
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).heightIn(min = 68.dp).padding(horizontal = Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(title, caller.photoUri)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title + if (g.calls.size > 1) "  (${g.calls.size})" else "",
                style = MaterialTheme.typography.bodyLarge,
                color = if (e.missed) CtrlColors.Error else CtrlColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(typeIcon(e), null, Modifier.size(14.dp), tint = if (e.missed) CtrlColors.Error else CtrlColors.Muted)
                Spacer(Modifier.width(6.dp))
                if (recorded) {
                    Icon(Icons.Outlined.RadioButtonChecked, stringResource(R.string.recordings), Modifier.size(14.dp), tint = CtrlColors.Muted)
                    Spacer(Modifier.width(6.dp))
                }
                if (noted) {
                    Icon(Icons.AutoMirrored.Outlined.Notes, stringResource(R.string.note_has), Modifier.size(14.dp), tint = CtrlColors.Muted)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    listOfNotNull(caller.label.takeIf { caller.name != null }, whenText(context, e.date)).joinToString(" · "),
                    style = CtrlType.Data, color = CtrlColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!e.hidden) CtrlSquareButton(Icons.Outlined.Call, stringResource(R.string.action_call), onClick = onCall, tint = CtrlColors.AccentText)
    }
}

fun typeIcon(e: CallEntry): ImageVector = when (e.type) {
    Calls.INCOMING_TYPE, Calls.ANSWERED_EXTERNALLY_TYPE -> Icons.AutoMirrored.Outlined.CallReceived
    Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Outlined.CallMade
    Calls.VOICEMAIL_TYPE -> Icons.Outlined.Voicemail
    Calls.BLOCKED_TYPE -> Icons.Outlined.Block
    else -> Icons.AutoMirrored.Outlined.CallMissed
}

private fun typeName(context: Context, e: CallEntry): String = context.getString(
    when (e.type) {
        Calls.INCOMING_TYPE, Calls.ANSWERED_EXTERNALLY_TYPE -> R.string.type_incoming
        Calls.OUTGOING_TYPE -> R.string.type_outgoing
        Calls.VOICEMAIL_TYPE -> R.string.type_voicemail
        Calls.BLOCKED_TYPE -> R.string.type_blocked
        Calls.REJECTED_TYPE -> R.string.type_declined
        else -> R.string.type_missed
    },
)

/** "14:05", "Yesterday", "Mon", or a date. */
fun whenText(context: Context, millis: Long): String {
    val now = System.currentTimeMillis()
    return when {
        DateUtils.isToday(millis) -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
        DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.yesterday)
        now - millis < 6 * DateUtils.DAY_IN_MILLIS -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
        else -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }
}

@Composable
private fun CallDetails(g: CallGroup, recordings: List<Recording>, onPlay: (Recording) -> Unit, onDismiss: () -> Unit, onCall: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val e = g.first
    val caller = if (e.hidden) Caller(null, null, null, null) else rememberCaller(e.number)
    var blocked by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(e.number) { if (!e.hidden && Blocked.canBlock(context)) blocked = Blocked.isBlocked(context, e.number) }
    val c = context.container
    val notes by c.notes.notes.collectAsStateWithLifecycle()
    val notesUnreadable by c.notes.unreadable.collectAsStateWithLifecycle()
    var noteFor by remember { mutableStateOf<CallEntry?>(null) }
    var reminding by remember { mutableStateOf(false) }

    CtrlBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val title = if (e.hidden) stringResource(R.string.private_number) else caller.name ?: e.cachedName ?: Phone.format(context, e.number)
                    ContactAvatar(title, caller.photoUri, size = 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!e.hidden && (caller.name != null || e.cachedName != null)) {
                            Text(listOfNotNull(caller.label, Phone.format(context, e.number)).joinToString(" · "), style = CtrlType.Data, color = CtrlColors.Muted)
                        }
                    }
                }
            }
            if (!e.hidden) {
                item { GroupRow(stringResource(R.string.action_call), icon = Icons.Outlined.Call, iconTint = CtrlColors.AccentText, onClick = onCall) }
                item { GroupRow(stringResource(R.string.message), icon = Icons.Outlined.ChatBubbleOutline, onClick = { onDismiss(); sendMessage(context, e.number) }) }
                item {
                    val uri = caller.lookupUri
                    if (uri != null) GroupRow(stringResource(R.string.open_contact), icon = Icons.Outlined.Person, onClick = { onDismiss(); openContact(context, uri) })
                    else GroupRow(stringResource(R.string.add_to_contacts), icon = Icons.Outlined.PersonAdd, onClick = { onDismiss(); addToContacts(context, e.number) })
                }
                item { GroupRow(stringResource(R.string.copy_number), icon = Icons.Outlined.ContentCopy, onClick = { copy(context, e.number); onDismiss() }) }
                item { GroupRow(stringResource(R.string.remind_call_back), stringResource(R.string.remind_call_back_sub), icon = Icons.Outlined.Alarm, onClick = { reminding = true }) }
                blocked?.let { isBlocked ->
                    item {
                        GroupRow(
                            stringResource(if (isBlocked) R.string.unblock else R.string.block),
                            stringResource(if (isBlocked) R.string.unblock_sub else R.string.block_sub),
                            icon = Icons.Outlined.Block,
                            iconTint = if (isBlocked) CtrlColors.Text else CtrlColors.Error,
                            titleColor = if (isBlocked) CtrlColors.Text else CtrlColors.Error,
                            onClick = {
                                scope.launch {
                                    val ok = if (isBlocked) Blocked.unblock(context, e.number) else Blocked.block(context, e.number)
                                    if (ok) blocked = !isBlocked
                                    else Toast.makeText(context, context.getString(R.string.block_failed), Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }
            }
            item { GroupRow(pluralStringResource(R.plurals.delete_calls, g.calls.size, g.calls.size), icon = Icons.Outlined.Delete, iconTint = CtrlColors.Error, titleColor = CtrlColors.Error, onClick = onDelete) }
            if (recordings.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.recordings).uppercase(),
                        style = CtrlType.Label, color = CtrlColors.Muted,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
                    )
                }
                items(recordings, key = { "rec-" + it.key }) { r ->
                    GroupRow(
                        DateUtils.formatDateTime(context, r.meta.startedAt, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL) +
                            if (r.meta.part > 1) " · " + stringResource(R.string.rec_part, r.meta.part) else "",
                        recordingDetails(context, r).firstOrNull(),
                        icon = Icons.Outlined.PlayArrow,
                        iconTint = CtrlColors.AccentText,
                        value = if (r.meta.durationMs > 0) formatDuration(r.meta.durationMs / 1000) else null,
                        onClick = { onPlay(r) },
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.call_list).uppercase(),
                    style = CtrlType.Label, color = CtrlColors.Muted,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
                )
            }
            item {
                Text(
                    stringResource(if (notesUnreadable) R.string.note_unreadable else R.string.note_hint),
                    style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (notesUnreadable) Modifier.clickable { scope.launch { c.notes.retry() } } else Modifier)
                        .padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
                )
            }
            items(g.calls, key = { it.id }) { call ->
                val note = notes[CallNoteRules.key(call.id, call.date)]
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !notesUnreadable, onClickLabel = stringResource(R.string.note_edit)) { noteFor = call }.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(typeIcon(call), null, Modifier.size(18.dp), tint = if (call.missed) CtrlColors.Error else CtrlColors.Muted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(typeName(context, call), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            DateUtils.formatDateTime(context, call.date, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL),
                            style = CtrlType.Data, color = CtrlColors.Muted,
                        )
                        if (note != null) {
                            if (note.note.isNotBlank()) Text(note.note, style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Text, modifier = Modifier.padding(top = 4.dp))
                            if (note.tags.isNotEmpty()) Text(note.tags.joinToString("  ") { "#$it" }, style = CtrlType.Data, color = CtrlColors.AccentText, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    if (call.duration > 0) Text(formatDuration(call.duration), style = CtrlType.Data, color = CtrlColors.Muted)
                }
            }
        }
    }

    noteFor?.let { call ->
        NoteDialog(notes[CallNoteRules.key(call.id, call.date)], onDismiss = { noteFor = null }) { text, tags ->
            noteFor = null
            scope.launch {
                val ok = c.notes.put(CallNote(call.id, call.date, text, tags))
                if (!ok) Toast.makeText(context, context.getString(R.string.note_save_failed), Toast.LENGTH_LONG).show()
            }
        }
    }
    if (reminding) {
        val name = caller.name ?: e.cachedName.orEmpty()
        ReminderSheet(onDismiss = { reminding = false }) { at ->
            reminding = false
            scope.launch {
                val saved = c.reminders.add(e.number, name, at)
                Toast.makeText(
                    context,
                    if (saved != null) context.getString(R.string.reminder_set, DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME))
                    else context.getString(R.string.reminder_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}

/** Writing the note and tags of one call. An empty note with no tags removes it. */
@Composable
private fun NoteDialog(initial: CallNote?, onDismiss: () -> Unit, onSave: (String, List<String>) -> Unit) {
    var text by remember { mutableStateOf(initial?.note.orEmpty()) }
    var tags by remember { mutableStateOf(initial?.tags.orEmpty().joinToString(", ")) }
    CtrlAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CtrlTextField(
                    value = text,
                    onValueChange = { text = it.take(CallNoteRules.MAX_NOTE) },
                    label = { Text(stringResource(R.string.note_label)) },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                CtrlTextField(
                    value = tags,
                    onValueChange = { tags = it.take(200) },
                    label = { Text(stringResource(R.string.note_tags_label)) },
                    placeholder = { Text(stringResource(R.string.note_tags_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text, CallNoteRules.parseTags(tags)) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CtrlColors.Text) } },
    )
}

/** "Remind me to call back": when. */
@Composable
private fun ReminderSheet(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val context = LocalContext.current
    val now = remember { System.currentTimeMillis() }
    val options = remember(now) {
        listOf(
            ReminderTimes.Choice.MIN_15 to R.string.remind_15m,
            ReminderTimes.Choice.MIN_30 to R.string.remind_30m,
            ReminderTimes.Choice.HOUR_1 to R.string.remind_1h,
            ReminderTimes.Choice.HOUR_3 to R.string.remind_3h,
            ReminderTimes.Choice.EVENING to R.string.remind_evening,
            ReminderTimes.Choice.TOMORROW to R.string.remind_tomorrow,
        ).mapNotNull { (choice, label) -> ReminderTimes.at(choice, now)?.let { label to it } }
    }
    CtrlBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(stringResource(R.string.remind_call_back), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            options.forEach { (label, at) ->
                GroupRow(
                    stringResource(label),
                    icon = Icons.Outlined.Alarm,
                    value = DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME),
                    onClick = { onPick(at) },
                )
            }
            Text(
                stringResource(R.string.remind_footer),
                style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}

fun copy(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("", text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
}
