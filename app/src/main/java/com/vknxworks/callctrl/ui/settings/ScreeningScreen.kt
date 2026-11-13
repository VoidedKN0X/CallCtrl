package com.vknxworks.callctrl.ui.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PhoneLocked
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.callctrl.R
import com.vknxworks.callctrl.container
import com.vknxworks.callctrl.data.Blocked
import com.vknxworks.callctrl.data.Phone
import com.vknxworks.callctrl.data.screening.ImportException
import com.vknxworks.callctrl.data.screening.ImportedRules
import com.vknxworks.callctrl.data.screening.ListedNumber
import com.vknxworks.callctrl.data.screening.RuleKind
import com.vknxworks.callctrl.data.screening.ScreenAction
import com.vknxworks.callctrl.data.screening.ScreenRule
import com.vknxworks.callctrl.data.screening.ScreeningBackup
import com.vknxworks.callctrl.data.screening.ScreeningConfig
import com.vknxworks.callctrl.data.screening.ScreeningMatcher
import com.vknxworks.callctrl.data.screening.ValueError
import com.vknxworks.callctrl.ui.components.CtrlAlertDialog
import com.vknxworks.callctrl.ui.components.CtrlSquareButton
import com.vknxworks.callctrl.ui.components.CtrlTextField
import com.vknxworks.callctrl.ui.components.FieldHeading
import com.vknxworks.callctrl.ui.components.Group
import com.vknxworks.callctrl.ui.components.GroupRow
import com.vknxworks.callctrl.ui.components.GroupSwitch
import com.vknxworks.callctrl.ui.components.ScreenTopBar
import com.vknxworks.callctrl.ui.components.WarningCard
import com.vknxworks.callctrl.ui.lock.AppLock
import com.vknxworks.callctrl.ui.nav.BlockedRoute
import com.vknxworks.callctrl.ui.nav.LocalNavigator
import com.vknxworks.callctrl.ui.theme.CtrlColors
import com.vknxworks.callctrl.ui.theme.Gutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many numbers of your callers list show before "Show all". */
private const val LIST_PREVIEW = 50

/**
 * Call screening: your rules, your callers list, the phone's blocked numbers and import / export. Opened from
 * Settings > Phone app. The screening service reads the same rules.
 */
@Composable
fun ScreeningScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val config by c.screening.config.collectAsStateWithLifecycle()
    fun set(transform: (ScreeningConfig) -> ScreeningConfig) {
        c.appScope.launch { c.screening.update(transform) }
    }

    var isDefault by remember { mutableStateOf(Phone.isDefault(context)) }
    var canBlock by remember { mutableStateOf(Blocked.canBlock(context)) }
    LifecycleResumeEffect(Unit) {
        isDefault = Phone.isDefault(context)
        canBlock = Blocked.canBlock(context)
        onPauseOrDispose { }
    }

    // Rule being edited: index in the list, or -1 for a new one. Null: no editor.
    var editRule by remember { mutableStateOf<Int?>(null) }
    var editNumber by remember { mutableStateOf<Int?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<ImportedRules?>(null) }

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { export(context, uri, json = true) }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { export(context, uri, json = false) }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { ScreeningBackup.parse(readText(context, uri)) } }
                val imported = result.getOrNull()
                when {
                    imported == null -> toast(context, R.string.screening_import_failed)
                    imported.isEmpty -> toast(context, R.string.screening_import_empty)
                    else -> pendingImport = imported
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.screening))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 4.dp, bottom = 24.dp)) {
            if (!isDefault) {
                WarningCard(stringResource(R.string.screening_not_default), Icons.Outlined.Warning, Modifier.padding(horizontal = Gutter, vertical = 6.dp))
            }
            Group(footer = stringResource(R.string.screening_footer)) {
                GroupSwitch(stringResource(R.string.screening_enable), stringResource(R.string.screening_enable_sub), config.enabled, Icons.Outlined.PhoneLocked) { on -> set { it.copy(enabled = on) } }
                if (config.enabled) {
                    GroupSwitch(stringResource(R.string.screening_contacts), stringResource(R.string.screening_contacts_sub), config.contactsAlwaysRing, Icons.Outlined.Contacts) { on -> set { it.copy(contactsAlwaysRing = on) } }
                    GroupSwitch(stringResource(R.string.screening_log), stringResource(R.string.screening_log_sub), config.logRejected, Icons.Outlined.History) { on -> set { it.copy(logRejected = on) } }
                    GroupSwitch(stringResource(R.string.screening_notify), stringResource(R.string.screening_notify_sub), config.notifyRejected, Icons.Outlined.NotificationsNone) { on -> set { it.copy(notifyRejected = on) } }
                }
            }

            Group(stringResource(R.string.screening_rules), footer = stringResource(R.string.screening_rules_footer)) {
                config.rules.forEachIndexed { i, rule ->
                    GroupRow(
                        describe(context, rule),
                        actionLabel(rule.action),
                        icon = kindIcon(rule.kind),
                        onClick = { editRule = i },
                        trailing = {
                            CtrlSquareButton(Icons.Outlined.Close, stringResource(R.string.screening_remove_rule), onClick = {
                                set { cfg -> cfg.copy(rules = cfg.rules.filterIndexed { j, _ -> j != i }) }
                            }, size = 40.dp, tint = CtrlColors.Muted)
                        },
                    )
                }
                GroupRow(stringResource(R.string.screening_add_rule), icon = Icons.Outlined.Add, onClick = { editRule = -1 })
            }

            Group(stringResource(R.string.screening_list), footer = stringResource(R.string.screening_list_footer)) {
                val shown = if (showAll) config.list else config.list.take(LIST_PREVIEW)
                shown.forEachIndexed { i, entry ->
                    GroupRow(
                        Phone.format(context, entry.number).ifBlank { entry.number },
                        actionLabel(entry.action),
                        icon = Icons.Outlined.Pin,
                        onClick = { editNumber = i },
                        trailing = {
                            CtrlSquareButton(Icons.Outlined.Close, stringResource(R.string.screening_remove_number), onClick = {
                                set { cfg -> cfg.copy(list = cfg.list.filterIndexed { j, _ -> j != i }) }
                            }, size = 40.dp, tint = CtrlColors.Muted)
                        },
                    )
                }
                if (!showAll && config.list.size > LIST_PREVIEW) {
                    GroupRow(stringResource(R.string.screening_show_all, config.list.size), showChevron = true, onClick = { showAll = true })
                }
                GroupRow(stringResource(R.string.screening_add_number), icon = Icons.Outlined.Add, onClick = { editNumber = -1 })
            }

            if (canBlock) {
                Group {
                    GroupRow(stringResource(R.string.blocked_numbers), stringResource(R.string.screening_blocked_sub), icon = Icons.Outlined.Block, showChevron = true, onClick = { nav.navigate(BlockedRoute) })
                }
            }

            Group(stringResource(R.string.screening_backup), footer = stringResource(R.string.screening_backup_footer)) {
                GroupRow(stringResource(R.string.screening_export_json), icon = Icons.Outlined.FileUpload, onClick = {
                    AppLock.expectReturn()
                    runCatching { exportJson.launch("CallCtrl-screening.json") }
                })
                GroupRow(stringResource(R.string.screening_export_csv), icon = Icons.Outlined.FileUpload, onClick = {
                    AppLock.expectReturn()
                    runCatching { exportCsv.launch("CallCtrl-screening.csv") }
                })
                GroupRow(stringResource(R.string.screening_import), stringResource(R.string.screening_import_sub), icon = Icons.Outlined.FileDownload, onClick = {
                    AppLock.expectReturn()
                    runCatching { importFile.launch(arrayOf("application/json", "text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")) }
                })
            }
        }
    }

    editRule?.let { index ->
        RuleDialog(
            initial = config.rules.getOrNull(index),
            onDismiss = { editRule = null },
        ) { rule ->
            editRule = null
            set { cfg ->
                if (index in cfg.rules.indices) cfg.copy(rules = cfg.rules.toMutableList().also { it[index] = rule })
                else cfg.copy(rules = cfg.rules + rule)
            }
        }
    }
    editNumber?.let { index ->
        NumberDialog(
            initial = config.list.getOrNull(index),
            onDismiss = { editNumber = null },
        ) { entry ->
            editNumber = null
            set { cfg ->
                val key = ScreeningMatcher.normalize(entry.number)
                val rest = cfg.list.filterIndexed { j, e -> j != index && ScreeningMatcher.normalize(e.number) != key }
                val list = if (index in cfg.list.indices) {
                    cfg.list.toMutableList().also { it[index] = entry }.filterIndexed { j, e -> j == index || ScreeningMatcher.normalize(e.number) != key }
                } else {
                    rest + entry
                }
                cfg.copy(list = list)
            }
        }
    }
    pendingImport?.let { imported ->
        val body = stringResource(
            R.string.screening_import_body,
            pluralStringResource(R.plurals.import_rules, imported.rules.size, imported.rules.size),
            pluralStringResource(R.plurals.import_list, imported.list.size, imported.list.size),
            pluralStringResource(R.plurals.import_blocked, imported.blocked.size, imported.blocked.size),
        ) + if (imported.skipped > 0) "\n\n" + pluralStringResource(R.plurals.screening_import_skipped, imported.skipped, imported.skipped) else ""
        CtrlAlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.screening_import_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    c.appScope.launch {
                        c.screening.update { ScreeningBackup.merge(it, imported) }
                        var failed = false
                        for (n in imported.blocked) {
                            if (!Blocked.isBlocked(context, n) && !Blocked.block(context, n)) failed = true
                        }
                        toast(context, if (failed) R.string.screening_import_blocked_failed else R.string.screening_imported)
                    }
                }) { Text(stringResource(R.string.screening_import_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text(stringResource(R.string.cancel), color = CtrlColors.Text) } },
        )
    }
}

private suspend fun export(context: Context, uri: Uri, json: Boolean) {
    val config = context.container.screening.config.value
    val blocked = if (Blocked.canBlock(context)) Blocked.list(context).map { it.second }.filter { it.isNotBlank() } else emptyList()
    val ok = withContext(Dispatchers.IO) {
        runCatching {
            val text = if (json) ScreeningBackup.toJson(config, blocked) else ScreeningBackup.toCsv(config, blocked)
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) } != null
        }.getOrDefault(false)
    }
    toast(context, if (ok) R.string.screening_exported else R.string.screening_export_failed)
}

/** Reads a picked file as text, refusing anything over the import size limit. */
private fun readText(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > ScreeningBackup.MAX_FILE_BYTES) throw ImportException("too large")
        }
        out.toByteArray()
    } ?: throw ImportException("unreadable")
    return bytes.decodeToString()
}

private fun toast(context: Context, res: Int) {
    Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
}

private fun describe(context: Context, rule: ScreenRule): String = when (rule.kind) {
    RuleKind.PREFIX -> context.getString(R.string.rule_prefix, rule.value)
    RuleKind.EXACT -> context.getString(R.string.rule_exact, Phone.format(context, rule.value).ifBlank { rule.value })
    RuleKind.PATTERN -> context.getString(R.string.rule_pattern, rule.value)
    RuleKind.NOT_IN_CONTACTS -> context.getString(R.string.rule_not_in_contacts)
    RuleKind.HIDDEN -> context.getString(R.string.rule_hidden)
}

private fun kindIcon(kind: RuleKind) = when (kind) {
    RuleKind.PREFIX, RuleKind.PATTERN -> Icons.Outlined.Dialpad
    RuleKind.EXACT -> Icons.Outlined.Pin
    RuleKind.NOT_IN_CONTACTS -> Icons.Outlined.Contacts
    RuleKind.HIDDEN -> Icons.Outlined.VisibilityOff
}

@Composable
private fun actionLabel(action: ScreenAction): String = stringResource(
    when (action) {
        ScreenAction.REJECT -> R.string.action_reject
        ScreenAction.SILENCE -> R.string.action_silence
        ScreenAction.ALLOW -> R.string.action_allow
    },
)

@Composable
private fun errorText(e: ValueError): String = stringResource(
    when (e) {
        ValueError.EMPTY -> R.string.rule_error_empty
        ValueError.CHARACTERS -> R.string.rule_error_characters
        ValueError.NO_DIGITS -> R.string.rule_error_no_digits
        ValueError.TOO_LONG -> R.string.rule_error_too_long
    },
)

/** Options as rows with a check on the chosen one (inside a dialog, instead of a sheet over the dialog). */
@Composable
private fun <T> OptionRows(options: List<Triple<T, String, String?>>, selected: T, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        options.forEach { (value, label, sub) ->
            val isSel = value == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onPick(value) }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = if (isSel) CtrlColors.AccentText else CtrlColors.Text)
                    if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted)
                }
                if (isSel) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.Check, stringResource(R.string.selected), Modifier.size(20.dp), tint = CtrlColors.AccentText)
                }
            }
        }
    }
}

@Composable
private fun actionOptions(includeAllow: Boolean): List<Triple<ScreenAction, String, String?>> = buildList {
    add(Triple(ScreenAction.REJECT, stringResource(R.string.action_reject), stringResource(R.string.action_reject_sub)))
    add(Triple(ScreenAction.SILENCE, stringResource(R.string.action_silence), stringResource(R.string.action_silence_sub)))
    if (includeAllow) add(Triple(ScreenAction.ALLOW, stringResource(R.string.action_allow), stringResource(R.string.action_allow_sub)))
}

@Composable
private fun RuleDialog(initial: ScreenRule?, onDismiss: () -> Unit, onSave: (ScreenRule) -> Unit) {
    var kind by remember { mutableStateOf(initial?.kind ?: RuleKind.PREFIX) }
    var value by remember { mutableStateOf(initial?.value ?: "") }
    var action by remember { mutableStateOf(initial?.action ?: ScreenAction.REJECT) }
    var touched by remember { mutableStateOf(false) }
    val error = ScreeningMatcher.validate(kind, value)
    CtrlAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.screening_add_rule else R.string.screening_edit_rule), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FieldHeading(stringResource(R.string.rule_kind))
                OptionRows(
                    listOf(
                        Triple(RuleKind.PREFIX, stringResource(R.string.kind_prefix), null),
                        Triple(RuleKind.EXACT, stringResource(R.string.kind_exact), null),
                        Triple(RuleKind.PATTERN, stringResource(R.string.kind_pattern), null),
                        Triple(RuleKind.NOT_IN_CONTACTS, stringResource(R.string.kind_not_in_contacts), null),
                        Triple(RuleKind.HIDDEN, stringResource(R.string.kind_hidden), null),
                    ),
                    kind,
                ) { kind = it }
                if (kind.needsValue) {
                    Spacer(Modifier.size(8.dp))
                    CtrlTextField(
                        value = value,
                        onValueChange = { value = it.take(ScreeningMatcher.MAX_VALUE_LENGTH * 2); touched = true },
                        label = {
                            Text(
                                stringResource(
                                    when (kind) {
                                        RuleKind.PREFIX -> R.string.rule_value_prefix
                                        RuleKind.PATTERN -> R.string.rule_value_pattern
                                        else -> R.string.rule_value_exact
                                    },
                                ),
                            )
                        },
                        singleLine = true,
                        // The phone keypad has + and * but no ?, so patterns get the full keyboard.
                        keyboardOptions = KeyboardOptions(keyboardType = if (kind == RuleKind.PATTERN) KeyboardType.Ascii else KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (touched && error != null) {
                        Text(errorText(error), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Error, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                    } else if (kind == RuleKind.PATTERN) {
                        Text(stringResource(R.string.rule_pattern_help), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                    }
                }
                Spacer(Modifier.size(8.dp))
                FieldHeading(stringResource(R.string.rule_action))
                OptionRows(actionOptions(includeAllow = true), action) { action = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(ScreenRule(kind, if (kind.needsValue) value.trim() else "", action))
            }, enabled = error == null) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CtrlColors.Text) } },
    )
}

@Composable
private fun NumberDialog(initial: ListedNumber?, onDismiss: () -> Unit, onSave: (ListedNumber) -> Unit) {
    var number by remember { mutableStateOf(initial?.number ?: "") }
    var action by remember { mutableStateOf(initial?.action ?: ScreenAction.REJECT) }
    var touched by remember { mutableStateOf(false) }
    val error = ScreeningMatcher.validate(RuleKind.EXACT, number)
    CtrlAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.screening_add_number else R.string.screening_edit_number), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CtrlTextField(
                    value = number,
                    onValueChange = { number = it.take(ScreeningMatcher.MAX_VALUE_LENGTH * 2); touched = true },
                    label = { Text(stringResource(R.string.rule_value_exact)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (touched && error != null) {
                    Text(errorText(error), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Error, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                }
                Spacer(Modifier.size(8.dp))
                FieldHeading(stringResource(R.string.rule_action))
                OptionRows(actionOptions(includeAllow = false), action) { action = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ListedNumber(number.trim(), action)) }, enabled = error == null) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CtrlColors.Text) } },
    )
}
