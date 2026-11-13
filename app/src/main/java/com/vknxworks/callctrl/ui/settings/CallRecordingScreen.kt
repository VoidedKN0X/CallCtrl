package com.vknxworks.callctrl.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.callctrl.R
import com.vknxworks.callctrl.container
import com.vknxworks.callctrl.data.Person
import com.vknxworks.callctrl.data.Phone
import com.vknxworks.callctrl.data.settings.AppSettings
import com.vknxworks.callctrl.recording.AutoDelete
import com.vknxworks.callctrl.recording.RecordFormat
import com.vknxworks.callctrl.recording.RecordMode
import com.vknxworks.callctrl.recording.SelectedContact
import com.vknxworks.callctrl.recording.ShizukuGate
import com.vknxworks.callctrl.recording.ShizukuStatus
import com.vknxworks.callctrl.ui.common.launch
import com.vknxworks.callctrl.ui.components.CtrlAlertDialog
import com.vknxworks.callctrl.ui.components.CtrlButton
import com.vknxworks.callctrl.ui.components.CtrlCard
import com.vknxworks.callctrl.ui.components.CtrlTextField
import com.vknxworks.callctrl.ui.components.Group
import com.vknxworks.callctrl.ui.components.GroupChoice
import com.vknxworks.callctrl.ui.components.GroupRow
import com.vknxworks.callctrl.ui.components.GroupSwitch
import com.vknxworks.callctrl.ui.components.ScreenTopBar
import com.vknxworks.callctrl.ui.components.WarningCard
import com.vknxworks.callctrl.ui.lock.AppLock
import com.vknxworks.callctrl.ui.nav.LocalNavigator
import com.vknxworks.callctrl.ui.nav.RecordingsRoute
import com.vknxworks.callctrl.ui.theme.CtrlColors
import com.vknxworks.callctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

/** Where Shizuku can be downloaded (opened in the browser only when you tap Get Shizuku). */
private const val SHIZUKU_URL = "https://shizuku.rikka.app/download/"

@Composable
fun recordModeLabel(mode: RecordMode): String = stringResource(
    when (mode) {
        RecordMode.OFF -> R.string.rec_mode_off
        RecordMode.ASK -> R.string.rec_mode_ask
        RecordMode.ALWAYS -> R.string.rec_mode_always
        RecordMode.UNKNOWN -> R.string.rec_mode_unknown
        RecordMode.SELECTED -> R.string.rec_mode_selected
    },
)

/** Settings > Phone app > Call recording: Shizuku, which calls, file type and what happens to the files. */
@Composable
fun CallRecordingScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val recordings by c.recordings.list.collectAsStateWithLifecycle()
    fun set(transform: (AppSettings) -> AppSettings) {
        c.appScope.launch { c.settings.update(transform) }
    }

    LaunchedEffect0 { ShizukuGate.start(context); c.recordings.reload() }
    val status by ShizukuGate.status.collectAsStateWithLifecycle()
    var isDefault by remember { mutableStateOf(Phone.isDefault(context)) }
    LifecycleResumeEffect(Unit) {
        ShizukuGate.refresh()
        isDefault = Phone.isDefault(context)
        onPauseOrDispose { }
    }
    var notice by remember { mutableStateOf<RecordMode?>(null) }
    var picking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.call_recording))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 4.dp, bottom = 24.dp)) {
            if (!isDefault) {
                WarningCard(stringResource(R.string.rec_not_default), Icons.Outlined.Warning, Modifier.padding(horizontal = Gutter, vertical = 6.dp))
            }
            ShizukuCard(status)

            Group(stringResource(R.string.call_recording), footer = stringResource(R.string.rec_mode_footer)) {
                GroupChoice(
                    stringResource(R.string.rec_mode),
                    RecordMode.entries.map { it to recordModeLabel(it) },
                    settings.recordMode,
                    icon = Icons.Outlined.RadioButtonChecked,
                ) { mode ->
                    if (mode != RecordMode.OFF && !settings.recordNoticeSeen) notice = mode
                    else set { it.copy(recordMode = mode) }
                }
                if (settings.recordMode == RecordMode.SELECTED) {
                    GroupRow(
                        stringResource(R.string.rec_contacts),
                        if (settings.recordContacts.isEmpty()) stringResource(R.string.rec_contacts_none)
                        else settings.recordContacts.joinToString(", ") { it.name },
                        icon = Icons.Outlined.Contacts,
                        showChevron = true,
                        value = settings.recordContacts.size.takeIf { it > 0 }?.toString(),
                        onClick = { picking = true },
                    )
                }
                GroupSwitch(stringResource(R.string.rec_on_hold), stringResource(R.string.rec_on_hold_sub), settings.recordOnHold, Icons.Outlined.Pause) { on ->
                    set { it.copy(recordOnHold = on) }
                }
            }

            Group(stringResource(R.string.rec_group_files), footer = stringResource(R.string.rec_files_footer)) {
                GroupRow(
                    stringResource(R.string.recordings),
                    pluralStringResource(R.plurals.rec_count, recordings.size, recordings.size),
                    icon = Icons.Outlined.Folder,
                    showChevron = true,
                    onClick = { nav.navigate(RecordingsRoute) },
                )
                GroupChoice(
                    stringResource(R.string.rec_format),
                    RecordFormat.entries.map { it to formatLabel(it) },
                    settings.recordFormat,
                    icon = Icons.Outlined.AudioFile,
                ) { f -> set { it.copy(recordFormat = f) } }
                GroupSwitch(stringResource(R.string.rec_copy_music), stringResource(R.string.rec_copy_music_sub), settings.recordCopyToMusic, Icons.Outlined.LibraryMusic) { on ->
                    set { it.copy(recordCopyToMusic = on) }
                }
                GroupChoice(
                    stringResource(R.string.rec_auto_delete),
                    AutoDelete.entries.map { it to autoDeleteLabel(it) },
                    settings.recordAutoDelete,
                    icon = Icons.Outlined.AutoDelete,
                ) { a ->
                    set { it.copy(recordAutoDelete = a) }
                    c.appScope.launch { c.recordings.autoDelete(a) }
                }
            }
        }
    }

    notice?.let { mode ->
        // The first time recording is turned on. Both choices are equal: no pre-ticked box, no nagging.
        CtrlAlertDialog(
            onDismissRequest = { notice = null },
            title = { Text(stringResource(R.string.rec_notice_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.rec_notice_body), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    notice = null
                    set { it.copy(recordMode = mode, recordNoticeSeen = true) }
                }) { Text(stringResource(R.string.rec_notice_ok)) }
            },
            dismissButton = { TextButton(onClick = { notice = null }) { Text(stringResource(R.string.cancel), color = CtrlColors.Text) } },
        )
    }
    if (picking) {
        ContactPicker(
            initial = settings.recordContacts,
            onDismiss = { picking = false },
        ) { picked ->
            picking = false
            set { it.copy(recordContacts = picked) }
        }
    }
}

/** Runs [block] once when the screen opens. */
@Composable
private fun LaunchedEffect0(block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
}

@Composable
fun formatLabel(f: RecordFormat): String = stringResource(
    when (f) {
        RecordFormat.FLAC -> R.string.rec_format_flac
        RecordFormat.WAV -> R.string.rec_format_wav
        RecordFormat.OPUS -> R.string.rec_format_opus
        RecordFormat.AAC -> R.string.rec_format_aac
    },
)

@Composable
private fun autoDeleteLabel(a: AutoDelete): String =
    if (a == AutoDelete.NEVER) stringResource(R.string.rec_auto_never) else pluralStringResource(R.plurals.rec_auto_days, a.days, a.days)

/** Shizuku's state, what to do about it, and the button that does it. */
@Composable
private fun ShizukuCard(status: ShizukuStatus) {
    val context = LocalContext.current
    val (title, body) = when (status) {
        ShizukuStatus.NOT_INSTALLED -> R.string.rec_shizuku_not_installed to R.string.rec_shizuku_not_installed_sub
        ShizukuStatus.NOT_RUNNING -> R.string.rec_shizuku_not_running to R.string.rec_shizuku_not_running_sub
        ShizukuStatus.TOO_OLD -> R.string.rec_shizuku_too_old to R.string.rec_shizuku_too_old_sub
        ShizukuStatus.PERMISSION_NEEDED -> R.string.rec_shizuku_permission to R.string.rec_shizuku_permission_sub
        ShizukuStatus.DENIED -> R.string.rec_shizuku_denied to R.string.rec_shizuku_denied_sub
        ShizukuStatus.READY -> R.string.rec_shizuku_ready to R.string.rec_shizuku_ready_sub
    }
    val ready = status == ShizukuStatus.READY
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp)) {
        com.vknxworks.callctrl.ui.components.SectionLabel(stringResource(R.string.rec_shizuku_group), Modifier.padding(start = 4.dp, bottom = 8.dp))
        CtrlCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
                    null,
                    Modifier.size(22.dp),
                    tint = if (ready) CtrlColors.AccentText else CtrlColors.Error,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, color = CtrlColors.Text)
                    Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted)
                }
            }
            val action: Pair<Int, () -> Unit>? = when (status) {
                ShizukuStatus.NOT_INSTALLED -> R.string.rec_shizuku_get to {
                    context.launch(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Unit
                }
                ShizukuStatus.NOT_RUNNING, ShizukuStatus.DENIED, ShizukuStatus.TOO_OLD -> R.string.rec_shizuku_open to {
                    ShizukuGate.openShizukuIntent(context)?.let { context.launch(it) }
                    Unit
                }
                ShizukuStatus.PERMISSION_NEEDED -> R.string.rec_shizuku_allow to {
                    AppLock.expectReturn()
                    ShizukuGate.requestPermission()
                }
                ShizukuStatus.READY -> null
            }
            if (action != null) {
                Spacer(Modifier.size(12.dp))
                CtrlButton(stringResource(action.first), onClick = action.second)
            }
        }
        Text(
            stringResource(R.string.rec_shizuku_footer),
            style = MaterialTheme.typography.bodySmall,
            color = CtrlColors.Muted,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
        )
    }
}

/** Picks the contacts whose calls are recorded ("Only selected contacts"). */
@Composable
private fun ContactPicker(initial: List<SelectedContact>, onDismiss: () -> Unit, onSave: (List<SelectedContact>) -> Unit) {
    val c = LocalContext.current.container
    val people by c.contacts.people.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(initial.associateBy { it.key }) }
    val shown = remember(people, query) { people.filter { it.matches(query) } }
    CtrlAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rec_contacts), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                CtrlTextField(
                    value = query,
                    onValueChange = { query = it.take(40) },
                    label = { Text(stringResource(R.string.search_contacts)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                if (people.isEmpty()) {
                    Text(stringResource(R.string.rec_contacts_empty), style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Muted)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(shown, key = { it.lookupKey }) { p ->
                        val on = chosen.containsKey(p.lookupKey)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                chosen = if (on) chosen - p.lookupKey else chosen + (p.lookupKey to p.selected())
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = on,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = CtrlColors.Accent, checkmarkColor = CtrlColors.OnAccent, uncheckedColor = CtrlColors.Muted),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Contacts picked earlier keep their numbers; new ones take their numbers now.
                onSave(chosen.values.sortedBy { it.name.lowercase() })
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CtrlColors.Text) } },
    )
}

private fun Person.selected() = SelectedContact(lookupKey, name, phones.map { it.number })
