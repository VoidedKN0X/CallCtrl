package com.vknxworks.callctrl.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The kinds of call a history export names. */
enum class ExportType { INCOMING, OUTGOING, MISSED, DECLINED, BLOCKED, VOICEMAIL }

/** One line of a call-history export. */
data class ExportCall(
    val date: Long,
    val type: ExportType,
    /** Empty for a hidden number. */
    val number: String,
    val name: String,
    val durationSeconds: Long,
    val sim: String,
    val note: String,
    val tags: List<String>,
)

/**
 * The call history as a CSV file for a spreadsheet. Pure Kotlin, unit-tested.
 *
 * Every cell is quoted, and a cell that starts like a formula (= + - @, or a tab or line break) gets a leading
 * apostrophe, so a spreadsheet never runs anything a caller's name or your own note happens to contain.
 */
object HistoryExport {
    const val HEADER = "date,time,type,number,name,duration_seconds,sim,note,tags"

    fun typeName(type: ExportType): String = when (type) {
        ExportType.INCOMING -> "incoming"
        ExportType.OUTGOING -> "outgoing"
        ExportType.MISSED -> "missed"
        ExportType.DECLINED -> "declined"
        ExportType.BLOCKED -> "blocked"
        ExportType.VOICEMAIL -> "voicemail"
    }

    fun cell(value: String): String {
        val flat = value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        val guarded = if (flat.isNotEmpty() && (flat[0] == '=' || flat[0] == '+' || flat[0] == '-' || flat[0] == '@' || flat[0] == '\t')) "'$flat" else flat
        return "\"" + guarded.replace("\"", "\"\"") + "\""
    }

    /** [calls] in the order they should be written (newest first, as in Recents). */
    fun csv(calls: List<ExportCall>): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        val out = StringBuilder(calls.size * 80 + 64)
        out.append(HEADER).append("\r\n")
        for (c in calls) {
            val date = Date(c.date)
            out.append(day.format(date)).append(',')
            out.append(time.format(date)).append(',')
            out.append(typeName(c.type)).append(',')
            out.append(cell(c.number)).append(',')
            out.append(cell(c.name)).append(',')
            out.append(c.durationSeconds.coerceAtLeast(0)).append(',')
            out.append(cell(c.sim)).append(',')
            out.append(cell(c.note)).append(',')
            out.append(cell(c.tags.joinToString(" ")))
            out.append("\r\n")
        }
        return out.toString()
    }

    fun fileName(now: Long): String = "CallCtrl-history-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(now)) + ".csv"
}
