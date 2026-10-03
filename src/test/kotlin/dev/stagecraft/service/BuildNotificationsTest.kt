package dev.stagecraft.service

import dev.stagecraft.model.BuildRef
import dev.stagecraft.model.BuildStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

        assertNull(watcher.observe("svc/main", listOf(build(1), build(2))))
    }

    @Test
    fun `a build that appears after the baseline is announced`() {
        val watcher = NewBuildWatcher()
        watcher.observe("svc/main", listOf(build(1)))

        val announced = watcher.observe("svc/main", listOf(build(1), build(2)))

        assertEquals(2, announced?.number)
    }

    @Test
    fun `a running build is not announced until it finishes`() {
        val watcher = NewBuildWatcher()
        watcher.observe("svc/main", listOf(build(1)))

        assertNull(watcher.observe("svc/main", listOf(build(1), build(2, BuildStatus.RUNNING))))
        assertEquals(2, watcher.observe("svc/main", listOf(build(1), build(2, BuildStatus.FAILURE)))?.number)
    }

    @Test
    fun `two branches do not share a baseline`() {
        val watcher = NewBuildWatcher()
        watcher.observe("svc/main", listOf(build(1)))

        // A different job's first list is its own baseline, so its existing builds are not announced.
        assertNull(watcher.observe("svc/feature", listOf(build(5))))
    }
}
