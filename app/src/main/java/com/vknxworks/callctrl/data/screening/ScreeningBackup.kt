package com.vknxworks.callctrl.data.screening

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The JSON file CallCtrl exports: every screening rule, your callers list and the phone's blocked numbers. */
@Serializable
data class ScreeningExport(
    val app: String = "CallCtrl",
    val format: Int = 1,
    val screening: ScreeningConfig = ScreeningConfig(),
    val blocked: List<String> = emptyList(),
)

/** What an imported file holds, after checking every line. */
data class ImportedRules(
    val rules: List<ScreenRule> = emptyList(),
    val list: List<ListedNumber> = emptyList(),
    val blocked: List<String> = emptyList(),
    /** Lines or entries that were left out because they weren't valid. */
    val skipped: Int = 0,
) {
    val isEmpty: Boolean get() = rules.isEmpty() && list.isEmpty() && blocked.isEmpty()
}

class ImportException(message: String) : Exception(message)

/**
 * Export and import of the screening rules as JSON (everything) or CSV (`type,value,action` per line, readable in a
 * spreadsheet). Imports are size-limited and every value is checked like one typed in the app.
 */
object ScreeningBackup {
    const val MAX_FILE_BYTES = 1_000_000
    const val MAX_RULES = 1_000
    const val MAX_NUMBERS = 10_000
    const val CSV_HEADER = "type,value,action"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        explicitNulls = false
    }

    fun toJson(config: ScreeningConfig, blocked: List<String>): String =
        json.encodeToString(ScreeningExport.serializer(), ScreeningExport(screening = config, blocked = blocked))

    fun toCsv(config: ScreeningConfig, blocked: List<String>): String = buildString {
        append(CSV_HEADER).append('\n')
        config.rules.forEach { r -> append(csvType(r.kind)).append(',').append(csvCell(r.value)).append(',').append(actionName(r.action)).append('\n') }
        config.list.forEach { e -> append("list,").append(csvCell(e.number)).append(',').append(actionName(e.action)).append('\n') }
        blocked.forEach { n -> append("blocked,").append(csvCell(n)).append(",\n") }
    }

    /** Reads a JSON or CSV export (from CallCtrl, or a plain list of numbers to block, one per line). */
    fun parse(text: String): ImportedRules {
        if (text.length > MAX_FILE_BYTES) throw ImportException("too large")
        val t = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return if (t.startsWith("{")) parseJson(t) else parseCsv(t)
    }

    fun parseJson(text: String): ImportedRules {
        val export = try {
            json.decodeFromString(ScreeningExport.serializer(), text)
        } catch (e: Exception) {
            throw ImportException("not a CallCtrl screening file")
        }
        var skipped = 0
        val rules = export.screening.rules.mapNotNull { r ->
            val clean = cleanRule(r)
            if (clean == null) skipped++
            clean
        }
        val list = export.screening.list.mapNotNull { e ->
            val clean = cleanListed(e.number, e.action)
            if (clean == null) skipped++
            clean
        }
        val blocked = export.blocked.mapNotNull { n ->
            val clean = cleanNumber(n)
            if (clean == null) skipped++
            clean
        }
        return limit(ImportedRules(rules, list, blocked, skipped))
    }

    fun parseCsv(text: String): ImportedRules {
        val rules = ArrayList<ScreenRule>()
        val list = ArrayList<ListedNumber>()
        val blocked = ArrayList<String>()
        var skipped = 0
        text.lineSequence().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val cells = line.split(',', ';', '\t').map { it.trim().removeSurrounding("\"").trim() }
            if (i == 0 && cells.firstOrNull()?.lowercase() == "type") return@forEachIndexed
            val type = cells[0].lowercase()
            val value = cells.getOrElse(1) { "" }
            val action = parseAction(cells.getOrElse(2) { "" })
            when {
                type == "blocked" -> cleanNumber(value)?.let { blocked += it } ?: skipped++
                type == "list" -> cleanListed(value, action ?: ScreenAction.REJECT)?.let { list += it } ?: skipped++
                kindOf(type) != null -> {
                    val r = cleanRule(ScreenRule(kindOf(type)!!, value, action ?: ScreenAction.REJECT))
                    if (r != null) rules += r else skipped++
                }
                // A plain list of numbers: each one is blocked.
                cells.size == 1 -> cleanNumber(cells[0])?.let { blocked += it } ?: skipped++
                else -> skipped++
            }
        }
        return limit(ImportedRules(rules, list, blocked, skipped))
    }

    /**
     * Adds imported rules and numbers to [config]. A rule or listed number you already have takes the imported
     * action; your switches (screening on, contacts always ring, history, notification) stay as they are.
     */
    fun merge(config: ScreeningConfig, imported: ImportedRules): ScreeningConfig {
        val rules = config.rules.toMutableList()
        for (r in imported.rules) {
            val i = rules.indexOfFirst { it.kind == r.kind && ruleKey(it) == ruleKey(r) }
            if (i >= 0) rules[i] = r else rules += r
        }
        val list = config.list.toMutableList()
        for (e in imported.list) {
            val key = ScreeningMatcher.normalize(e.number)
            val i = list.indexOfFirst { ScreeningMatcher.normalize(it.number) == key }
            if (i >= 0) list[i] = e else list += e
        }
        return config.copy(rules = rules.take(MAX_RULES), list = list.take(MAX_NUMBERS))
    }

    /** Two rules of the same kind with the same key are the same rule. */
    fun ruleKey(r: ScreenRule): String = when (r.kind) {
        RuleKind.PATTERN -> ScreeningMatcher.normalizePattern(r.value)
        RuleKind.PREFIX, RuleKind.EXACT -> ScreeningMatcher.normalize(r.value)
        else -> ""
    }

    private fun limit(r: ImportedRules): ImportedRules {
        val extra = (r.rules.size - MAX_RULES).coerceAtLeast(0) + (r.list.size - MAX_NUMBERS).coerceAtLeast(0) + (r.blocked.size - MAX_NUMBERS).coerceAtLeast(0)
        return ImportedRules(
            r.rules.distinctBy { it.kind to ruleKey(it) }.take(MAX_RULES),
            r.list.distinctBy { ScreeningMatcher.normalize(it.number) }.take(MAX_NUMBERS),
            r.blocked.distinctBy { ScreeningMatcher.normalize(it) }.take(MAX_NUMBERS),
            r.skipped + extra,
        )
    }

    private fun cleanRule(r: ScreenRule): ScreenRule? {
        if (ScreeningMatcher.validate(r.kind, r.value) != null) return null
        val value = if (r.kind.needsValue) r.value.trim() else ""
        return r.copy(value = value)
    }

    private fun cleanListed(number: String, action: ScreenAction): ListedNumber? {
        val n = cleanNumber(number) ?: return null
        // The list is for numbers to reject or silence; "allow" there means nothing, so it becomes silence.
        return ListedNumber(n, if (action == ScreenAction.ALLOW) ScreenAction.SILENCE else action)
    }

    /** A phone number as typed (kept readable), or null when it isn't one. */
    fun cleanNumber(number: String): String? {
        val v = number.trim()
        if (ScreeningMatcher.validate(RuleKind.EXACT, v) != null) return null
        return v
    }

    fun parseAction(s: String): ScreenAction? = when (s.trim().lowercase()) {
        "allow" -> ScreenAction.ALLOW
        "silence", "silent", "mute" -> ScreenAction.SILENCE
        "reject", "block", "decline" -> ScreenAction.REJECT
        else -> null
    }

    private fun actionName(a: ScreenAction) = when (a) {
        ScreenAction.ALLOW -> "allow"
        ScreenAction.SILENCE -> "silence"
        ScreenAction.REJECT -> "reject"
    }

    private fun csvType(k: RuleKind) = when (k) {
        RuleKind.PREFIX -> "prefix"
        RuleKind.EXACT -> "exact"
        RuleKind.PATTERN -> "pattern"
        RuleKind.NOT_IN_CONTACTS -> "not_in_contacts"
        RuleKind.HIDDEN -> "hidden"
    }

    private fun kindOf(type: String): RuleKind? = when (type) {
        "prefix" -> RuleKind.PREFIX
        "exact", "number" -> RuleKind.EXACT
        "pattern" -> RuleKind.PATTERN
        "not_in_contacts", "unknown" -> RuleKind.NOT_IN_CONTACTS
        "hidden", "private", "withheld" -> RuleKind.HIDDEN
        else -> null
    }

    /** Numbers and patterns never hold commas or quotes; anything that would break the CSV is dropped. */
    private fun csvCell(s: String) = s.filterNot { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ';' || it == '\t' }
}
