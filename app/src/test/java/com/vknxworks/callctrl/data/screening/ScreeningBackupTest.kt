package com.vknxworks.callctrl.data.screening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreeningBackupTest {
    private val config = ScreeningConfig(
        enabled = true,
        contactsAlwaysRing = false,
        logRejected = false,
        notifyRejected = true,
        rules = listOf(
            ScreenRule(RuleKind.PREFIX, "+44", ScreenAction.REJECT),
            ScreenRule(RuleKind.PATTERN, "+32 4?? *", ScreenAction.SILENCE),
            ScreenRule(RuleKind.EXACT, "+32 470 12 34 56", ScreenAction.ALLOW),
            ScreenRule(RuleKind.NOT_IN_CONTACTS, action = ScreenAction.SILENCE),
            ScreenRule(RuleKind.HIDDEN, action = ScreenAction.REJECT),
        ),
        list = listOf(ListedNumber("+32 2 555 00 00", ScreenAction.REJECT), ListedNumber("0800 11 22", ScreenAction.SILENCE)),
    )
    private val blocked = listOf("+3212345678", "0499 99 99 99")

    @Test fun jsonRoundTrip() {
        val r = ScreeningBackup.parse(ScreeningBackup.toJson(config, blocked))
        assertEquals(config.rules, r.rules)
        assertEquals(config.list, r.list)
        assertEquals(blocked, r.blocked)
        assertEquals(0, r.skipped)
    }

    @Test fun csvRoundTrip() {
        val csv = ScreeningBackup.toCsv(config, blocked)
        assertTrue(csv.startsWith(ScreeningBackup.CSV_HEADER))
        val r = ScreeningBackup.parse(csv)
        assertEquals(config.rules, r.rules)
        assertEquals(config.list, r.list)
        assertEquals(blocked, r.blocked)
    }

    @Test fun plainNumberListIsBlocked() {
        val r = ScreeningBackup.parse("\uFEFF+32 470 00 00 01\r\n0470 00 00 02\n\nnot a number\n")
        assertEquals(listOf("+32 470 00 00 01", "0470 00 00 02"), r.blocked)
        assertEquals(1, r.skipped)
    }

    @Test fun invalidCsvLinesAreSkipped() {
        val r = ScreeningBackup.parse(
            """
            type,value,action
            prefix,+4*,reject
            pattern,(.*)+${'$'},reject
            pattern,*,reject
            exact,abc,reject
            hidden,,silence
            list,+32470000000,allow
            something,1,reject
            """.trimIndent(),
        )
        assertEquals(listOf(ScreenRule(RuleKind.HIDDEN, "", ScreenAction.SILENCE)), r.rules)
        // "allow" makes no sense on the callers list: it becomes silence.
        assertEquals(listOf(ListedNumber("+32470000000", ScreenAction.SILENCE)), r.list)
        assertEquals(5, r.skipped)
    }

    @Test(expected = ImportException::class)
    fun brokenJsonFails() {
        ScreeningBackup.parse("{\"screening\": [}")
    }

    @Test(expected = ImportException::class)
    fun tooLargeFails() {
        ScreeningBackup.parse("1".repeat(ScreeningBackup.MAX_FILE_BYTES + 1))
    }

    @Test fun jsonFromAnotherAppIsEmpty() {
        val r = ScreeningBackup.parse("{\"app\":\"Other\",\"something\":[1,2,3]}")
        assertTrue(r.isEmpty)
    }

    @Test fun duplicatesAreMergedOnImport() {
        val r = ScreeningBackup.parse("prefix,+44,reject\nprefix,+4 4,silence\nblocked,+3212\nblocked,+32 12\n")
        assertEquals(1, r.rules.size)
        assertEquals(1, r.blocked.size)
    }

    @Test fun mergeAddsAndReplacesButKeepsSwitches() {
        val imported = ImportedRules(
            rules = listOf(ScreenRule(RuleKind.PREFIX, "+ 44", ScreenAction.SILENCE), ScreenRule(RuleKind.PREFIX, "+33", ScreenAction.REJECT)),
            list = listOf(ListedNumber("+32 2 555 0000", ScreenAction.SILENCE), ListedNumber("+31 6 1234 5678", ScreenAction.REJECT)),
        )
        val merged = ScreeningBackup.merge(config, imported)
        assertEquals(config.enabled, merged.enabled)
        assertEquals(config.contactsAlwaysRing, merged.contactsAlwaysRing)
        assertEquals(config.logRejected, merged.logRejected)
        assertEquals(config.notifyRejected, merged.notifyRejected)
        assertEquals(config.rules.size + 1, merged.rules.size)
        assertEquals(ScreenAction.SILENCE, merged.rules.first { it.kind == RuleKind.PREFIX && ScreeningBackup.ruleKey(it) == "+44" }.action)
        assertEquals(config.list.size + 1, merged.list.size)
        assertEquals(ScreenAction.SILENCE, merged.list.first { ScreeningMatcher.normalize(it.number) == "+3225550000" }.action)
    }

    @Test fun csvNeverBreaksOnOddValues() {
        val odd = ScreeningConfig(rules = listOf(ScreenRule(RuleKind.EXACT, "1,2\"3", ScreenAction.REJECT)))
        val csv = ScreeningBackup.toCsv(odd, emptyList())
        assertEquals(2, csv.trim().lines().size)
    }
}
