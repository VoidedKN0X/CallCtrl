package com.vknxworks.callctrl.ui.settings

import android.app.role.RoleManager
import android.content.Intent
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.callctrl.R
import com.vknxworks.callctrl.container
import com.vknxworks.callctrl.data.Blocked
import com.vknxworks.callctrl.data.Phone
import com.vknxworks.callctrl.data.lock.LockVault
import com.vknxworks.callctrl.data.settings.AppSettings
import com.vknxworks.callctrl.data.settings.LockTimeout
import com.vknxworks.callctrl.ui.common.launch
import com.vknxworks.callctrl.ui.components.ConfirmDialog
import com.vknxworks.callctrl.ui.components.Group
import com.vknxworks.callctrl.ui.components.GroupChoice
import com.vknxworks.callctrl.ui.components.GroupRow
import com.vknxworks.callctrl.ui.components.GroupSwitch
import com.vknxworks.callctrl.ui.components.ScreenTopBar
import com.vknxworks.callctrl.ui.lock.AppLock
import com.vknxworks.callctrl.ui.lock.PinRequest
import com.vknxworks.callctrl.ui.lock.findActivity
import com.vknxworks.callctrl.recording.RecordMode
import com.vknxworks.callctrl.ui.nav.BlockedRoute
import com.vknxworks.callctrl.ui.nav.CallRecordingRoute
import androidx.compose.material.icons.outlined.RadioButtonChecked
import com.vknxworks.callctrl.ui.nav.ScreeningRoute
import androidx.compose.material.icons.outlined.PhoneLocked
import androidx.compose.ui.res.pluralStringResource
import com.vknxworks.callctrl.ui.nav.LocalNavigator
import com.vknxworks.callctrl.ui.setup.canFullScreen
import com.vknxworks.callctrl.ui.setup.openFullScreenSettings
import kotlinx.coroutines.launch

/** Settings, opened from the You page (tap the profile picture). CallCtrl follows the phone's dark theme. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    fun set(transform: (AppSettings) -> AppSettings) {
        scope.launch { c.settings.update(transform) }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.settings))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 4.dp, bottom = 24.dp)) {
            PhoneAppGroup()
            KeypadGroup(settings) { set(it) }
            RecentsGroup(settings) { set(it) }
            PrivacyAndSecurityGroup(settings)
        }
    }
}

@Composable
private fun PhoneAppGroup() {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val isDefault = remember(tick) { Phone.isDefault(context) }
    val fullScreen = remember(tick) { canFullScreen(context) }
    val canBlock = remember(tick) { Blocked.canBlock(context) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    Group(stringResource(R.string.group_phone_app), footer = stringResource(R.string.phone_app_footer)) {
        GroupRow(
            stringResource(R.string.default_phone_app),
            stringResource(if (isDefault) R.string.default_yes else R.string.default_no),
            icon = Icons.Outlined.Call,
            showChevron = true,
            onClick = {
                AppLock.expectReturn()
                val rm = context.getSystemService(RoleManager::class.java)
                if (!isDefault && rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    runCatching { roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)) }
                } else {
                    context.launch(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
        )
        if (!fullScreen) {
            GroupRow(
                stringResource(R.string.full_screen_calls),
                stringResource(R.string.full_screen_calls_sub),
                icon = Icons.Outlined.FullscreenExit,
                showChevron = true,
                onClick = { openFullScreenSettings(context) },
            )
        }
        val screening by context.container.screening.config.collectAsStateWithLifecycle()
        GroupRow(
            stringResource(R.string.screening),
            if (screening.enabled) {
                stringResource(R.string.screening_sub_on, pluralStringResource(R.plurals.screening_rules_count, screening.rules.size, screening.rules.size))
            } else {
                stringResource(R.string.screening_sub_off)
            },
            icon = Icons.Outlined.PhoneLocked,
            showChevron = true,
            onClick = { nav.navigate(ScreeningRoute) },
        )
        val appSettings by context.container.settings.settings.collectAsStateWithLifecycle()
        GroupRow(
            stringResource(R.string.call_recording),
            if (appSettings.recordMode == RecordMode.OFF) stringResource(R.string.call_recording_sub_off)
            else stringResource(R.string.call_recording_sub_on, recordModeLabel(appSettings.recordMode)),
            icon = Icons.Outlined.RadioButtonChecked,
            showChevron = true,
            onClick = { nav.navigate(CallRecordingRoute) },
        )
        if (canBlock) {
            GroupRow(stringResource(R.string.blocked_numbers), stringResource(R.string.blocked_numbers_sub), icon = Icons.Outlined.Block, showChevron = true, onClick = { nav.navigate(BlockedRoute) })
        }
        GroupRow(
            stringResource(R.string.calling_accounts),
            stringResource(R.string.calling_accounts_sub),
            icon = Icons.Outlined.SimCard,
            showChevron = true,
            onClick = {
                if (!context.launch(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                    Toast.makeText(context, context.getString(R.string.no_app), Toast.LENGTH_SHORT).show()
                }
            },
        )
        GroupRow(
            stringResource(R.string.ringtone),
            stringResource(R.string.ringtone_sub),
            icon = Icons.Outlined.MusicNote,
            showChevron = true,
            onClick = { context.launch(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        )
    }
}

@Composable
private fun KeypadGroup(settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    Group(stringResource(R.string.group_keypad)) {
        GroupSwitch(stringResource(R.string.keypad_tones), stringResource(R.string.keypad_tones_sub), settings.keypadTones, Icons.Outlined.MusicNote) { on -> set { it.copy(keypadTones = on) } }
        GroupSwitch(stringResource(R.string.keypad_haptics), stringResource(R.string.keypad_haptics_sub), settings.keypadHaptics, Icons.Outlined.Vibration) { on -> set { it.copy(keypadHaptics = on) } }
    }
}

@Composable
private fun RecentsGroup(settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    Group(stringResource(R.string.tab_recents), footer = stringResource(R.string.recents_footer)) {
        GroupSwitch(stringResource(R.string.group_calls), stringResource(R.string.group_calls_sub), settings.groupRecents, Icons.Outlined.ViewAgenda) { on -> set { it.copy(groupRecents = on) } }
        GroupRow(stringResource(R.string.clear_history), icon = Icons.Outlined.DeleteSweep, onClick = { confirm = true })
    }
    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.clear_history_title),
            body = stringResource(R.string.clear_history_body),
            confirm = stringResource(R.string.clear_history),
            onDismiss = { confirm = false },
            onConfirm = {
                confirm = false
                scope.launch { c.history.clearAll() }
            },
        )
    }
}

/** Privacy and security: the shared Ctrl series app lock, then hiding CallCtrl in screenshots. */
@Composable
private fun PrivacyAndSecurityGroup(settings: AppSettings) {
    val context = LocalContext.current
    val c = context.container
    val bioAvailable = remember { AppLock.biometricAvailable(context) }
    val lockOn = c.vault.enabled
    Group(stringResource(R.string.group_privacy), footer = stringResource(R.string.app_lock_footer)) {
        GroupSwitch(
            stringResource(R.string.app_lock),
            stringResource(R.string.app_lock_subtitle),
            checked = lockOn,
            icon = Icons.Outlined.Lock,
        ) { on -> AppLock.request = if (on) PinRequest.ENABLE else PinRequest.DISABLE }
        if (lockOn) {
            GroupRow(
                stringResource(R.string.lock_change_pin),
                stringResource(R.string.lock_change_pin_subtitle, LockVault.PIN_MIN),
                icon = Icons.Outlined.Password,
                showChevron = true,
                onClick = { AppLock.request = PinRequest.CHANGE },
            )
            GroupSwitch(
                stringResource(R.string.lock_fingerprint),
                stringResource(if (bioAvailable || c.vault.biometricEnabled) R.string.lock_fingerprint_subtitle else R.string.lock_fingerprint_unavailable),
                checked = c.vault.biometricEnabled,
                icon = Icons.Outlined.Fingerprint,
            ) { on ->
                if (!on) {
                    c.vault.disableBiometric()
                } else if (bioAvailable) {
                    val activity = context.findActivity()
                    val cipher = c.vault.biometricEnrolCipher()
                    if (activity == null || cipher == null) {
                        Toast.makeText(context, context.getString(R.string.lock_fingerprint_setup_failed), Toast.LENGTH_SHORT).show()
                    } else {
                        AppLock.biometricPrompt(
                            activity,
                            context.getString(R.string.lock_fingerprint_enable_title),
                            context.getString(R.string.lock_fingerprint_enable_subtitle),
                            cipher,
                        ) { unlocked ->
                            if (!c.vault.finishBiometricEnrol(unlocked)) {
                                Toast.makeText(context, context.getString(R.string.lock_fingerprint_setup_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            GroupChoice(
                stringResource(R.string.lock_again),
                LockTimeout.entries.map { it to stringResource(it.label) },
                settings.lockTimeout,
                icon = Icons.Outlined.Timer,
            ) { t -> c.appScope.launch { c.settings.update { it.copy(lockTimeout = t) } } }
        }
        GroupSwitch(
            stringResource(R.string.hide_screenshots),
            stringResource(R.string.hide_screenshots_subtitle),
            checked = settings.hideScreenshots,
            icon = Icons.Outlined.VisibilityOff,
        ) { on -> c.appScope.launch { c.settings.update { it.copy(hideScreenshots = on) } } }
        GroupSwitch(
            stringResource(R.string.hide_recents),
            stringResource(R.string.hide_recents_subtitle),
            checked = settings.hideRecents,
            icon = Icons.Outlined.Layers,
        ) { on -> c.appScope.launch { c.settings.update { it.copy(hideRecents = on) } } }
    }
}
