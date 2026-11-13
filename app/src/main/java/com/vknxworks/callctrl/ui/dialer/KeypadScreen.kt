package com.vknxworks.callctrl.ui.dialer

import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.callctrl.R
import com.vknxworks.callctrl.container
import com.vknxworks.callctrl.data.CallAccount
import com.vknxworks.callctrl.data.Person
import com.vknxworks.callctrl.data.Phone
import com.vknxworks.callctrl.ui.common.ContactAvatar
import com.vknxworks.callctrl.ui.components.CtrlWordmark
import com.vknxworks.callctrl.ui.components.ProfileButton
import com.vknxworks.callctrl.ui.components.bottomClearance
import com.vknxworks.callctrl.ui.theme.CtrlColors
import com.vknxworks.callctrl.ui.theme.CtrlShapes
import com.vknxworks.callctrl.ui.theme.CtrlType
import com.vknxworks.callctrl.ui.theme.Gutter

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeypadScreen() {
    val context = LocalContext.current
    val c = context.container
    val view = LocalView.current
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val people by c.contacts.people.collectAsStateWithLifecycle()
    val calls by c.history.calls.collectAsStateWithLifecycle()
    val pending by c.dialNumber.collectAsStateWithLifecycle()
    var number by rememberSaveable { mutableStateOf("") }
    var sims by remember { mutableStateOf<List<CallAccount>?>(null) }

    LaunchedEffect(pending) {
        pending?.let { number = it; c.dialNumber.value = null }
    }

    // Key tones follow both CallCtrl's switch and Android's own "Dial pad tones" setting.
    val tones = remember(settings.keypadTones) {
        val system = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1 }.getOrDefault(true)
        if (settings.keypadTones && system) runCatching { ToneGenerator(AudioManager.STREAM_DTMF, 70) }.getOrNull() else null
    }
    DisposableEffect(tones) { onDispose { tones?.release() } }

    fun tone(ch: Char) {
        val t = tones ?: return
        val id = when (ch) {
            in '0'..'9' -> ToneGenerator.TONE_DTMF_0 + (ch - '0')
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            else -> return
        }
        runCatching { t.startTone(id, 120) }
    }

    fun call(n: String) {
        startCall(context, n) { sims = it }
    }

    val matches = remember(number, people) {
        if (number.length < 1) emptyList() else people.filter { it.matchesDigits(number) }.take(3)
    }
    val known = remember(number, people) { people.firstOrNull { p -> p.phones.any { Phone.same(it.number, number) } } }

    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        val compact = maxHeight < 640.dp
        Column(Modifier.fillMaxSize().bottomClearance(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Wordmark and profile picture (no big title: the keypad needs the room).
            Row(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CtrlWordmark("Call", Modifier.weight(1f))
                ProfileButton()
            }

            // Suggestions: contacts whose name (on the keypad letters) or number matches.
            Column(
                Modifier.fillMaxWidth().weight(1f).clipToBounds().padding(horizontal = Gutter, vertical = 8.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                matches.forEach { p -> Suggestion(p, number) { n -> number = n; call(n) } }
                if (number.isNotBlank() && known == null && number.count { it.isDigit() } >= 3) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallAction(Icons.Outlined.PersonAdd, stringResource(R.string.add_to_contacts), Modifier.weight(1f)) { addToContacts(context, number) }
                        SmallAction(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.message), Modifier.weight(1f)) { sendMessage(context, number) }
                    }
                }
            }

            // The number
            Row(Modifier.fillMaxWidth().padding(horizontal = Gutter).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(44.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (number.isEmpty()) "" else Phone.format(context, number).ifBlank { number },
                        style = CtrlType.Readout.copy(fontSize = if (number.length > 14) 26.sp else 34.sp),
                        color = CtrlColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                        textAlign = TextAlign.Center,
                    )
                    known?.let { Text(it.name, style = CtrlType.Data, color = CtrlColors.AccentText, maxLines = 1) }
                }
                if (number.isNotEmpty()) {
                    Box(
                        Modifier.size(44.dp).clip(CtrlShapes.Control)
                            .combinedClickable(
                                onClickLabel = stringResource(R.string.backspace),
                                role = Role.Button,
                                onLongClick = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); number = "" },
                                onClick = { number = number.dropLast(1) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Outlined.Backspace, stringResource(R.string.backspace), Modifier.size(24.dp), tint = CtrlColors.Muted) }
                } else Spacer(Modifier.size(44.dp))
            }

            DialPad(
                onKey = { ch -> if (number.length < 64) number += ch; tone(ch) },
                onLongKey = { ch ->
                    when (ch) {
                        // Long-press 0: "+" for international numbers.
                        '0' -> number = number.dropLast(1) + "+"
                        // Long-press 1 with nothing typed: voicemail.
                        '1' -> if (number.length <= 1) {
                            number = ""
                            if (!Phone.callVoicemail(context)) android.widget.Toast.makeText(context, context.getString(R.string.voicemail_failed), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.widthIn(max = 440.dp).padding(horizontal = Gutter, vertical = 8.dp),
                keyHeight = if (compact) 54.dp else 64.dp,
                haptics = settings.keypadHaptics,
            )

            // Call: with nothing typed, the first tap brings back the last number you called.
            val label = stringResource(R.string.action_call)
            Box(
                Modifier.padding(top = 6.dp).size(width = 132.dp, height = if (compact) 58.dp else 66.dp).clip(CtrlShapes.Hero).background(CtrlColors.Accent)
                    .clickable(role = Role.Button, onClickLabel = label) {
                        if (number.isBlank()) {
                            calls.firstOrNull { it.type == android.provider.CallLog.Calls.OUTGOING_TYPE && it.number.isNotBlank() }?.let { number = it.number }
                        } else call(number)
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Call, label, Modifier.size(30.dp), tint = CtrlColors.OnAccent) }
        }
    }

    sims?.let { list ->
        SimSheet(list, onDismiss = { sims = null }) { a ->
            sims = null
            if (!Phone.call(context, number, a.handle)) android.widget.Toast.makeText(context, context.getString(R.string.call_failed), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun Suggestion(p: Person, typed: String, onCall: (String) -> Unit) {
    val context = LocalContext.current
    val digits = typed.filter { it.isDigit() }
    val phone = p.phones.firstOrNull { it.number.filter { c -> c.isDigit() }.contains(digits) } ?: p.phones.firstOrNull { it.primary } ?: p.phones.first()
    Row(
        Modifier.fillMaxWidth().clip(CtrlShapes.Card).clickable { onCall(phone.number) }.padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(p.name, p.photoUri, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Phone.format(context, phone.number), style = CtrlType.Data, color = CtrlColors.Muted, maxLines = 1)
        }
        Icon(Icons.Outlined.Call, null, Modifier.size(20.dp), tint = CtrlColors.AccentText)
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 44.dp).clip(CtrlShapes.Control).background(CtrlColors.Surface).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = CtrlColors.AccentText)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = CtrlColors.Text, maxLines = 1)
    }
}
