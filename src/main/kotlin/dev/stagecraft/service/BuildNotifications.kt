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
 * Detects builds that finished **after** the first live list for their job was seen (§7.2).
 *
 * The first live list for a job is the baseline: builds that had already finished when Stagecraft
 * first looked are not announced, or every IDE start would fire a balloon per build. After that,
 * every build that is seen finished for the first time is returned - all of them, not only the
 * newest, so two builds that finish between two polls get two balloons, in build order. A build
 * that was running at the baseline is announced when it finishes, and so is the first build of a
 * branch that had no finished build yet: an empty baseline is still a baseline.
 *
 * Callers must only feed **live** lists. A list painted from the disk cache is days old, and using
 * it as the baseline would announce every build that finished while the IDE was closed.
 */
class NewBuildWatcher {

    private val finishedSeen = HashMap<String, MutableSet<Int>>()

    @Synchronized
    fun observe(jobKey: String, builds: List<BuildRef>): List<BuildRef> {
        val finished = builds.filter { !it.isRunning }
        val seen = finishedSeen[jobKey]
        if (seen == null) {
            finishedSeen[jobKey] = finished.mapTo(HashSet()) { it.number }
            return emptyList()
        }
        val fresh = finished.filter { it.number !in seen }.sortedBy { it.number }
        fresh.forEach { seen += it.number }
        return fresh
    }
}
