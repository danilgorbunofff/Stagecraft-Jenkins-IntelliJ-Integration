package dev.stagecraft.jenkins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogViewTest {

    private fun view(text: String, head: Int = 1_000_000, tail: Int = 1_000_000, live: Boolean = false): LogView {
        val view = LogView(ConsoleLogReader(headCapChars = head, tailCapChars = tail).read(text), head, tail)
        view.setLive(live)
        view.render()
        return view
    }

    private fun LogChange.text(): String = when (this) {
        is LogChange.Replace -> text
        is LogChange.Append -> text
        LogChange.None -> ""
    }

    @Test
    fun `raw live output is appended as it arrives`() {
        val view = view("one\n", live = true)

        val change = view.append("two\nthr")

        assertTrue(change is LogChange.Append)
        assertEquals("two\nthr", change.text())
        assertEquals(3, view.totalLines)
    }

    @Test
    fun `filtered live output is filtered, and a half line waits for its newline`() {
        val view = view("ERROR: one\nnoise\n", live = true)
        assertEquals("ERROR: one", view.setFilter(LogFilterMode.ERRORS, false).text())

        // "ERROR: two" is complete; "ERROR: thr" is still being written.
        val first = view.append("next line\nERROR: two\nERROR: thr")
        assertEquals("\nERROR: two", first.text())

        val second = view.append("ee\n")
        assertEquals("\nERROR: three", second.text())
    }

    @Test
    fun `when the build finishes a filtered view takes its last unterminated line`() {
        val view = view("ERROR: one\n", live = true)
        view.setFilter(LogFilterMode.ERRORS, false)
        view.append("ERROR: last")

        assertEquals("\nERROR: last", view.setLive(false).text())
    }

    @Test
    fun `hours of live output stay under the memory cap`() {
        val view = view("start\n", head = 1_000, tail = 1_000, live = true)
        repeat(2_000) { i -> view.append("output line $i\n") }

        assertTrue(view.isTruncated)
        assertTrue(view.display.length < 1_000 + 2 * 1_000 + 200, "retained ${view.display.length} chars")
        assertEquals(2_001, view.totalLines)
        assertTrue(view.display.toString().endsWith("output line 1999\n"))
    }

    @Test
    fun `a full-log line number maps through truncation and the filter`() {
        val text = (1..400).joinToString("") { if (it % 50 == 0) "ERROR: at $it\n" else "line $it\n" }
        val view = view(text, head = 500, tail = 500)

        // Raw: line 1 is document line 0, the last line is the last document line.
        assertEquals(0, view.documentLine(1))
        val raw = view.render().text().split('\n')
        assertEquals("line 399", raw[view.documentLine(399)!!])

        // Errors only: line 400 is shown; line 399 is filtered out and maps to the next shown line.
        val filtered = view.setFilter(LogFilterMode.ERRORS, false).text().split('\n')
        assertEquals("ERROR: at 400", filtered[view.documentLine(400)!!])
        assertEquals("ERROR: at 400", filtered[view.documentLine(399)!!])
    }

    @Test
    fun `the first error is located in the shown document, or not at all`() {
        val view = view("ok\nERROR: here\nok\n")
        assertEquals(1, view.firstErrorDocumentLine())

        view.setFilter(LogFilterMode.WARNINGS, false)
        assertNull(view.firstErrorDocumentLine())
    }

    @Test
    fun `a stage is found by its pipeline line, also on the stage-view path`() {
        val view = view("[Pipeline] { (Build)\nbuilding\n[Pipeline] { (Deploy)\nERROR: deploy failed\n")

        assertEquals(2, view.stageDocumentLine("Deploy"))
        view.setFilter(LogFilterMode.ERRORS, false)
        assertEquals(0, view.stageDocumentLine("Deploy")) // the next shown line: the error
        assertNull(view.stageDocumentLine("Nope"))
    }

    @Test
    fun `stripes mark error and warning lines in document coordinates`() {
        val view = LogView(ConsoleLogReader().read("ok\nERROR: x\nWARNING: y\n"))

        val stripes = view.render().stripes

        assertEquals(listOf(Stripe(1, LineKind.ERROR), Stripe(2, LineKind.WARNING)), stripes)
    }
}
