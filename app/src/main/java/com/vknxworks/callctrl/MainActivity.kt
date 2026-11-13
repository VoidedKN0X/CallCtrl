package com.vknxworks.callctrl

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.callctrl.ui.lock.AppLockGate
import com.vknxworks.callctrl.ui.components.shield
import com.vknxworks.callctrl.ui.lock.lockCovering
import com.vknxworks.callctrl.ui.nav.AppRoot
import com.vknxworks.callctrl.ui.theme.CallCtrlTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) take(intent)
        setContent { CtrlWindow(this) { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
    }

    /** A number to dial from another app ("tel:" links, DIAL), or the missed-call notification. */
    private fun take(intent: Intent?) {
        intent ?: return
        val c = container
        when (intent.action) {
            Intent.ACTION_DIAL, Intent.ACTION_VIEW -> {
                if (intent.type == "vnd.android.cursor.dir/calls") {
                    c.showRecents.value = true
                } else {
                    val number = intent.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart?.trim()
                    if (!number.isNullOrEmpty()) c.dialNumber.value = number.take(64)
                }
            }
            ACTION_SHOW_RECENTS -> c.showRecents.value = true
        }
    }

    companion object {
        const val ACTION_SHOW_RECENTS = "com.vknxworks.callctrl.SHOW_RECENTS"
        const val ACTION_ADD_CALL = "com.vknxworks.callctrl.ADD_CALL"
    }
}

/**
 * The Ctrl window: follows the phone's dark theme, applies "Hide in screenshots" / "Hide in recent apps",
 * and shows nothing of the app while the app lock is locked.
 */
@Composable
fun CtrlWindow(activity: ComponentActivity, content: @Composable () -> Unit) {
    val c = activity.container
    val dark = isSystemInDarkTheme()
    DisposableEffect(dark) {
        val bars = if (dark) SystemBarStyle.dark(DARK_BAR) else SystemBarStyle.light(LIGHT_BAR, DARK_BAR)
        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        onDispose { }
    }
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val secure = settings.hideScreenshots || lockCovering(c.vault)
    val blankRecents = settings.hideRecents || c.vault.enabled
    LaunchedEffect(secure, blankRecents) {
        if (secure) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activity.setRecentsScreenshotEnabled(!blankRecents)
    }
    // The lock screen and PIN screens (only those): no other app's floating window over them, and their content
    // hidden from apps misusing accessibility. Lifted again when they close, so overlays keep working in the rest of the app.
    val shielded = lockCovering(c.vault)
    LaunchedEffect(shielded) { activity.window.shield(shielded, sensitiveData = shielded) }
    CallCtrlTheme(dark) {
        AppLockGate { content() }
    }
}

private val DARK_BAR = android.graphics.Color.rgb(8, 8, 10)
private val LIGHT_BAR = android.graphics.Color.rgb(245, 245, 241)
