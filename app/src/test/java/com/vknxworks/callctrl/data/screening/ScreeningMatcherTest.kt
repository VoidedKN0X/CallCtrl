package com.vknxworks.callctrl.data.screening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreeningMatcherTest {
    private fun cfg(vararg rules: ScreenRule, list: List<ListedNumber> = emptyList(), contactsAlwaysRing: Boolean = true) =
        ScreeningConfig(enabled = true, contactsAlwaysRing = contactsAlwaysRing, rules = rules.toList(), list = list)

    private fun call(number: String?, contact: Boolean = false, emergency: Boolean = false) =
        IncomingCall(number, hidden = number.isNullOrBlank(), isContact = contact, isEmergency = emergency)

    /** Belgian test forms: +32 4xx ↔ 04xx, like Android's E.164 / national formatting would give. */
    private val beForms: (String) -> Set<String> = { n ->
        val norm = ScreeningMatcher.normalize(n)
        when {
            norm.startsWith("+32") -> setOf(norm, "0" + norm.removePrefix("+32"))
            norm.startsWith("0") && !norm.startsWith("00") -> setOf(norm, "+32" + norm.removePrefix("0"))
            else -> setOf(norm)
        }.filter { it.isNotEmpty() }.toSet()
    }

    @Test fun normalizeKeepsPlusAndDigits() {
        assertEquals("+3247012345", ScreeningMatcher.normalize(" +32 (470) 12-34.5 "))
        assertEquals("047012345", ScreeningMatcher.normalize("0470 12 34 5"))
        assertEquals("", ScreeningMatcher.normalize(null))
        assertEquals("", ScreeningMatcher.normalize("+"))
    }

    @Test fun offMeansEverythingRings() {
        val c = ScreeningConfig(enabled = false, rules = listOf(ScreenRule(RuleKind.HIDDEN)))
        assertEquals(ScreenAction.ALLOW, ScreeningMatcher.decide(c, call(null)).action)
    }

    @Test fun noRulesAllows() {
        val d = ScreeningMatcher.decide(cfg(), call("+3247012345"))
        assertEquals(ScreenAction.ALLOW, d.action)
        assertEquals(Reason.NONE, d.reason)
    }

    @Test fun prefixMatches() {
        val c = cfg(ScreenRule(RuleKind.PREFIX, "+44", ScreenAction.REJECT))
        assertEquals(ScreenAction.REJECT, ScreeningMatcher.decide(c, call("+44 20 7946 0000")).action)
        assertEquals(ScreenAction.ALLOW, ScreeningMatcher.decide(c, call("+32 470 12 34 56")).action)
        // "+4" is not "+44"
        assertEquals(ScreenAction.ALLOW, ScreeningMatcher.decide(c, call("+4")).action)
    }

    @Test fun prefixMatchesNationalAndInternationalForms() {
        val c = cfg(ScreenRule(RuleKind.PREFIX, "0900", ScreenAction.SILENCE))
        assertEquals(ScreenAction.SILENCE, ScreeningMatcher.decide(c, call("+32 900 12 345"), beForms).action)
        val d = cfg(ScreenRule(RuleKind.PREFIX, "+32900", ScreenAction.SILENCE))
        assertEquals(ScreenAction.SILENCE, ScreeningMatcher.decide(d, call("0900 12 345"), beForms).action)
    }

    @Test fun exactMatchesAcrossFormatting() {
        val c = cfg(ScreenRule(RuleKind.EXACT, "0470 12 34 56", ScreenAction.REJECT))
        assertEquals(ScreenAction.REJECT, ScreeningMatcher.decide(c, call("+32470123456"), beForms).action)
        assertEquals(ScreenAction.ALLOW, ScreeningMatcher.decide(c, call("+32470123457"), beForms).action)
    }

    @Test fun patternMatches() {
        assertTrue(ScreeningMatcher.globMatches("+324??12*", "+3247012"))
        assertTrue(ScreeningMatcher.globMatches("+324??12*", "+32470123456"))
        assertFalse(ScreeningMatcher.globMatches("+324??12*", "+3247013"))
        assertTrue(ScreeningMatcher.globMatches("*999", "0800999"))
        assertTrue(ScreeningMatcher.globMatches("*", "123"))
        assertFalse(ScreeningMatcher.globMatches("12?", "12"))
        // * and ? stand for digits only, never for the +
        assertFalse(ScreeningMatcher.globMatches("*32", "+32"))
        assertFalse(ScreeningMatcher.globMatches("?32", "+32"))
        val c = cfg(ScreenRule(RuleKind.PATTERN, "+32 4?? 12 *", ScreenAction.SILENCE))
        assertEquals(ScreenAction.SILENCE, ScreeningMatcher.decide(c, call("+32 470 12 99 99")).action)
        assertEquals(ScreenAction.ALLOW, ScreeningMatcher.decide(c, call("+32 470 13 99 99")).action)
    }

    @Test fun patternStaysFastOnHostileInput() {
        val pattern = ScreeningMatcher.normalizePattern("*1*1*1*1*1*1*1*1*1*1*1*1*1*1*1*2")
        val number = "1".repeat(32)
        val start = System.nanoTime()
        repeat(1000) { assertFalse(ScreeningMatcher.globMatches(pattern, number)) }
        assertTrue("glob too slow", System.nanoTime() - start < 2_000_000_000L)
    }

    @Test fun normalizePatternCollapsesStarsAndDropsOthers() {
        assertEquals("+32*1?", ScreeningMatcher.normalizePattern("+32 ** - 1 ?"))
    }

    @Test fun hiddenRule() {
        val c = cfg(ScreenRule(RuleKind.HIDDEN, action = ScreenAction.REJECT))
        assertEquals(ScreenAction.REJECT, ScreeningMatcher.decide(c, call(null)).action)
        assertEquals(ScreenAction.REJECT, ScreeningMatcher.decide(c, call("")).action)
        assertEquals(ScreenAction.ALLOW, ScreeningMatcher.decide(c, call("+3247012345")).action)
    }

    @Test fun hiddenCallerWithANumberIsStillHidden() {
        val c = cfg(ScreenRule(RuleKind.HIDDEN, action = ScreenAction.REJECT), ScreenRule(RuleKind.PREFIX, "+32", ScreenAction.ALLOW))
        val d = ScreeningMatcher.decide(c, IncomingCall("+3247012345", hidden = true, isContact = false))
        // Number rules don't look at a withheld number, so only the hidden rule matches.
        assertEquals(ScreenAction.REJECT, d.action)
    }

    @Test fun notInContacts() {
        val c = cfg(ScreenRule(RuleKind.NOT_IN_CONTACTS, action = ScreenAction.SILENCE))
        assertEquals(ScreenAction.SILENCE, ScreeningMatcher.decide(c, call("+3247012345")).action)
        assertEquals(ScreenAction.SILENCE, ScreeningMatcher.decide(c, call(null)).action)
        assertEquals(ScreenAction.ALLOW, ScreeningMatcher.decide(c, call("+3247012345", contact = true)).action)
    }

    @Test fun contactsAlwaysRingSkipsRules() {
        val rule = ScreenRule(RuleKind.PREFIX, "+32", ScreenAction.REJECT)
        val d = ScreeningMatcher.decide(cfg(rule), call("+3247012345", contact = true))
        assertEquals(ScreenAction.ALLOW, d.action)
        assertEquals(Reason.CONTACT, d.reason)
        val off = ScreeningMatcher.decide(cfg(rule, contactsAlwaysRing = false), call("+3247012345", contact = true))
        assertEquals(ScreenAction.REJECT, off.action)
    }

    @Test fun allowWinsThenRejectThenSilence() {
        val silence = ScreenRule(RuleKind.NOT_IN_CONTACTS, action = ScreenAction.SILENCE)
        val reject = ScreenRule(RuleKind.PREFIX, "+44", ScreenAction.REJECT)
        val allow = ScreenRule(RuleKind.EXACT, "+44 20 7946 0000", ScreenAction.ALLOW)
        assertEquals(ScreenAction.REJECT, ScreeningMatcher.decide(cfg(silence, reject), call("+442079461111")).action)
        assertEquals(ScreenAction.ALLOW, ScreeningMatcher.decide(cfg(silence, reject, allow), call("+442079460000")).action)
        assertEquals(ScreenAction.SILENCE, ScreeningMatcher.decide(cfg(silence, reject, allow), call("+3247012345")).action)
        val d = ScreeningMatcher.decide(cfg(silence, reject), call("+442079461111"))
        assertEquals(reject, d.rule)
    }

    @Test fun disabledRulesDontMatch() {
        val c = cfg(ScreenRule(RuleKind.HIDDEN, action = ScreenAction.REJECT, enabled = false))
        assertEquals(ScreenAction.ALLOW, ScreeningMatcher.decide(c, call(null)).action)
    }

    @Test fun callersListWinsOverContactsAndRules() {
        val c = cfg(
            ScreenRule(RuleKind.PREFIX, "+32", ScreenAction.ALLOW),
            list = listOf(ListedNumber("0470 12 34 56", ScreenAction.SILENCE)),
        )
        val d = ScreeningMatcher.decide(c, call("+32470123456", contact = true), beForms)
        assertEquals(ScreenAction.SILENCE, d.action)
        assertEquals(Reason.LIST, d.reason)
        assertEquals(ScreenAction.ALLOW, ScreeningMatcher.decide(c, call("+32470123457"), beForms).action)
    }

    @Test fun emergencyNumbersAreNeverScreened() {
        val c = cfg(
            ScreenRule(RuleKind.NOT_IN_CONTACTS, action = ScreenAction.REJECT),
            ScreenRule(RuleKind.PATTERN, "*", ScreenAction.REJECT),
            list = listOf(ListedNumber("112", ScreenAction.REJECT)),
        )
        for (n in listOf("112", "911", "999", "1 1 2")) {
            val d = ScreeningMatcher.decide(c, call(n))
            assertEquals(n, ScreenAction.ALLOW, d.action)
            assertEquals(Reason.EMERGENCY, d.reason)
        }
        // Android's own check (for the SIM's country) counts too.
        val d = ScreeningMatcher.decide(c, call("+3247012345", emergency = true))
        assertEquals(Reason.EMERGENCY, d.reason)
        // An international number ending in 112 is not an emergency number.
        assertEquals(ScreenAction.REJECT, ScreeningMatcher.decide(c, call("+44112")).action)
    }

    @Test fun validation() {
        assertNull(ScreeningMatcher.validate(RuleKind.PREFIX, "+44"))
        assertNull(ScreeningMatcher.validate(RuleKind.EXACT, "+32 (470) 12-34-56"))
        assertNull(ScreeningMatcher.validate(RuleKind.PATTERN, "+32 4?? *"))
        assertNull(ScreeningMatcher.validate(RuleKind.HIDDEN, ""))
        assertEquals(ValueError.EMPTY, ScreeningMatcher.validate(RuleKind.PREFIX, "  "))
        assertEquals(ValueError.CHARACTERS, ScreeningMatcher.validate(RuleKind.PREFIX, "+4*"))
        assertEquals(ValueError.CHARACTERS, ScreeningMatcher.validate(RuleKind.PATTERN, "(.*)+$"))
        assertEquals(ValueError.CHARACTERS, ScreeningMatcher.validate(RuleKind.EXACT, "3+2"))
        assertEquals(ValueError.CHARACTERS, ScreeningMatcher.validate(RuleKind.EXACT, "abc"))
        assertEquals(ValueError.NO_DIGITS, ScreeningMatcher.validate(RuleKind.PATTERN, "*"))
        assertEquals(ValueError.TOO_LONG, ScreeningMatcher.validate(RuleKind.EXACT, "1".repeat(33)))
    }
}
