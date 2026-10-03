package dev.stagecraft.jenkins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogFilterTest {

    private val log = listOf(
        "[Pipeline] stage",
        "com.acme.order.OrderService: loading",
        "WARNING: deprecated call",
        "at com.acme.order.OrderService.run(OrderService.java:214)",
        "ERROR: build failed",
        "some framework noise",
    ).joinToString("\n")

    @Test
    fun `all keeps every line`() {
        assertEquals(log, LogFilter.apply(log, LogFilterMode.ALL))
    }

    @Test
    fun `errors keeps only the error lines`() {
        val filtered = LogFilter.apply(log, LogFilterMode.ERRORS)

        assertEquals("ERROR: build failed", filtered)
    }

    @Test
    fun `warnings keeps only the warning lines`() {
        val filtered = LogFilter.apply(log, LogFilterMode.WARNINGS)

        assertEquals("WARNING: deprecated call", filtered)
    }

    @Test
    fun `own package keeps lines that mention the project's names`() {
        val filtered = LogFilter.apply(log, LogFilterMode.OWN_PACKAGE, ownPackageTokens = listOf("com.acme.order"))

        assertEquals(
            "com.acme.order.OrderService: loading\n" +
                "at com.acme.order.OrderService.run(OrderService.java:214)",
            filtered,
        )
    }

    @Test
    fun `own package with no tokens keeps everything rather than blanking the view`() {
        assertEquals(log, LogFilter.apply(log, LogFilterMode.OWN_PACKAGE, ownPackageTokens = emptyList()))
    }

    @Test
    fun `whitespace collapse turns a run of blank lines into one`() {
        val spaced = "a\n\n\n\nb\n\nc\n"

        val collapsed = LogFilter.apply(spaced, LogFilterMode.ALL, collapseBlankLines = true)

        assertEquals("a\n\nb\n\nc\n", collapsed)
    }

    @Test
    fun `a warning predicate does not fire on an ordinary line`() {
        assertTrue(LogFilter.isWarning("WARNING: x"))
        assertTrue(!LogFilter.isWarning("all good here"))
    }
}
