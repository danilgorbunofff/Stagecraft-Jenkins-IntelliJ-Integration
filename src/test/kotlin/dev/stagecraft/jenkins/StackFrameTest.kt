package dev.stagecraft.jenkins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StackFrameTest {

    @Test
    fun `a jvm frame carries its class, file and line`() {
        val text = "at com.company.OrderServiceTest.computeTotals(OrderServiceTest.java:88)"

        val frame = StackFrames.find(text).single()

        assertEquals(FrameKind.JVM, frame.kind)
        assertEquals("OrderServiceTest.java", frame.file)
        assertEquals(88, frame.line)
        assertEquals("com.company.OrderServiceTest", frame.className)
        assertEquals("computeTotals", frame.method)
        // The class's package is prepended, so the class - not the common file name - picks the file.
        assertEquals("com/company/OrderServiceTest.java", frame.pathHint)
    }

    @Test
    fun `the recorded stack trace yields both frames in order`() {
        val trace = "at com.company.OrderServiceTest.computeTotals(OrderServiceTest.java:88)\n" +
            "at com.company.OrderService.resolveDiscount(OrderService.java:214)"

        val frames = StackFrames.find(trace)

        assertEquals(2, frames.size)
        assertEquals(listOf(88, 214), frames.map { it.line })
        assertTrue(frames[0].start < frames[1].start)
    }

    @Test
    fun `an unindented jvm frame is still found`() {
        // Jenkins' errorStackTrace can start with `at` and no leading whitespace.
        val frame = StackFrames.find("at com.company.Foo.run(Foo.java:1)").single()

        assertEquals(1, frame.line)
    }

    @Test
    fun `compiler and tool errors are found with their agent-side paths`() {
        val text = """
            [ERROR] /workspace/src/main/java/com/company/OrderService.java:[214,5] cannot find symbol
            e: file:///workspace/build.gradle.kts:12:3 Unresolved reference
            src/main/java/com/company/Foo.java:10: error: cannot find symbol
        """.trimIndent()

        val frames = StackFrames.find(text)

        assertEquals(3, frames.size)
        assertEquals(setOf(FrameKind.MAVEN, FrameKind.GRADLE, FrameKind.COMPILER), frames.map { it.kind }.toSet())
        assertEquals(214, frames.first { it.kind == FrameKind.MAVEN }.line)
        assertEquals(5, frames.first { it.kind == FrameKind.MAVEN }.column)
        assertEquals(12, frames.first { it.kind == FrameKind.GRADLE }.line)
    }

    @Test
    fun `python, node and dotnet frames are found`() {
        val python = StackFrames.find("File \"/app/orders/service.py\", line 42, in handle").single()
        assertEquals(FrameKind.PYTHON, python.kind)
        assertEquals(42, python.line)

        val node = StackFrames.find("at handler (/app/dist/server.js:17:9)").single()
        assertEquals(FrameKind.NODE, node.kind)
        assertEquals(17, node.line)

        val dotnet = StackFrames.find("   at Acme.Order.Run() in C:\\src\\Order.cs:line 55").single()
        assertEquals(FrameKind.DOTNET, dotnet.kind)
        assertEquals(55, dotnet.line)
    }

    @Test
    fun `plain text has no frames`() {
        assertTrue(StackFrames.find("[Pipeline] stage\nhello world\n").isEmpty())
    }

    // ---------------------------------------------------------------- audit regressions

    @Test
    fun `a kotlin backtick test name is still a jvm frame`() {
        val frame = StackFrames.find("    at x.FooTest.should compute total(FooTest.kt:12)").single()

        assertEquals("x.FooTest", frame.className)
        assertEquals("should compute total", frame.method)
        assertEquals(12, frame.line)
    }

    @Test
    fun `an unindented frame is underlined from its own at`() {
        val text = "previous line\nat a.B.c(B.java:3)"

        val frame = StackFrames.find(text).single()

        assertEquals(text.indexOf("at a.B"), frame.start)
    }

    @Test
    fun `go, tsc and rustc errors are locations too`() {
        val text = "./main.go:10:5: undefined: x\nsrc/a.ts(10,5): error TS2322: nope\nerror[E0425]: x\n  --> src/main.rs:10:5\n"

        val files = StackFrames.find(text).map { it.file to it.line }

        assertEquals(listOf("./main.go" to 10, "src/a.ts" to 10, "src/main.rs" to 10), files)
    }

    @Test
    fun `a huge single line is skipped, not scanned for seconds`() {
        val line = "value at index ".repeat(20_000) // 300 KB, the shape that took 17 s
        val started = System.nanoTime()

        val frames = StackFrames.find(line + "\n    at a.B.c(B.java:3)\n")

        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(millis < 500, "took $millis ms")
        assertEquals(1, frames.size)
    }

    @Test
    fun `a failed test opens its own frame, not the assertion library's`() {
        val trace = """
            org.opentest4j.AssertionFailedError: expected: <1> but was: <2>
            	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
            	at org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:166)
            	at com.company.OrderServiceTest.discount(OrderServiceTest.java:42)
            	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
        """.trimIndent()

        val frame = TestFrames.testFrame("com.company.OrderServiceTest", StackFrames.find(trace))

        assertEquals("OrderServiceTest.java", frame?.file)
        assertEquals(42, frame?.line)
    }

    @Test
    fun `without a matching class the first non-framework frame is the test`() {
        val trace = "\tat org.junit.Assert.fail(Assert.java:89)\n\tat com.x.Helper.check(Helper.kt:7)\n"

        assertEquals("Helper.kt", TestFrames.testFrame("com.x.OtherTest", StackFrames.find(trace))?.file)
    }
}
