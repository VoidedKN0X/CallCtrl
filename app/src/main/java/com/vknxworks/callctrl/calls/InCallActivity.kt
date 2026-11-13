package com.vknxworks.callctrl.calls

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.callctrl.container
import com.vknxworks.callctrl.ui.theme.CallCtrlTheme

/**
 * The call screen. It shows over the lock screen and is never behind the app lock: a ringing phone must always be
 * answerable. It shows only the call itself, nothing else from CallCtrl.
 */
class InCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        val c = container
        setContent {
            val dark = isSystemInDarkTheme()
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(DARK_BAR) else SystemBarStyle.light(LIGHT_BAR, DARK_BAR)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose { }
            }
            val settings by c.settings.settings.collectAsStateWithLifecycle()
            LaunchedEffect(settings.hideScreenshots) {
                if (settings.hideScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            val calls by CallManager.calls.collectAsStateWithLifecycle()
            val ended by CallManager.lastEnded.collectAsStateWithLifecycle()
            // Nothing left to show: close (after "Call ended" was visible for a moment).
            LaunchedEffect(calls.isEmpty(), ended) {
                if (calls.isEmpty() && ended == null) finishAndRemoveTask()
            }
            CallCtrlTheme(dark) {
                InCallScreen(calls, ended)
            }
        }
    }
}

private val DARK_BAR = android.graphics.Color.rgb(8, 8, 10)
private val LIGHT_BAR = android.graphics.Color.rgb(245, 245, 241)
