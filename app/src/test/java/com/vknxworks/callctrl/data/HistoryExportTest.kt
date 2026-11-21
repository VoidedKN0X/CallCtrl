package com.vknxworks.callctrl.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryExportTest {
    @Test fun cellsAreQuotedAndFormulasGuarded() {
        assertEquals("\"Sam\"", HistoryExport.cell("Sam"))
        assertEquals("\"'+32470123456\"", HistoryExport.cell("+32470123456"))
        assertEquals("\"'=cmd|' /C calc'!A0\"", HistoryExport.cell("=cmd|' /C calc'!A0"))
        assertEquals("\"'@SUM(1)\"", HistoryExport.cell("@SUM(1)"))
        assertEquals("\"'-1\"", HistoryExport.cell("-1"))
        assertEquals("\"said \"\"hi\"\", twice\"", HistoryExport.cell("said \"hi\", twice"))
        assertEquals("\"two lines\"", HistoryExport.cell("two\nlines"))
        assertEquals("\"\"", HistoryExport.cell(""))
    }

    @Test fun csvHasAHeaderAndOneLinePerCall() {
        val csv = HistoryExport.csv(
            listOf(
                ExportCall(0L, ExportType.MISSED, "+32470123456", "Sam, the plumber", 0, "SIM 1", "call back\ntomorrow", listOf("work", "urgent")),
                ExportCall(60_000L, ExportType.OUTGOING, "", "", 75, "", "", emptyList()),
            ),
        )
        val lines = csv.split("\r\n")
        assertEquals(HistoryExport.HEADER, lines[0])
        assertEquals(4, lines.size)
        assertTrue(lines[1].endsWith(",missed,\"'+32470123456\",\"Sam, the plumber\",0,\"SIM 1\",\"call back tomorrow\",\"work urgent\""))
        assertTrue(lines[2].endsWith(",outgoing,\"\",\"\",75,\"\",\"\",\"\""))
        assertEquals("", lines[3])
    }
}
