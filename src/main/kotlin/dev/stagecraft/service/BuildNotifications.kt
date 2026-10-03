package dev.stagecraft.service

import dev.stagecraft.model.BuildRef

/** The IDE-side act of showing one build balloon; a seam so the dedup rule is testable headless. */
fun interface BuildNotifier {
    fun show(build: BuildRef)
}

/**
 * §7.2: **one balloon per build**, scoped to the branch.
 *
 * The branch scoping is upstream - the view model only ever lists the current branch's builds - so
 * this class is only the "one per build" rule. It is kept headless because it is a rule, not a
 * widget: a second poll, a second tool window or a second IDE start must not re-announce a build
 * that has already been announced.
 */
class BuildNotifications(private val notifier: BuildNotifier) {

    private val notified = HashSet<String>()
    private val lock = Any()

    fun onBuild(build: BuildRef) {
        val first = synchronized(lock) { notified.add(build.url) }
        if (first) notifier.show(build)
    }

    fun alreadyNotified(build: BuildRef): Boolean = synchronized(lock) { build.url in notified }
}

/**
 * Detects a build that appeared **after** the tool window already had a list on screen.
 *
 * The first list for a job is the baseline: the builds that already existed when the window opened
 * are not announced, or every IDE start would fire a balloon per build. Only a build with a number
 * higher than anything seen, and no longer running, is returned. Headless, so the rule is tested.
 */
class NewBuildWatcher {

    private val seenMax = HashMap<String, Int>()

    fun observe(jobKey: String, builds: List<BuildRef>): BuildRef? {
        // The baseline is the highest *finished* build, so a build that is running now is still new
        // when it finishes rather than being swallowed by the poll that first saw it.
        val finished = builds.filter { !it.isRunning }
        val maxFinished = finished.maxOfOrNull { it.number } ?: return null
        val previous = seenMax[jobKey]
        seenMax[jobKey] = maxOf(previous ?: maxFinished, maxFinished)
        if (previous == null) return null
        return finished.filter { it.number > previous }.maxByOrNull { it.number }
    }
}
