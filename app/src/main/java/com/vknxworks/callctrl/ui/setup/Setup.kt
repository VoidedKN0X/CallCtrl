package com.vknxworks.callctrl.ui.setup

import android.app.Activity
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.callctrl.R
import com.vknxworks.callctrl.container
import com.vknxworks.callctrl.data.Phone
import com.vknxworks.callctrl.ui.common.openAppSettings
import com.vknxworks.callctrl.ui.components.CtrlButton
import com.vknxworks.callctrl.ui.lock.AppLock
import com.vknxworks.callctrl.ui.theme.CtrlColors
import kotlinx.coroutines.launch

/** Permissions CallCtrl can't work without. The default-phone-app role grants most of them by itself. */
val REQUIRED_PERMISSIONS = arrayOf(
    android.Manifest.permission.CALL_PHONE,
    android.Manifest.permission.READ_PHONE_STATE,
    android.Manifest.permission.READ_CALL_LOG,
    android.Manifest.permission.WRITE_CALL_LOG,
    android.Manifest.permission.READ_CONTACTS,
    android.Manifest.permission.POST_NOTIFICATIONS,
)

fun missingPermissions(context: Context): List<String> = REQUIRED_PERMISSIONS.filter { !Phone.has(context, it) }

/** Android 14+ lets you turn off full-screen alerts; incoming calls then only show as a banner. */
fun canFullScreen(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != false

fun openFullScreenSettings(context: Context) {
    AppLock.expectReturn()
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { openAppSettings(context) }
}

/**
 * Shows [content] once CallCtrl can work: it's the default phone app (or you chose to skip that) and has its
 * permissions. Until then, one step at a time.
 */
@Composable
fun SetupGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    var asked by remember { mutableIntStateOf(0) }
    // Check again every time CallCtrl comes back (from Android's settings or the role dialog).
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val isDefault = remember(tick) { Phone.isDefault(context) }
    val missing = remember(tick) { missingPermissions(context) }

    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked++; tick++ }

    when {
        !isDefault && !settings.skippedDefault && Phone.roleAvailable(context) -> Step(
            title = stringResource(R.string.setup_default_title),
            body = stringResource(R.string.setup_default_body),
            button = stringResource(R.string.setup_default_button),
            onButton = {
                val rm = context.getSystemService(RoleManager::class.java)
                if (rm != null) {
                    AppLock.expectReturn()
                    runCatching { roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)) }
                }
            },
            secondary = stringResource(R.string.setup_default_skip),
            onSecondary = { scope.launch { c.settings.update { it.copy(skippedDefault = true) } } },
        )
        missing.isNotEmpty() -> {
            val activity = context as? Activity
            val blocked = asked > 0 && activity != null && missing.none { activity.shouldShowRequestPermissionRationale(it) }
            Step(
                title = stringResource(R.string.setup_perm_title),
                body = stringResource(if (blocked) R.string.setup_perm_blocked else R.string.setup_perm_body),
                button = stringResource(if (blocked) R.string.open_settings else R.string.setup_perm_button),
                onButton = {
                    if (blocked) openAppSettings(context) else {
                        AppLock.expectReturn()
                        permLauncher.launch(missing.toTypedArray())
                    }
                },
            )
        }
        else -> {
            LaunchedEffect(tick) { c.startData() }
            content()
        }
    }
}

@Composable
private fun Step(title: String, body: String, button: String, onButton: () -> Unit, secondary: String? = null, onSecondary: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().background(CtrlColors.Background).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(CtrlColors.Accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Call, null, Modifier.size(30.dp), tint = CtrlColors.OnAccent)
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            CtrlButton(button, onClick = onButton, modifier = Modifier.fillMaxWidth())
            if (secondary != null && onSecondary != null) {
                TextButton(onClick = onSecondary) { Text(secondary, color = CtrlColors.Muted) }
            }
        }
    }
}
