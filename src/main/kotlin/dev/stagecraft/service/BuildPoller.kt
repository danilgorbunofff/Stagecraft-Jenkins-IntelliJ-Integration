package dev.stagecraft.service

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A cancellable handle from [BuildPoller.start]. */
interface PollerHandle {
    fun cancel()
}

/**
 * Re-runs a task at a fixed cadence until cancelled. Plain Kotlin — no IDE imports — so cadence
 * behaviour is unit-testable (§9.1: every trap covered headless).
 */
class BuildPoller(
    private val scheduler: ScheduledExecutorService,
    private val periodMillis: Long = DEFAULT_PERIOD_MILLIS,
) {

    fun start(task: () -> Unit): PollerHandle {
        val future = scheduler.scheduleWithFixedDelay(
            task,
            periodMillis,
            periodMillis,
            TimeUnit.MILLISECONDS,
        )
        return object : PollerHandle {
            private var cancelled = false
            override fun cancel() {
                if (!cancelled) {
                    cancelled = true
                    future.cancel(false)
                }
            }
        }
    }

    companion object {
        /** §11 Days 5-6: the branch list refreshes quietly while the tool window is open. */
        const val DEFAULT_PERIOD_MILLIS = 15_000L
    }
}
