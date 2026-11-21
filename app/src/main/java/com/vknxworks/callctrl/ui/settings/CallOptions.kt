package com.vknxworks.callctrl.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog.Calls
import android.provider.ContactsContract
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.callctrl.R
import com.vknxworks.callctrl.container
import com.vknxworks.callctrl.data.CallEntry
import com.vknxworks.callctrl.data.ExportCall
import com.vknxworks.callctrl.data.ExportType
import com.vknxworks.callctrl.data.HistoryExport
import com.vknxworks.callctrl.data.Phone
import com.vknxworks.callctrl.data.notes.CallNoteRules
import com.vknxworks.callctrl.data.quiet.QuietHours
import com.vknxworks.callctrl.data.quiet.QuietRules
import com.vknxworks.callctrl.data.reminders.Reminder
import com.vknxworks.callctrl.data.settings.AppSettings
import com.vknxworks.callctrl.data.settings.DialRules
import com.vknxworks.callctrl.data.settings.SettingField
import com.vknxworks.callctrl.data.settings.SettingsFile
import com.vknxworks.callctrl.data.settings.SettingsImport
import com.vknxworks.callctrl.data.settings.SpeedDial
import com.vknxworks.callctrl.ui.components.ConfirmDialog
import com.vknxworks.callctrl.ui.components.CtrlAlertDialog
import com.vknxworks.callctrl.ui.components.CtrlBottomSheet
import com.vknxworks.callctrl.ui.components.CtrlTextField
import com.vknxworks.callctrl.ui.components.Group
import com.vknxworks.callctrl.ui.components.GroupChoice
import com.vknxworks.callctrl.ui.components.GroupRow
import com.vknxworks.callctrl.ui.components.GroupSwitch
import com.vknxworks.callctrl.ui.components.MessageView
import com.vknxworks.callctrl.ui.components.ScreenTopBar
import com.vknxworks.callctrl.ui.components.WarningCard
import com.vknxworks.callctrl.ui.lock.AppLock
import com.vknxworks.callctrl.ui.nav.DeclineMessagesRoute
import com.vknxworks.callctrl.ui.nav.LocalNavigator
import com.vknxworks.callctrl.ui.nav.QuietModeRoute
import com.vknxworks.callctrl.ui.nav.SpeedDialRoute
import com.vknxworks.callctrl.ui.theme.CtrlColors
import com.vknxworks.callctrl.ui.theme.CtrlShapes
import com.vknxworks.callctrl.ui.theme.Gutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

// ======================================================================== the Calls group in Settings

/** Settings > Calls: confirm before calling, flip to silence, speed dial, decline messages and quiet mode. */
@Composable
fun CallsGroup(settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val quiet by context.container.quiet.quiet.collectAsStateWithLifecycle()
    Group(stringResource(R.string.group_calls_options)) {
        GroupSwitch(stringResource(R.string.confirm_calls), stringResource(R.string.confirm_calls_sub), settings.confirmCalls, Icons.Outlined.CheckCircle) { on -> set { it.copy(confirmCalls = on) } }
        GroupSwitch(stringResource(R.string.flip_to_silence), stringResource(R.string.flip_to_silence_sub), settings.flipToSilence, Icons.Outlined.ScreenRotation) { on -> set { it.copy(flipToSilence = on) } }
        GroupRow(
            stringResource(R.string.speed_dial),
            if (settings.speedDial.isEmpty()) stringResource(R.string.speed_dial_sub_none)
            else pluralStringResource(R.plurals.speed_dial_count, settings.speedDial.size, settings.speedDial.size),
            icon = Icons.Outlined.Dialpad,
            showChevron = true,
            onClick = { nav.navigate(SpeedDialRoute) },
        )
        GroupRow(
            stringResource(R.string.decline_messages),
            stringResource(if (settings.declineMessages.isEmpty()) R.string.decline_messages_sub_standard else R.string.decline_messages_sub_own),
            icon = Icons.AutoMirrored.Outlined.Message,
            showChevron = true,
            onClick = { nav.navigate(DeclineMessagesRoute) },
        )
        GroupRow(
            stringResource(R.string.quiet_mode),
            if (quiet.enabled) stringResource(R.string.quiet_mode_sub_on, QuietRules.clock(quiet.startMinute), QuietRules.clock(quiet.endMinute))
            else stringResource(R.string.quiet_mode_sub_off),
            icon = Icons.Outlined.NotificationsOff,
            showChevron = true,
            onClick = { nav.navigate(QuietModeRoute) },
        )
    }
}

/** A dialog with one text field, for a number or a message. */
@Composable
private fun TextDialog(
    title: String,
    initial: String,
    max: Int,
    phone: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    CtrlAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            CtrlTextField(
                value = text,
                onValueChange = { text = it.take(max) },
                singleLine = phone,
                minLines = if (phone) 1 else 2,
                keyboardOptions = if (phone) KeyboardOptions(keyboardType = KeyboardType.Phone) else KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CtrlColors.Text) } },
    )
}

// ======================================================================== speed dial

/** The phone's contact picker for one phone number. The picker lets CallCtrl read just the number that was picked. */
private object PickPhoneNumber : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** The number and name behind a picked phone-number row, or null when it can't be read. */
private fun readPickedPhone(context: Context, uri: Uri): Pair<String, String>? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
        null, null, null,
    )?.use { c ->
        if (!c.moveToFirst()) return@use null
        val number = c.getString(0)?.trim().orEmpty()
        if (number.isEmpty()) null else number to c.getString(1).orEmpty()
    }
}.getOrNull()

/** Settings > Speed dial: the numbers behind a long press on keys 2 to 9 of the keypad. */
@Composable
fun SpeedDialScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    // The key whose menu is open, the key a number is being typed for, and the key the contact picker is open for.
    var menu by remember { mutableStateOf<Int?>(null) }
    var typing by remember { mutableStateOf<Int?>(null) }
    var pickFor by rememberSaveable { mutableIntStateOf(0) }

    fun put(key: Int, entry: SpeedDial?) {
        scope.launch {
            c.settings.update { s -> s.copy(speedDial = DialRules.cleanSpeedDial(s.speedDial.filterNot { it.key == key } + listOfNotNull(entry))) }
        }
    }
    val pick = rememberLauncherForActivityResult(PickPhoneNumber) { uri ->
        val key = pickFor
        if (uri == null || key !in DialRules.KEYS) return@rememberLauncherForActivityResult
        scope.launch {
            val found = withContext(Dispatchers.IO) { readPickedPhone(context, uri) }
            if (found == null) Toast.makeText(context, context.getString(R.string.speed_dial_pick_failed), Toast.LENGTH_SHORT).show()
            else put(key, SpeedDial(key, found.first, found.second))
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.speed_dial))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 4.dp, bottom = 24.dp)) {
            Group(footer = stringResource(R.string.speed_dial_footer)) {
                for (key in DialRules.KEYS) {
                    val entry = settings.speedDial.firstOrNull { it.key == key }
                    val number = entry?.let { Phone.format(context, it.number).ifBlank { it.number } }
                    GroupRow(
                        when {
                            entry == null -> stringResource(R.string.speed_dial_not_set)
                            entry.name.isNotBlank() -> entry.name
                            else -> number.orEmpty()
                        },
                        if (entry != null && entry.name.isNotBlank()) number else null,
                        icon = Icons.Outlined.Dialpad,
                        iconTint = if (entry != null) CtrlColors.AccentText else CtrlColors.Muted,
                        titleColor = if (entry != null) CtrlColors.Text else CtrlColors.Muted,
                        value = stringResource(R.string.speed_dial_key, key),
                        onClick = { menu = key },
                    )
                }
            }
        }
    }

    val menuKey = menu
    if (menuKey != null) {
        val key: Int = menuKey
        val entry = settings.speedDial.firstOrNull { it.key == key }
        CtrlBottomSheet(onDismissRequest = { menu = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Text(stringResource(R.string.speed_dial_key_title, key), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                GroupRow(stringResource(R.string.speed_dial_pick_contact), icon = Icons.Outlined.Contacts, onClick = {
                    menu = null
                    pickFor = key
                    AppLock.expectReturn()
                    runCatching { pick.launch(Unit) }.onFailure {
                        Toast.makeText(context, context.getString(R.string.no_app), Toast.LENGTH_SHORT).show()
                    }
                })
                GroupRow(stringResource(R.string.speed_dial_type_number), icon = Icons.Outlined.Edit, onClick = { menu = null; typing = key })
                if (entry != null) {
                    GroupRow(stringResource(R.string.speed_dial_remove), icon = Icons.Outlined.Delete, iconTint = CtrlColors.Error, titleColor = CtrlColors.Error, onClick = { menu = null; put(key, null) })
                }
            }
        }
    }
    val typingKey = typing
    if (typingKey != null) {
        val key: Int = typingKey
        TextDialog(
            title = stringResource(R.string.speed_dial_key_title, key),
            initial = settings.speedDial.firstOrNull { it.key == key }?.number.orEmpty(),
            max = 40,
            phone = true,
            onDismiss = { typing = null },
        ) { number ->
            typing = null
            if (number.any { it.isDigit() }) put(key, SpeedDial(key, number, "")) else Toast.makeText(context, context.getString(R.string.speed_dial_pick_failed), Toast.LENGTH_SHORT).show()
        }
    }
}

// ======================================================================== decline messages

/** Settings > Decline messages: the texts offered by "Reply with a message" when a call comes in. */
@Composable
fun DeclineMessagesScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val standard = listOf(stringResource(R.string.reply_1), stringResource(R.string.reply_2), stringResource(R.string.reply_3))
    val own = settings.declineMessages
    val shown = own.ifEmpty { standard }
    // The place of the message being written (-1 for a new one), and of the one whose menu is open.
    var editing by remember { mutableStateOf<Int?>(null) }
    var menu by remember { mutableStateOf<Int?>(null) }

    fun save(list: List<String>) {
        scope.launch { c.settings.update { it.copy(declineMessages = DialRules.cleanMessages(list)) } }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.decline_messages))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 4.dp, bottom = 24.dp)) {
            Group(footer = stringResource(R.string.decline_messages_footer)) {
                shown.forEachIndexed { i, text ->
                    GroupRow(text, icon = Icons.AutoMirrored.Outlined.Message, onClick = { menu = i })
                }
                if (shown.size < DialRules.MAX_MESSAGES) {
                    GroupRow(stringResource(R.string.decline_messages_add), icon = Icons.Outlined.Add, iconTint = CtrlColors.AccentText, onClick = { editing = -1 })
                }
                if (own.isNotEmpty()) {
                    GroupRow(stringResource(R.string.decline_messages_reset), icon = Icons.Outlined.Restore, onClick = { save(emptyList()) })
                }
            }
        }
    }

    val menuIndex = menu
    if (menuIndex != null && menuIndex in shown.indices) {
        val index: Int = menuIndex
        CtrlBottomSheet(onDismissRequest = { menu = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Text(shown[index], style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                GroupRow(stringResource(R.string.decline_messages_edit), icon = Icons.Outlined.Edit, onClick = { menu = null; editing = index })
                GroupRow(stringResource(R.string.decline_messages_delete), icon = Icons.Outlined.Delete, iconTint = CtrlColors.Error, titleColor = CtrlColors.Error, onClick = {
                    menu = null
                    save(shown.filterIndexed { i, _ -> i != index })
                })
            }
        }
    }
    val editIndex = editing
    if (editIndex != null) {
        val index: Int = editIndex
        TextDialog(
            title = stringResource(if (index < 0) R.string.decline_messages_add else R.string.decline_messages_edit),
            initial = shown.getOrNull(index).orEmpty(),
            max = DialRules.MAX_MESSAGE,
            phone = false,
            onDismiss = { editing = null },
        ) { text ->
            editing = null
            val list = shown.toMutableList()
            if (index in list.indices) list[index] = text else list += text
            save(list)
        }
    }
}

// ======================================================================== quiet mode

/** Settings > Quiet mode: hours in which incoming calls don't ring. */
@Composable
fun QuietModeScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val quiet by c.quiet.quiet.collectAsStateWithLifecycle()
    val isDefault = remember { Phone.isDefault(context) }
    fun set(transform: (QuietHours) -> QuietHours) {
        scope.launch { c.quiet.update(transform) }
    }
    // Every half hour of the day.
    val times = remember { (0 until 48).map { (it * 30) to QuietRules.clock(it * 30) } }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.quiet_mode))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 4.dp, bottom = 24.dp)) {
            if (!isDefault) {
                WarningCard(stringResource(R.string.quiet_needs_default), Icons.Outlined.Warning, Modifier.padding(horizontal = Gutter, vertical = 6.dp))
            }
            Group(footer = stringResource(R.string.quiet_footer)) {
                GroupSwitch(stringResource(R.string.quiet_enabled), stringResource(R.string.quiet_enabled_sub), quiet.enabled, Icons.Outlined.NotificationsOff) { on -> set { it.copy(enabled = on) } }
                GroupChoice(stringResource(R.string.quiet_from), times, quiet.startMinute, fallbackLabel = QuietRules.clock(quiet.startMinute)) { m -> set { it.copy(startMinute = m) } }
                GroupChoice(stringResource(R.string.quiet_until), times, quiet.endMinute, fallbackLabel = QuietRules.clock(quiet.endMinute)) { m -> set { it.copy(endMinute = m) } }
            }
            Group(stringResource(R.string.quiet_days), footer = stringResource(R.string.quiet_days_footer)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (day in 1..7) {
                        val on = day in quiet.days
                        val name = DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, Locale.getDefault())
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                                .clip(CtrlShapes.Chip)
                                .background(if (on) CtrlColors.Accent else CtrlColors.Surface)
                                .border(1.dp, if (on) CtrlColors.Accent else CtrlColors.ControlOutline, CtrlShapes.Chip)
                                .clickable(onClickLabel = DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.getDefault())) {
                                    set { q -> q.copy(days = if (day in q.days) q.days - day else q.days + day) }
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text(name, style = MaterialTheme.typography.labelLarge, color = if (on) CtrlColors.OnAccent else CtrlColors.Text, maxLines = 1) }
                    }
                }
            }
            Group(stringResource(R.string.quiet_exceptions), footer = stringResource(R.string.quiet_exceptions_footer)) {
                GroupSwitch(stringResource(R.string.quiet_favourites), stringResource(R.string.quiet_favourites_sub), quiet.favouritesRing, Icons.Outlined.Star) { on -> set { it.copy(favouritesRing = on) } }
                GroupSwitch(stringResource(R.string.quiet_contacts), stringResource(R.string.quiet_contacts_sub), quiet.contactsRing, Icons.Outlined.Contacts) { on -> set { it.copy(contactsRing = on) } }
            }
        }
    }
}

// ======================================================================== call-back reminders

/** Call-back reminders that are still to come, opened from the You page. */
@Composable
fun RemindersScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val items by c.reminders.items.collectAsStateWithLifecycle()
    val unreadable by c.reminders.unreadable.collectAsStateWithLifecycle()
    val people by c.contacts.people.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<Reminder?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.reminders))
        when {
            unreadable -> MessageView(
                Icons.Outlined.Lock,
                stringResource(R.string.reminders_unreadable),
                stringResource(R.string.reminders_unreadable_body),
                actionLabel = stringResource(R.string.try_again),
                onAction = { scope.launch { c.reminders.load() } },
            )
            items.isEmpty() -> MessageView(Icons.Outlined.Alarm, stringResource(R.string.reminders_empty), stringResource(R.string.reminders_empty_body))
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 4.dp, bottom = 24.dp)) {
                Group(footer = stringResource(R.string.reminders_footer)) {
                    items.forEach { r ->
                        val name = people.firstOrNull { p -> p.phones.any { Phone.same(it.number, r.number) } }?.name ?: r.name.takeIf { it.isNotBlank() }
                        val number = Phone.format(context, r.number).ifBlank { r.number }
                        GroupRow(
                            name ?: number,
                            listOfNotNull(
                                number.takeIf { name != null },
                                DateUtils.formatDateTime(context, r.at, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL),
                            ).joinToString(" · "),
                            icon = Icons.Outlined.Alarm,
                            iconTint = CtrlColors.AccentText,
                            onClick = { removing = r },
                        )
                    }
                }
            }
        }
    }

    removing?.let { r ->
        ConfirmDialog(
            title = stringResource(R.string.reminder_remove_title),
            body = stringResource(R.string.reminder_remove_body),
            confirm = stringResource(R.string.reminder_remove),
            onDismiss = { removing = null },
            onConfirm = {
                removing = null
                scope.launch { c.reminders.remove(r.id) }
            },
        )
    }
}

// ======================================================================== history export

private fun exportType(type: Int): ExportType = when (type) {
    Calls.INCOMING_TYPE, Calls.ANSWERED_EXTERNALLY_TYPE -> ExportType.INCOMING
    Calls.OUTGOING_TYPE -> ExportType.OUTGOING
    Calls.VOICEMAIL_TYPE -> ExportType.VOICEMAIL
    Calls.BLOCKED_TYPE -> ExportType.BLOCKED
    Calls.REJECTED_TYPE -> ExportType.DECLINED
    else -> ExportType.MISSED
}

/** "Export call history" in Settings > Recents: the whole call log, with your notes and tags, as a CSV file. */
@Composable
fun HistoryExportRow() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val calls: List<CallEntry> = c.history.all()
                    val notes = c.notes.notes.value
                    val sims = Phone.callAccounts(context).associate { it.handle.id to it.label }
                    // Contact names by the last nine digits of their numbers: one walk through the contacts, not one look-up per call.
                    val names = HashMap<String, String>()
                    for (p in c.contacts.people.value) for (d in p.digits) if (d.isNotEmpty()) names.putIfAbsent(d.takeLast(9), p.name)
                    val rows = calls.map { e ->
                        val digits = e.number.filter { it.isDigit() }
                        val note = notes[CallNoteRules.key(e.id, e.date)]
                        ExportCall(
                            date = e.date,
                            type = exportType(e.type),
                            number = if (e.hidden) "" else e.number,
                            name = if (e.hidden) "" else names[digits.takeLast(9)] ?: e.cachedName.orEmpty(),
                            durationSeconds = e.duration,
                            sim = e.accountId?.let { sims[it] }.orEmpty(),
                            note = note?.note.orEmpty(),
                            tags = note?.tags.orEmpty(),
                        )
                    }
                    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("no stream")
                    stream.use { it.write(HistoryExport.csv(rows).toByteArray(Charsets.UTF_8)) }
                    rows.size
                }.getOrNull()
            }
            busy = false
            Toast.makeText(
                context,
                if (count != null) context.resources.getQuantityString(R.plurals.history_exported, count, count) else context.getString(R.string.history_export_failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    GroupRow(
        stringResource(R.string.history_export),
        stringResource(if (busy) R.string.history_exporting else R.string.history_export_sub),
        icon = Icons.Outlined.FileUpload,
        onClick = if (busy) null else ({
            AppLock.expectReturn()
            runCatching { create.launch(HistoryExport.fileName(System.currentTimeMillis())) }
        }),
    )
}

// ======================================================================== settings file

@Composable
private fun fieldLabel(field: SettingField): String = stringResource(
    when (field) {
        SettingField.KEYPAD_TONES -> R.string.keypad_tones
        SettingField.KEYPAD_HAPTICS -> R.string.keypad_haptics
        SettingField.GROUP_RECENTS -> R.string.group_calls
        SettingField.CONFIRM_CALLS -> R.string.confirm_calls
        SettingField.FLIP_TO_SILENCE -> R.string.flip_to_silence
        SettingField.HIDE_SCREENSHOTS -> R.string.hide_screenshots
        SettingField.HIDE_RECENTS -> R.string.hide_recents
        SettingField.RECORD_FORMAT -> R.string.settings_file_record_format
        SettingField.RECORD_ON_HOLD -> R.string.settings_file_record_on_hold
    },
)

@Composable
private fun fieldValue(raw: String): String = when (raw) {
    "true" -> stringResource(R.string.value_on)
    "false" -> stringResource(R.string.value_off)
    else -> raw
}

/** Settings > Settings file: export CallCtrl's settings to a file, or bring them back from one. */
@Composable
fun SettingsFileGroup() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    // The file that was picked, read and checked, waiting for a yes.
    var pending by remember { mutableStateOf<Pair<String, SettingsImport>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = SettingsFile.export(c.settings.load(), c.quiet.current())
                    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("no stream")
                    stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }.isSuccess
            }
            message = context.getString(if (ok) R.string.settings_file_exported else R.string.settings_file_export_failed)
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytes(SettingsFile.MAX_BYTES + 1)
                        if (bytes.size > SettingsFile.MAX_BYTES) null else String(bytes, Charsets.UTF_8)
                    }
                    text?.let { t -> SettingsFile.plan(t, c.settings.load(), c.quiet.current())?.let { t to it } }
                }.getOrNull()
            }
            when {
                read == null -> message = context.getString(R.string.settings_file_not_ours)
                read.second.nothing && read.second.refused.isEmpty() -> message = context.getString(R.string.settings_file_same)
                else -> pending = read
            }
        }
    }

    Group(stringResource(R.string.settings_file_title), footer = stringResource(R.string.settings_file_footer)) {
        GroupRow(
            stringResource(R.string.settings_file_export),
            stringResource(R.string.settings_file_export_sub),
            icon = Icons.Outlined.FileUpload,
            onClick = {
                AppLock.expectReturn()
                val day = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date())
                runCatching { create.launch("CallCtrl-settings-$day.json") }
            },
        )
        GroupRow(
            stringResource(R.string.settings_file_import),
            stringResource(R.string.settings_file_import_sub),
            icon = Icons.Outlined.FileDownload,
            onClick = {
                AppLock.expectReturn()
                runCatching { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            },
        )
    }

    message?.let { text ->
        CtrlAlertDialog(
            onDismissRequest = { message = null },
            title = { Text(stringResource(R.string.settings_file_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.ok)) } },
        )
    }

    pending?.let { (text, plan) ->
        val lines = ArrayList<String>()
        if (!plan.nothing) {
            lines += stringResource(R.string.settings_file_will_change)
            for (change in plan.changes) lines += "• " + fieldLabel(change.field) + ": " + fieldValue(change.to)
            if (plan.speedDialChanges) lines += "• " + stringResource(R.string.settings_file_speed_dial, plan.settings.speedDial.size)
            if (plan.messagesChange) lines += "• " + stringResource(R.string.settings_file_messages)
            plan.quiet?.let { q ->
                lines += "• " + if (q.enabled) stringResource(R.string.settings_file_quiet_on, QuietRules.clock(q.startMinute), QuietRules.clock(q.endMinute))
                else stringResource(R.string.settings_file_quiet_off)
            }
        }
        if (plan.refused.isNotEmpty()) {
            if (lines.isNotEmpty()) lines += ""
            lines += stringResource(R.string.settings_file_refused)
            for (field in plan.refused) lines += "• " + fieldLabel(field)
        }
        if (plan.nothing) {
            CtrlAlertDialog(
                onDismissRequest = { pending = null },
                title = { Text(stringResource(R.string.settings_file_import), style = MaterialTheme.typography.titleLarge) },
                text = { Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodyMedium) },
                confirmButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.ok)) } },
            )
        } else {
            ConfirmDialog(
                title = stringResource(R.string.settings_file_confirm_title),
                body = lines.joinToString("\n"),
                confirm = stringResource(R.string.settings_file_confirm),
                onDismiss = { pending = null },
                onConfirm = {
                    pending = null
                    scope.launch {
                        // Worked out again against the settings as they are now, with the same rules.
                        val quietNow = withContext(Dispatchers.IO) { c.quiet.current() }
                        c.settings.update { now -> SettingsFile.plan(text, now, quietNow)?.settings ?: now }
                        plan.quiet?.let { q -> c.quiet.update { q } }
                        message = context.getString(R.string.settings_file_imported)
                    }
                },
            )
        }
    }
}
