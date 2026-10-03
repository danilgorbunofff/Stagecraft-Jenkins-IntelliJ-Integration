package dev.stagecraft.service

import dev.stagecraft.model.BuildRef
import dev.stagecraft.model.BuildStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildNotificationsTest {

    private fun build(number: Int, status: BuildStatus = BuildStatus.FAILURE) = BuildRef(
        jobFullName = "svc/main",
        jobRawPath = listOf("svc", "main"),
        number = number,
        url = "http://h/job/svc/job/main/$number/",
        status = status,
        timestampMillis = 0,
        durationMillis = 0,
    )

    @Test
    fun `a build is announced exactly once`() {
        val shown = ArrayList<BuildRef>()
        val notifications = BuildNotifications { shown += it }
        val build = build(1)

        notifications.onBuild(build)
        notifications.onBuild(build)
        notifications.onBuild(build)

        assertEquals(1, shown.size)
    }

    @Test
    fun `a second build is a second balloon`() {
        val shown = ArrayList<BuildRef>()
        val notifications = BuildNotifications { shown += it }

        notifications.onBuild(build(1))
        notifications.onBuild(build(2))

        assertEquals(listOf(1, 2), shown.map { it.number })
    }

    @Test
    fun `the first list for a job is a baseline and announces nothing`() {
        val watcher = NewBuildWatcher()

        assertEquals(emptyList(), watcher.observe("svc/main", listOf(build(1), build(2))))
    }

    @Test
    fun `a build that appears after the baseline is announced`() {
        val watcher = NewBuildWatcher()
        watcher.observe("svc/main", listOf(build(1)))

        val announced = watcher.observe("svc/main", listOf(build(1), build(2)))

        assertEquals(listOf(2), announced.map { it.number })
    }

    @Test
    fun `a running build is not announced until it finishes`() {
        val watcher = NewBuildWatcher()
        watcher.observe("svc/main", listOf(build(1)))

        assertEquals(emptyList(), watcher.observe("svc/main", listOf(build(1), build(2, BuildStatus.RUNNING))))
        assertEquals(listOf(2), watcher.observe("svc/main", listOf(build(1), build(2, BuildStatus.FAILURE))).map { it.number })
    }

    @Test
    fun `two branches do not share a baseline`() {
        val watcher = NewBuildWatcher()
        watcher.observe("svc/main", listOf(build(1)))

        // A different job's first list is its own baseline, so its existing builds are not announced.
        assertEquals(emptyList(), watcher.observe("svc/feature", listOf(build(5))))
    }

    // ---------------------------------------------------------------- audit regressions

    @Test
    fun `the first build of a new branch is announced`() {
        // A brand-new branch: its first list has only a running build. That is still a baseline.
        val watcher = NewBuildWatcher()

        assertEquals(emptyList(), watcher.observe("svc/feature-x", listOf(build(1, BuildStatus.RUNNING))))
        assertEquals(listOf(1), watcher.observe("svc/feature-x", listOf(build(1, BuildStatus.FAILURE))).map { it.number })
    }

    @Test
    fun `every build that finished between two polls is announced, in order`() {
        val watcher = NewBuildWatcher()
        watcher.observe("svc/main", listOf(build(5)))

        assertEquals(listOf(6, 7), watcher.observe("svc/main", listOf(build(7), build(6), build(5))).map { it.number })
    }

    @Test
    fun `a build that finishes after a newer one is still announced`() {
        val watcher = NewBuildWatcher()
        watcher.observe("svc/main", listOf(build(10), build(11, BuildStatus.RUNNING), build(12, BuildStatus.RUNNING)))
        watcher.observe("svc/main", listOf(build(10), build(11, BuildStatus.RUNNING), build(12)))

        assertEquals(listOf(11), watcher.observe("svc/main", listOf(build(10), build(11), build(12))).map { it.number })
    }
}
