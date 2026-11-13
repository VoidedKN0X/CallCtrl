package com.vknxworks.callctrl.ui.recordings

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.callctrl.R
import com.vknxworks.callctrl.calls.formatDuration
import com.vknxworks.callctrl.container
import com.vknxworks.callctrl.data.Phone
import com.vknxworks.callctrl.recording.CaptureMode
import com.vknxworks.callctrl.recording.RecordFormat
import com.vknxworks.callctrl.recording.Recording
import com.vknxworks.callctrl.ui.common.launch
import com.vknxworks.callctrl.ui.components.ConfirmDialog
import com.vknxworks.callctrl.ui.components.CtrlBottomSheet
import com.vknxworks.callctrl.ui.components.CtrlSquareButton
import com.vknxworks.callctrl.ui.components.GroupRow
import com.vknxworks.callctrl.ui.components.MessageView
import com.vknxworks.callctrl.ui.components.ScreenTopBar
import com.vknxworks.callctrl.ui.theme.CtrlColors
import com.vknxworks.callctrl.ui.theme.CtrlType
import com.vknxworks.callctrl.ui.theme.Gutter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings > Phone app > Call recording > Recordings (and from a recording notification): every recording,
 * newest first, with the parts of one call together. Behind the app lock like the rest of the app.
 */
@Composable
fun RecordingsScreen() {
    val context = LocalContext.current
    val c = context.container
    val list by c.recordings.list.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Recording?>(null) }
    LaunchedEffect(Unit) { c.recordings.reload() }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.recordings))
        if (list.isEmpty()) {
            MessageView(Icons.Outlined.RadioButtonChecked, stringResource(R.string.recordings_empty), stringResource(R.string.recordings_empty_body))
        } else {
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list, key = { it.key }) { r -> RecordingRow(r) { open = r } }
            }
        }
    }
    open?.let { r -> RecordingSheet(r, onDismiss = { open = null }) }
}

/** The caller's name, number or "Private number" / "Conference call". */
fun recordingTitle(context: Context, r: Recording): String = when {
    r.meta.direction == "conf" -> context.getString(R.string.rec_conference)
    r.meta.name != null -> r.meta.name
    r.meta.number != null -> Phone.format(context, r.meta.number).ifBlank { r.meta.number }
    else -> context.getString(R.string.private_number)
}

@Composable
private fun RecordingRow(r: Recording, onClick: () -> Unit) {
    val context = LocalContext.current
    val m = r.meta
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 68.dp).padding(horizontal = Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(directionIcon(m.direction), null, Modifier.size(20.dp), tint = CtrlColors.Muted)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                recordingTitle(context, r) + if (m.part > 1) " · " + context.getString(R.string.rec_part, m.part) else "",
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(
                    DateUtils.formatDateTime(context, m.startedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL),
                    m.format,
                ).joinToString(" · "),
                style = CtrlType.Data, color = CtrlColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (m.durationMs > 0) Text(formatDuration(m.durationMs / 1000), style = CtrlType.Data, color = CtrlColors.Muted)
    }
}

private fun directionIcon(d: String) = when (d) {
    "out" -> Icons.AutoMirrored.Outlined.CallMade
    "conf" -> Icons.Outlined.CallMerge
    else -> Icons.AutoMirrored.Outlined.CallReceived
}

/** "File: FLAC · 48 kHz · mono · 16-bit" and "Captured: 48 kHz · stereo · 32-bit float · call audio, both sides". */
fun recordingDetails(context: Context, r: Recording): List<String> {
    val m = r.meta
    fun khz(rate: Int) = context.getString(R.string.rec_khz, if (rate % 1000 == 0) (rate / 1000).toString() else "%.1f".format(rate / 1000.0))
    fun ch(n: Int) = context.getString(if (n == 2) R.string.rec_stereo else R.string.rec_mono)
    val out = ArrayList<String>()
    if (m.sampleRate > 0) {
        out += context.getString(R.string.rec_detail_file, listOf(m.format, khz(m.sampleRate), ch(m.channels), if (m.bits == 32) context.getString(R.string.rec_float) else context.getString(R.string.rec_bits, m.bits)).joinToString(" · "))
    }
    if (m.captureRate > 0) {
        val source = when (CaptureMode.of(m.capture)) {
            CaptureMode.SPLIT -> R.string.rec_source_split
            CaptureMode.DOWNLINK_ONLY -> R.string.rec_source_down
            CaptureMode.UPLINK_ONLY -> R.string.rec_source_up
            else -> R.string.rec_source_call
        }
        out += context.getString(
            R.string.rec_detail_capture,
            listOf(khz(m.captureRate), ch(m.captureChannels), if (m.captureFloat) context.getString(R.string.rec_float) else context.getString(R.string.rec_bits, 16), context.getString(source)).joinToString(" · "),
        )
    }
    when (m.endReason) {
        "shizuku" -> out += context.getString(R.string.rec_ended_shizuku)
        "storage" -> out += context.getString(R.string.rec_ended_storage)
        "error" -> out += context.getString(R.string.rec_ended_error)
    }
    return out
}

/** A recording: play with seek, details, share, save a copy, delete. */
@Composable
fun RecordingSheet(r: Recording, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    CtrlBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(recordingTitle(context, r), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    DateUtils.formatDateTime(context, r.meta.startedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL) +
                        if (r.meta.part > 1) " · " + context.getString(R.string.rec_part, r.meta.part) else "",
                    style = CtrlType.Data, color = CtrlColors.Muted,
                )
                Spacer(Modifier.size(12.dp))
                Player(r)
                Spacer(Modifier.size(8.dp))
                recordingDetails(context, r).forEach { Text(it, style = CtrlType.Data, color = CtrlColors.Muted) }
            }
            GroupRow(stringResource(R.string.rec_share), stringResource(R.string.rec_share_note), icon = Icons.Outlined.Share, onClick = { share(context, r) })
            if (!r.meta.copied) {
                GroupRow(stringResource(R.string.rec_save_copy), icon = Icons.Outlined.LibraryMusic, onClick = {
                    scope.launch {
                        val ok = c.recordings.copyToMusic(r)
                        Toast.makeText(context, context.getString(if (ok) R.string.rec_saved_copy else R.string.rec_save_failed), Toast.LENGTH_SHORT).show()
                    }
                })
            }
            GroupRow(stringResource(R.string.rec_delete), icon = Icons.Outlined.Delete, iconTint = CtrlColors.Error, titleColor = CtrlColors.Error, onClick = { confirmDelete = true })
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.rec_delete_title),
            body = stringResource(R.string.rec_delete_body),
            confirm = stringResource(R.string.rec_delete),
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDismiss()
                c.appScope.launch { c.recordings.delete(listOf(r)) }
            },
        )
    }
}

/** Play / pause and a seek bar, with Android's own MediaPlayer (no extra library). */
@Composable
private fun Player(r: Recording) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf((r.meta.durationMs).toInt().coerceAtLeast(0)) }
    var position by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val player = remember(r.key) {
        runCatching {
            MediaPlayer().apply {
                setDataSource(r.file.path)
                prepare()
                setOnCompletionListener { playing = false; position = 0; runCatching { seekTo(0) } }
            }
        }.getOrNull()
    }
    DisposableEffect(player) {
        player?.duration?.takeIf { it > 0 }?.let { duration = it }
        onDispose { runCatching { player?.release() } }
    }
    LaunchedEffect(playing) {
        while (playing) {
            if (!dragging) position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            delay(200)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CtrlSquareButton(
            if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            stringResource(if (playing) R.string.rec_pause else R.string.rec_play),
            onClick = {
                val p = player
                if (p == null) {
                    Toast.makeText(context, context.getString(R.string.rec_play_failed), Toast.LENGTH_SHORT).show()
                } else if (playing) {
                    runCatching { p.pause() }
                    playing = false
                } else {
                    runCatching { p.start() }
                    playing = true
                }
            },
            tint = CtrlColors.AccentText,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Slider(
                value = if (dragging) dragValue else if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    val to = (dragValue * duration).toInt()
                    runCatching { player?.seekTo(to) }
                    position = to
                    dragging = false
                },
                enabled = player != null && duration > 0,
                colors = SliderDefaults.colors(thumbColor = CtrlColors.AccentText, activeTrackColor = CtrlColors.Accent, inactiveTrackColor = CtrlColors.ProgressTrack),
            )
            val shown = if (dragging) (dragValue * duration).toLong() else position.toLong()
            Text("${formatDuration(shown / 1000)} / ${formatDuration(duration / 1000L)}", style = CtrlType.Data, color = CtrlColors.Muted)
        }
    }
}

private fun share(context: Context, r: Recording) {
    val format = RecordFormat.entries.firstOrNull { r.file.name.endsWith("." + it.extension) } ?: RecordFormat.FLAC
    val uri = runCatching { FileProvider.getUriForFile(context, context.packageName + ".files", r.file) }.getOrNull() ?: return
    val send = Intent(Intent.ACTION_SEND)
        .setType(format.mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.launch(Intent.createChooser(send, context.getString(R.string.rec_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
