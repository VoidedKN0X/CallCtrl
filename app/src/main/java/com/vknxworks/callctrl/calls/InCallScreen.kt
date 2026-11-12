package com.vknxworks.callctrl.calls

import android.content.Intent
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallAudioState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddIcCall
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material.icons.outlined.SwapCalls
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.callctrl.MainActivity
import com.vknxworks.callctrl.R
import com.vknxworks.callctrl.data.Phone
import com.vknxworks.callctrl.ui.common.ContactAvatar
import com.vknxworks.callctrl.ui.components.CtrlBottomSheet
import com.vknxworks.callctrl.ui.components.GroupRow
import com.vknxworks.callctrl.ui.components.NameDialog
import com.vknxworks.callctrl.ui.components.SectionLabel
import com.vknxworks.callctrl.ui.dialer.DialPad
import com.vknxworks.callctrl.ui.theme.CtrlColors
import com.vknxworks.callctrl.ui.theme.CtrlShapes
import com.vknxworks.callctrl.ui.theme.CtrlType
import com.vknxworks.callctrl.ui.theme.Gutter
import kotlinx.coroutines.delay

@Composable
fun InCallScreen(calls: List<CallInfo>, ended: CallInfo?) {
    val context = LocalContext.current
    val audio by CallManager.audio.collectAsStateWithLifecycle()
    val main = calls.firstOrNull { it.ringing } ?: calls.firstOrNull { it.active } ?: calls.firstOrNull { !it.ended } ?: calls.firstOrNull() ?: ended
    var keypad by remember { mutableStateOf(false) }
    var dtmf by remember { mutableStateOf("") }
    var replies by remember { mutableStateOf(false) }
    var routes by remember { mutableStateOf(false) }

    if (main == null) {
        Box(Modifier.fillMaxSize().background(CtrlColors.Background))
        return
    }
    Box(Modifier.fillMaxSize().background(CtrlColors.Background)) {
        val other = calls.firstOrNull { it.call != main.call && !it.ended }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = Gutter, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // State and SIM
            SectionLabel(stateText(main, isEnded = main === ended), color = if (main.ringing) CtrlColors.AccentText else CtrlColors.Muted)
            main.accountLabel?.let {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.SimCard, null, Modifier.size(14.dp), tint = CtrlColors.Muted)
                    Spacer(Modifier.width(4.dp))
                    Text(it, style = CtrlType.Data, color = CtrlColors.Muted)
                }
            }
            if (!keypad) {
                Spacer(Modifier.height(28.dp))
                ContactAvatar(main.title, main.caller.photoUri, size = 120.dp)
                Spacer(Modifier.height(18.dp))
                Text(
                    if (main.isConference) stringResource(R.string.conference, main.conferenceSize)
                    else main.caller.name ?: main.number?.let { Phone.format(context, it) } ?: stringResource(R.string.unknown_caller),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (main.caller.name != null && main.number != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(main.caller.label, Phone.format(context, main.number)).joinToString(" · "),
                        style = CtrlType.Data, color = CtrlColors.Muted, textAlign = TextAlign.Center,
                    )
                }
                if (main.active && main.connectedAt > 0) {
                    Spacer(Modifier.height(10.dp))
                    CallTimer(main.connectedAt)
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Text(main.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(dtmf.ifEmpty { " " }, style = CtrlType.Readout, color = CtrlColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            // A second call: waiting (ringing) or on hold.
            if (other != null && !keypad) {
                Spacer(Modifier.height(18.dp))
                SecondCall(other)
            }

            Spacer(Modifier.weight(1f))

            when {
                main === ended || main.ended -> Unit
                main.ringing -> Incoming(main, onReplies = { replies = true })
                main.choosingSim -> SimChoice(main)
                keypad -> {
                    DialPad(
                        onKey = { ch ->
                            dtmf = (dtmf + ch).takeLast(40)
                            CallManager.dtmf(main.call, ch)
                        },
                        onKeyUp = { CallManager.stopDtmf(main.call) },
                        modifier = Modifier.widthIn(max = 420.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { keypad = false }) { Text(stringResource(R.string.hide_keypad), color = CtrlColors.AccentText) }
                        Spacer(Modifier.width(24.dp))
                        EndButton { CallManager.hangUp(main.call) }
                    }
                }
                else -> {
                    Controls(
                        main = main,
                        audio = audio,
                        hasOther = other != null,
                        onKeypad = { keypad = true },
                        onAudio = {
                            val a = audio
                            if (a != null && (a.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0) routes = true
                            else CallManager.toggleSpeaker()
                        },
                        onAddCall = {
                            runCatching {
                                context.startActivity(Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_ADD_CALL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                    )
                    Spacer(Modifier.height(20.dp))
                    EndButton { CallManager.hangUp(main.call) }
                }
            }
        }
    }

    if (replies) {
        val ringing = calls.firstOrNull { it.ringing }
        QuickReplies(
            onDismiss = { replies = false },
            onSend = { text ->
                replies = false
                ringing?.let { CallManager.decline(it.call, text) }
            },
        )
    }
    if (routes) {
        RouteSheet(audio, onDismiss = { routes = false }) { r ->
            routes = false
            CallManager.setRoute(r)
        }
    }
}

@Composable
private fun stateText(c: CallInfo, isEnded: Boolean): String = when {
    isEnded || c.ended -> c.disconnect?.label?.toString()?.takeIf { it.isNotBlank() } ?: stringResource(R.string.state_ended)
    c.ringing -> stringResource(R.string.state_incoming)
    c.held -> stringResource(R.string.state_held)
    c.choosingSim -> stringResource(R.string.state_choose_sim)
    c.outgoing -> stringResource(R.string.state_calling)
    else -> stringResource(R.string.state_ongoing)
}

@Composable
private fun CallTimer(connectedAt: Long) {
    // connectTimeMillis is wall-clock time; the timer ticks every second from it.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - SystemClock.uptimeMillis() % 1000)
        }
    }
    Text(formatDuration(((now - connectedAt) / 1000).coerceAtLeast(0)), style = CtrlType.Readout.copy(fontSize = CtrlType.Readout.fontSize * 0.6f), color = CtrlColors.Text)
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun Incoming(main: CallInfo, onReplies: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onReplies) {
            Icon(Icons.Outlined.Message, null, Modifier.size(18.dp), tint = CtrlColors.AccentText)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.reply_with_message), color = CtrlColors.AccentText)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BigButton(Icons.Outlined.CallEnd, stringResource(R.string.decline), CtrlColors.Error, CtrlColors.OnError) { CallManager.decline(main.call) }
            BigButton(Icons.Outlined.Call, stringResource(R.string.answer), CtrlColors.Accent, CtrlColors.OnAccent) { CallManager.answer(main.call) }
        }
    }
}

@Composable
private fun BigButton(icon: ImageVector, label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(84.dp).clip(CtrlShapes.Hero).background(bg).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, Modifier.size(34.dp), tint = fg) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = CtrlColors.Text)
    }
}

@Composable
private fun EndButton(onClick: () -> Unit) {
    val label = stringResource(R.string.end_call)
    Box(
        Modifier.size(width = 120.dp, height = 68.dp).clip(CtrlShapes.Hero).background(CtrlColors.Error)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.CallEnd, label, Modifier.size(32.dp), tint = CtrlColors.OnError) }
}

@Composable
private fun Controls(main: CallInfo, audio: CallAudioState?, hasOther: Boolean, onKeypad: () -> Unit, onAudio: () -> Unit, onAddCall: () -> Unit) {
    val muted = audio?.isMuted == true
    val route = audio?.route ?: CallAudioState.ROUTE_EARPIECE
    val (routeIcon, routeLabel) = when (route) {
        CallAudioState.ROUTE_BLUETOOTH -> Icons.Outlined.Bluetooth to stringResource(R.string.route_bluetooth)
        CallAudioState.ROUTE_WIRED_HEADSET -> Icons.Outlined.Headphones to stringResource(R.string.route_headset)
        CallAudioState.ROUTE_SPEAKER -> Icons.Outlined.VolumeUp to stringResource(R.string.route_speaker)
        else -> Icons.Outlined.VolumeUp to stringResource(R.string.route_speaker)
    }
    val speakerOn = route == CallAudioState.ROUTE_SPEAKER || route == CallAudioState.ROUTE_BLUETOOTH || route == CallAudioState.ROUTE_WIRED_HEADSET
    Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Control(if (muted) Icons.Outlined.MicOff else Icons.Outlined.Mic, stringResource(if (muted) R.string.unmute else R.string.mute), on = muted, Modifier.weight(1f)) {
                CallManager.setMuted(!muted)
            }
            Control(Icons.Outlined.Dialpad, stringResource(R.string.keypad), on = false, Modifier.weight(1f), onClick = onKeypad)
            Control(routeIcon, routeLabel, on = speakerOn, Modifier.weight(1f), onClick = onAudio)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val held = main.held
            Control(
                if (held) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                stringResource(if (held) R.string.resume else R.string.hold),
                on = held, Modifier.weight(1f), enabled = main.canHold || held,
            ) { if (held) CallManager.unhold(main.call) else CallManager.hold(main.call) }
            if (hasOther) {
                Control(Icons.Outlined.SwapCalls, stringResource(R.string.swap), on = false, Modifier.weight(1f)) { CallManager.swap() }
                Control(Icons.Outlined.CallMerge, stringResource(R.string.merge), on = false, Modifier.weight(1f), enabled = main.canMerge) { CallManager.merge() }
            } else {
                Control(Icons.Outlined.AddIcCall, stringResource(R.string.add_call), on = false, Modifier.weight(1f), enabled = main.active, onClick = onAddCall)
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Control(icon: ImageVector, label: String, on: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (on) CtrlColors.Accent else CtrlColors.Surface
    val fg = when {
        !enabled -> CtrlColors.Inactive
        on -> CtrlColors.OnAccent
        else -> CtrlColors.Text
    }
    Column(
        modifier
            .heightIn(min = 76.dp)
            .clip(CtrlShapes.Button)
            .background(bg)
            .border(1.dp, if (on) bg else CtrlColors.Outline, CtrlShapes.Button)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = fg)
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

@Composable
private fun SecondCall(other: CallInfo) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(CtrlShapes.Card).background(CtrlColors.Surface).border(1.dp, CtrlColors.Outline, CtrlShapes.Card).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.PhoneInTalk, null, Modifier.size(22.dp), tint = if (other.ringing) CtrlColors.AccentText else CtrlColors.Muted)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(other.caller.name ?: other.number?.let { Phone.format(context, it) } ?: stringResource(R.string.unknown_caller), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(if (other.ringing) R.string.state_waiting else R.string.state_held), style = CtrlType.Data, color = CtrlColors.Muted)
        }
        if (other.ringing) {
            TextButton(onClick = { CallManager.decline(other.call) }) { Text(stringResource(R.string.decline), color = CtrlColors.Error) }
            TextButton(onClick = { CallManager.answer(other.call) }) { Text(stringResource(R.string.answer), color = CtrlColors.AccentText) }
        }
    }
}

/** More than one SIM and no default: pick the one for this call. */
@Composable
private fun SimChoice(main: CallInfo) {
    val context = LocalContext.current
    val accounts = remember { Phone.callAccounts(context) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        accounts.forEach { a ->
            Row(
                Modifier.fillMaxWidth().clip(CtrlShapes.Card).background(CtrlColors.Surface).border(1.dp, CtrlColors.Outline, CtrlShapes.Card)
                    .clickable { CallManager.simChosen(main.call, a.handle) }.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.SimCard, null, Modifier.size(22.dp), tint = Color(a.color).takeIf { a.color != 0 } ?: CtrlColors.Text)
                Spacer(Modifier.width(14.dp))
                Text(a.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(6f), contentAlignment = Alignment.Center) {
            EndButton { CallManager.hangUp(main.call) }
        }
    }
}

@Composable
private fun QuickReplies(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    val canned = listOf(
        stringResource(R.string.reply_1),
        stringResource(R.string.reply_2),
        stringResource(R.string.reply_3),
    )
    CtrlBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(stringResource(R.string.reply_with_message), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            canned.forEach { r -> GroupRow(r, onClick = { onSend(r) }) }
            GroupRow(stringResource(R.string.reply_custom), onClick = { custom = true })
        }
    }
    if (custom) {
        NameDialog(stringResource(R.string.reply_custom), "", confirm = stringResource(R.string.send), onDismiss = { custom = false }) { text ->
            custom = false
            if (text.isNotBlank()) onSend(text)
        }
    }
}

@Composable
private fun RouteSheet(audio: CallAudioState?, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val mask = audio?.supportedRouteMask ?: 0
    val current = audio?.route
    val options = buildList {
        if ((mask and CallAudioState.ROUTE_EARPIECE) != 0) add(Triple(CallAudioState.ROUTE_EARPIECE, Icons.Outlined.PhoneInTalk, R.string.route_phone))
        if ((mask and CallAudioState.ROUTE_WIRED_HEADSET) != 0) add(Triple(CallAudioState.ROUTE_WIRED_HEADSET, Icons.Outlined.Headphones, R.string.route_headset))
        add(Triple(CallAudioState.ROUTE_SPEAKER, Icons.Outlined.VolumeUp, R.string.route_speaker))
        if ((mask and CallAudioState.ROUTE_BLUETOOTH) != 0) add(Triple(CallAudioState.ROUTE_BLUETOOTH, Icons.Outlined.Bluetooth, R.string.route_bluetooth))
    }
    CtrlBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            options.forEach { (route, icon, label) ->
                GroupRow(
                    stringResource(label) + if (route == CallAudioState.ROUTE_BLUETOOTH) audio?.activeBluetoothDevice?.let { d -> runCatching { " · " + d.name }.getOrDefault("") }.orEmpty() else "",
                    icon = icon,
                    iconTint = if (route == current) CtrlColors.AccentText else CtrlColors.Text,
                    titleColor = if (route == current) CtrlColors.AccentText else CtrlColors.Text,
                    onClick = { onPick(route) },
                )
            }
        }
    }
}
