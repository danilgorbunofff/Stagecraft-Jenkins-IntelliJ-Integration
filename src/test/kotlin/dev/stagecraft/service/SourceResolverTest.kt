package dev.stagecraft.service

import dev.stagecraft.jenkins.StackFrames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceResolverTest {

    private val projectPaths = listOf(
        "src/test/java/com/company/OrderServiceTest.java",
        "src/main/java/com/company/OrderService.java",
        "src/main/java/com/company/Other.java",
    )

    @Test
    fun `a jvm frame resolves to its class file by suffix`() {
        val frame = StackFrames.find("at com.company.OrderService.resolveDiscount(OrderService.java:214)").single()

        val location = SuffixSourceResolver(projectPaths).resolve(frame)

        assertEquals("src/main/java/com/company/OrderService.java", location?.path)
        assertEquals(214, location?.line)
    }

    @Test
    fun `a file that is not in the project does not resolve`() {
        val frame = StackFrames.find("at com.company.Missing.run(Missing.java:1)").single()

        assertNull(SuffixSourceResolver(projectPaths).resolve(frame))
    }

    @Test
    fun `an agent-side path resolves to the project suffix`() {
        val frame = StackFrames.find(
            "[ERROR] /var/jenkins/workspace/svc/src/main/java/com/company/OrderService.java:[214,5] boom",
        ).single()

        val location = SuffixSourceResolver(projectPaths).resolve(frame)

        assertEquals("src/main/java/com/company/OrderService.java", location?.path)
        assertEquals(214, location?.line)
        assertEquals(5, location?.column)
    }

    @Test
    fun `two files with the same suffix is a tie, and a tie means no link`() {
        val frame = StackFrames.find("at com.company.OrderService.run(OrderService.java:1)").single()
        val tied = listOf("a/com/company/OrderService.java", "b/com/company/OrderService.java")

        assertNull(SuffixSourceResolver(tied).resolve(frame))
    }

    @Test
    fun `the longest matching suffix wins`() {
        val frame = StackFrames.find("at com.company.OrderService.run(OrderService.java:1)").single()
        val paths = listOf(
            "OrderService.java",
            "src/main/java/com/company/OrderService.java",
        )

        val location = SuffixSourceResolver(paths).resolve(frame)

        assertEquals("src/main/java/com/company/OrderService.java", location?.path)
    }

    // ---------------------------------------------------------------- audit regressions

    private val absoluteProject = listOf(
        "/Users/me/svc/src/main/java/com/company/OrderService.java",
        "/Users/me/svc/src/main/kotlin/order/Socket.kt",
        "/Users/me/svc/web/src/app.ts",
    )

    @Test
    fun `an agent-side absolute path resolves by its shared suffix`() {
        val maven = StackFrames.find("[ERROR] /var/jenkins/ws/svc/src/main/java/com/company/OrderService.java:[214,8] boom").single()
        val windows = StackFrames.find("[ERROR] C:\\agent\\ws\\svc\\src\\main\\java\\com\\company\\OrderService.java:[214,8] boom").single()

        assertEquals(absoluteProject[0], SuffixSourceResolver(absoluteProject).resolve(maven)?.path)
        assertEquals(absoluteProject[0], SuffixSourceResolver(absoluteProject).resolve(windows)?.path)
    }

    @Test
    fun `a kotlin file in a directory without the root package still resolves`() {
        // package com.company.order, filed under src/main/kotlin/order/ (Kotlin's recommended layout)
        val frame = StackFrames.find("\tat com.company.order.Socket.open(Socket.kt:12)").single()

        assertEquals(absoluteProject[1], SuffixSourceResolver(absoluteProject).resolve(frame)?.path)
    }

    @Test
    fun `a shared file name with no shared directory does not count as a segment match`() {
        val frame = StackFrames.find("[ERROR] /agent/MyOrderService.java:[1,1] x").single()

        assertNull(SuffixSourceResolver(absoluteProject).resolve(frame))
    }
}
