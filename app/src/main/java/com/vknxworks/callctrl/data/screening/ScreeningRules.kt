package com.vknxworks.callctrl.data.screening

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Call screening rules and their matching. Pure Kotlin (no Android), so every decision is unit-tested.
 *
 * Order of decisions for an incoming call:
 * 1. Emergency numbers are never screened.
 * 2. A number on your own "unknown callers" list gets that list's action (reject or silence).
 * 3. Contacts always come through when "Contacts always ring" is on.
 * 4. Of the rules that match, Allow wins, then Reject, then Silence. No rule matches: the call rings.
 */

@Serializable
enum class ScreenAction {
    @SerialName("allow") ALLOW,
    @SerialName("silence") SILENCE,
    @SerialName("reject") REJECT,
}

@Serializable
enum class RuleKind {
    /** The number starts with [ScreenRule.value]. */
    @SerialName("prefix") PREFIX,
    /** The number is [ScreenRule.value]. */
    @SerialName("exact") EXACT,
    /** The number matches the simple pattern in [ScreenRule.value] (`?` one digit, `*` any digits). */
    @SerialName("pattern") PATTERN,
    /** The caller isn't in your contacts (a hidden number counts as not in contacts). */
    @SerialName("not_in_contacts") NOT_IN_CONTACTS,
    /** The caller hid their number (withheld, private, unknown or payphone). */
    @SerialName("hidden") HIDDEN,
    ;

    val needsValue: Boolean get() = this == PREFIX || this == EXACT || this == PATTERN
}

@Serializable
data class ScreenRule(
    val kind: RuleKind,
    val value: String = "",
    val action: ScreenAction = ScreenAction.REJECT,
    val enabled: Boolean = true,
)

/** A number on your own list of callers to always reject or silence. */
@Serializable
data class ListedNumber(
    val number: String,
    val action: ScreenAction = ScreenAction.REJECT,
)

@Serializable
data class ScreeningConfig(
    /** Call screening is on. Off by default: nothing is screened until you turn it on. */
    val enabled: Boolean = false,
    /** Calls from your contacts always ring, whatever the rules say. */
    val contactsAlwaysRing: Boolean = true,
    /** Rejected calls are kept in the call history as blocked calls. */
    val logRejected: Boolean = true,
    /** A quiet notification when a call was rejected. */
    val notifyRejected: Boolean = false,
    val rules: List<ScreenRule> = emptyList(),
    val list: List<ListedNumber> = emptyList(),
)

/** What CallCtrl knows about an incoming call when it decides. */
data class IncomingCall(
    /** The number as Android gave it; null or blank when the caller hid it. */
    val number: String?,
    /** The caller hid their number (or the network didn't send one). */
    val hidden: Boolean,
    /** The number belongs to one of your contacts. */
    val isContact: Boolean,
    /** The number is an emergency number for this phone (checked by Android's telephony). */
    val isEmergency: Boolean = false,
)

/** Why a call got its action, for the notification and tests. */
enum class Reason { NONE, EMERGENCY, CONTACT, LIST, RULE }

data class Decision(val action: ScreenAction, val reason: Reason, val rule: ScreenRule? = null) {
    val screened: Boolean get() = action != ScreenAction.ALLOW
}

object ScreeningMatcher {
    const val MAX_VALUE_LENGTH = 32

    /**
     * Pure fallback list of emergency numbers, used next to Android's own check (which knows the numbers for the
     * SIM's country). Never screened either way.
     */
    val FALLBACK_EMERGENCY = setOf("112", "911", "999", "000", "08", "110", "118", "119", "100", "101", "102", "108", "15", "17", "18", "997", "998")

    /** Keeps a leading + and the digits; drops spaces, dashes, dots, brackets and anything else. */
    fun normalize(number: String?): String {
        if (number.isNullOrBlank()) return ""
        val t = number.trim()
        val digits = t.filter { it in '0'..'9' }
        return if (t.startsWith("+") && digits.isNotEmpty()) "+$digits" else digits
    }

    fun isFallbackEmergency(number: String?): Boolean {
        val n = normalize(number)
        return n.isNotEmpty() && !n.startsWith("+") && n in FALLBACK_EMERGENCY
    }

    /** Normalised pattern: keeps a leading +, digits, `?` and `*` (collapses runs of `*`). */
    fun normalizePattern(pattern: String): String {
        val t = pattern.trim()
        val sb = StringBuilder()
        if (t.startsWith("+")) sb.append('+')
        for (c in t) {
            when {
                c in '0'..'9' || c == '?' -> sb.append(c)
                c == '*' -> if (sb.isEmpty() || sb.last() != '*') sb.append('*')
            }
        }
        return sb.toString()
    }

    /** Error for a rule value, or null when it's fine. */
    fun validate(kind: RuleKind, value: String): ValueError? {
        if (!kind.needsValue) return null
        val v = value.trim()
        if (v.isEmpty()) return ValueError.EMPTY
        if (v.length > MAX_VALUE_LENGTH * 2) return ValueError.TOO_LONG
        val allowed = when (kind) {
            RuleKind.PATTERN -> { c: Char -> c in '0'..'9' || c == '?' || c == '*' || c in " -.()/" }
            else -> { c: Char -> c in '0'..'9' || c in " -.()/" }
        }
        val body = if (v.startsWith("+")) v.substring(1) else v
        if (!body.all(allowed)) return ValueError.CHARACTERS
        val n = if (kind == RuleKind.PATTERN) normalizePattern(v) else normalize(v)
        if (n.length > MAX_VALUE_LENGTH) return ValueError.TOO_LONG
        if (n.none { it in '0'..'9' }) return ValueError.NO_DIGITS
        return null
    }

    /**
     * Glob match without regular expressions: `?` is one digit, `*` any number of digits (also none). Greedy with
     * one backtrack point, so it runs in O(pattern × number) at worst on strings of at most 32 characters: no
     * pattern can make it slow.
     */
    fun globMatches(pattern: String, number: String): Boolean {
        var p = 0
        var n = 0
        var star = -1
        var mark = 0
        while (n < number.length) {
            if (p < pattern.length && (pattern[p] == number[n] || (pattern[p] == '?' && number[n] in '0'..'9'))) {
                p++; n++
            } else if (p < pattern.length && pattern[p] == '*') {
                star = p++; mark = n
            } else if (star >= 0 && number[mark] in '0'..'9') {
                p = star + 1; n = ++mark
            } else {
                return false
            }
        }
        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }

    /** Whether [rule] matches [call]. [forms] are the forms of the number to compare (as given, international, national). */
    fun ruleMatches(rule: ScreenRule, call: IncomingCall, forms: Set<String>): Boolean {
        if (!rule.enabled) return false
        return when (rule.kind) {
            RuleKind.HIDDEN -> call.hidden
            RuleKind.NOT_IN_CONTACTS -> !call.isContact
            RuleKind.PREFIX -> {
                val p = normalize(rule.value)
                p.isNotEmpty() && !call.hidden && forms.any { it.length >= p.length && it.startsWith(p) }
            }
            RuleKind.EXACT -> {
                val p = normalize(rule.value)
                p.isNotEmpty() && !call.hidden && p in forms
            }
            RuleKind.PATTERN -> {
                val p = normalizePattern(rule.value)
                p.any { it in '0'..'9' } && !call.hidden && forms.any { globMatches(p, it) }
            }
        }
    }

    /**
     * Decides what happens to [call]. [forms] turns a number into the forms to compare (normalised as given, plus
     * the international and national forms Android works out for your country); the default only normalises.
     */
    fun decide(
        config: ScreeningConfig,
        call: IncomingCall,
        forms: (String) -> Set<String> = { setOf(normalize(it)).filter { s -> s.isNotEmpty() }.toSet() },
    ): Decision {
        if (!config.enabled) return Decision(ScreenAction.ALLOW, Reason.NONE)
        if (call.isEmergency || isFallbackEmergency(call.number)) return Decision(ScreenAction.ALLOW, Reason.EMERGENCY)
        val callForms = if (call.hidden || call.number.isNullOrBlank()) emptySet() else forms(call.number)
        if (callForms.isNotEmpty()) {
            val listed = config.list.firstOrNull { entry -> forms(entry.number).any { it in callForms } }
            if (listed != null && listed.action != ScreenAction.ALLOW) return Decision(listed.action, Reason.LIST)
        }
        if (call.isContact && config.contactsAlwaysRing) return Decision(ScreenAction.ALLOW, Reason.CONTACT)
        val matching = config.rules.filter { ruleMatches(it, call, callForms) }
        val winner = matching.firstOrNull { it.action == ScreenAction.ALLOW }
            ?: matching.firstOrNull { it.action == ScreenAction.REJECT }
            ?: matching.firstOrNull { it.action == ScreenAction.SILENCE }
            ?: return Decision(ScreenAction.ALLOW, Reason.NONE)
        return Decision(winner.action, Reason.RULE, winner)
    }
}

enum class ValueError { EMPTY, CHARACTERS, NO_DIGITS, TOO_LONG }
