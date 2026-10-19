package io.github.ravenor907.handheldforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class BugReportTest {
    private fun info(lines: Int) = BugReport.Info(
        "0.9.7", "Google Pixel 8", "16 (API 36)", mapOf("write while downloading" to true),
        "Format drive", "SanDisk Ultra 64 GB, USB 3", "ERROR", "The drive stopped answering.\nmore", setOf("x"),
        List(lines) { "12:00:%02d line $it".format(it % 60) },
    )

    @Test
    fun reportHasEverything() {
        val t = BugReport.text(info(3))
        for (want in listOf("0.9.7", "Pixel 8", "API 36", "write while downloading on", "Format drive", "SanDisk", "ERROR (x)",
            "stopped answering", "Log (3 lines)", "line 0", "line 2")) {
            assertTrue("missing $want in\n$t", t.contains(want))
        }
        assertEquals("Format drive: error - The drive stopped answering.", BugReport.title(info(0)))
    }

    @Test
    fun linkFitsAndKeepsTheEndOfTheLog() {
        val small = BugReport.issueUrl(info(5))
        assertTrue(URLDecoder.decode(small, "UTF-8").contains("line 4"))
        val big = BugReport.issueUrl(info(2000))
        assertTrue("link is ${big.length} long", big.length <= 7000)
        val body = URLDecoder.decode(big, "UTF-8")
        assertTrue(body.contains("line 1999")) // the end, where the failure is, survives
        assertTrue(body.contains("on the clipboard"))
    }
}
