package dev.stagecraft.service

import dev.stagecraft.jenkins.JenkinsClient
import dev.stagecraft.jenkins.JenkinsException
import dev.stagecraft.jenkins.JenkinsUrls
import dev.stagecraft.jenkins.JobIndex
import dev.stagecraft.jenkins.RemoteInfo
import dev.stagecraft.jenkins.RemoteMatcher
import dev.stagecraft.jenkins.confirmWithBuild
import dev.stagecraft.jenkins.jobFromName
import dev.stagecraft.model.BuildRef
import dev.stagecraft.model.JobNode
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** What the git side of a project looks like when a load starts. */
data class BranchContext(
    val remoteUrl: String?,
    val branch: String?,
    val prNumber: Int?,
)

/** Result of [BuildsLoader.load] / [BuildsLoader.snapshotForFirstPaint]. */
sealed interface ToolWindowState {

    /** No settings yet — show a call to action, not a spinner. */
    object Unconfigured : ToolWindowState

    /**
     * Working. [hint] names the job we are checking when a warm cache already knows it, so the
     * first paint is never a bare spinner (§9.7: cache-warm paint < 200 ms).
     */
    data class Loading(val hint: String?) : ToolWindowState

    /** The branch's builds, newest first. */
    data class Ready(
        val builds: List<BuildRef>,
        val job: JobNode,
        val how: String,
        val versionWarning: String?,
        val fromCache: Boolean,
        /** Age of the cached rows when [fromCache], so the UI can show how stale they are (§15.4 #3). */
        val ageMillis: Long? = null,
    ) : ToolWindowState

    /** The job matched and exists, but has no builds yet. */
    data class Empty(
        val job: JobNode,
        val how: String,
        /** Age of the cached "no builds" answer, or null when it came from a live load. */
        val ageMillis: Long? = null,
    ) : ToolWindowState

    /** Something went wrong. [retryable] says whether a Retry button can help. */
    data class Failed(val reason: String, val retryable: Boolean) : ToolWindowState

    /**
     * The user stopped a load that was taking too long (§15.5: no loading state may outlive 200 ms
     * without a cancel button). The request already on the wire is not aborted — a blocking read
     * cannot be — but its answer is discarded and the spinner is replaced by a state that says so.
     */
    object Cancelled : ToolWindowState
}

/** The headless behaviour the tool window renders. */
interface BuildsLoader {

    fun load(state: StagecraftState, ctx: BranchContext): ToolWindowState

    /** Disk-cache-only, no network. Must stay fast (§9.7: < 200 ms with a warm cache). */
    fun snapshotForFirstPaint(state: StagecraftState, ctx: BranchContext): ToolWindowState
}

fun interface JenkinsClientFactory {
    fun create(baseUrl: String, user: String, token: String): JenkinsClient
}

/**
 * Resolves the project's git remote to a Jenkins job through a job index, then lists that job's
 * builds. Every rule in §7.3 lives here: the loader never issues an unbounded job list GET, always
 * verifies credentials before indexing, admits honestly when it cannot name the job, and never
 * trusts server URLs that come back in build payloads (the client builds them locally).
 *
 * The loader is called from one I/O thread at a time, which is what lets it keep the little state
 * it has - which client has been verified, which jobs have been confirmed - in plain fields.
 */
class DefaultBuildsLoader(
    private val credentials: CredentialStore,
    private val clientFactory: JenkinsClientFactory,
    private val cacheDir: File?,
    private val clock: () -> Long = System::currentTimeMillis,
) : BuildsLoader {

    /** The client whose credentials `/me` already confirmed; a poll does not re-ask (§9.2). */
    private var verifiedClient: JenkinsClient? = null

    /**
     * §9.4 step 3, once per job: what the newest build said about the repository it came from.
     * The value is the sentence to add to the explanation, or "" when the build agreed or could not
     * tell.
     */
    private val confirmations = HashMap<String, String>()

    override fun load(state: StagecraftState, ctx: BranchContext): ToolWindowState {
        if (!state.isConfigured) return ToolWindowState.Unconfigured

        val token = credentials.token(state.serverUrl, state.user)
        if (token == null) {
            return ToolWindowState.Failed(
                "Stagecraft has no API token stored for ${state.user} on this server yet. Add one under " +
                    "Settings > Tools > Stagecraft.",
                retryable = false,
            )
        }
        if (ctx.remoteUrl.isNullOrBlank()) {
            return ToolWindowState.Failed(
                "Stagecraft could not see a git remote in this project, so it cannot name a job. " +
                    "Open the project from a git checkout, or pin a job in settings.",
                retryable = true,
            )
        }

        val client = clientFactory.create(
            JenkinsUrls.normalizeBase(state.serverUrl),
            state.user,
            token,
        )

        return try {
            // Verify identity first: a 401 or an anonymous mismatch must be named at connection
            // time, not discovered three requests later (§9.2). Once per client: a token revoked
            // later still fails loudly, as a 401 on the next request.
            if (client !== verifiedClient) {
                client.verifyCredentials()
                verifiedClient = client
            }

            val match = matchWithFreshIndex(state, client, ctx)
            when (match) {
                is RemoteMatcher.MatchResult.Unresolved -> ToolWindowState.Failed(match.reason, retryable = true)
                is RemoteMatcher.MatchResult.Matched -> readyState(client, match, state, ctx)
            }
        } catch (e: JenkinsException) {
            verifiedClient = null
            ToolWindowState.Failed(e.message ?: e.javaClass.simpleName, retryable = true)
        } catch (e: Exception) {
            verifiedClient = null
            // A wild exception must never reach the EDT with a stack trace and no message (§7.3 rule 2).
            ToolWindowState.Failed("Unexpected error: ${e.message ?: e.javaClass.simpleName}", retryable = true)
        }
    }

    override fun snapshotForFirstPaint(state: StagecraftState, ctx: BranchContext): ToolWindowState {
        if (!state.isConfigured) return ToolWindowState.Unconfigured
        if (ctx.remoteUrl.isNullOrBlank()) {
            return ToolWindowState.Loading("waiting for a git remote in this project")
        }

        val cacheDir = cacheDir ?: return ToolWindowState.Loading(null)
        val cached = JobIndex.load(cacheDir, state.serverUrl) ?: return ToolWindowState.Loading(null)
        val index = withPins(cached, state)

        // Never touch the network here: paint fast, refine in the background.
        return when (val match = RemoteMatcher(index).match(ctx.remoteUrl, ctx.branch, ctx.prNumber)) {
            is RemoteMatcher.MatchResult.Matched -> cachedBuilds(cacheDir, state, match, ctx)
            is RemoteMatcher.MatchResult.Unresolved -> ToolWindowState.Loading(null)
        }
    }

    /**
     * §15.4 #3: paint the branch's last known rows from the disk cache, with their age, and let the
     * background refresh replace them. Falls back to the honest "checking" state when no list has
     * been cached yet - a cold cache must still show something instantly (§9.7).
     */
    private fun cachedBuilds(
        cacheDir: File,
        state: StagecraftState,
        match: RemoteMatcher.MatchResult.Matched,
        ctx: BranchContext,
    ): ToolWindowState {
        val key = BuildListCache.key(state.serverUrl, match.job.rawPath, ctx.branch, ctx.prNumber)
        val entry = BuildListCache.load(cacheDir, state.serverUrl, key)
            ?: return ToolWindowState.Loading("checking builds of \"${match.job.displayName}\"")
        val age = BuildListCache.ageMillis(entry, clock())
        return if (entry.builds.isEmpty()) {
            ToolWindowState.Empty(entry.job, entry.how, ageMillis = age)
        } else {
            ToolWindowState.Ready(
                entry.builds,
                entry.job,
                entry.how,
                entry.versionWarning,
                fromCache = true,
                ageMillis = age,
            )
        }
    }

    /**
     * Cache first, but not cache forever (§15.4 #3: populate from the cache, refresh in the
     * background - the load already runs off the EDT).
     *
     * A cached index is refetched when it is older than [MAX_INDEX_AGE_MILLIS], or when it cannot
     * answer this branch and is older than [MISS_REFETCH_AGE_MILLIS]. The second rule is the one that
     * matters day to day: a branch pushed after the index was cached is a miss in the cache and a hit
     * one request later, and every feature branch is pushed after the index was cached. The floor
     * keeps a branch Jenkins genuinely does not have from costing a tree fetch on every poll.
     */
    private fun matchWithFreshIndex(
        state: StagecraftState,
        client: JenkinsClient,
        ctx: BranchContext,
    ): RemoteMatcher.MatchResult {
        val remote = ctx.remoteUrl.orEmpty()
        val cached = cacheDir?.let { JobIndex.load(it, state.serverUrl) }
        if (cached == null) {
            return match(withPins(fetchIndex(client, previous = null), state), ctx, remote)
        }

        val first = match(withPins(cached, state), ctx, remote)
        val age = clock() - cached.fetchedAtMillis
        val stale = age > MAX_INDEX_AGE_MILLIS
        val missed = age > MISS_REFETCH_AGE_MILLIS && isMiss(first)
        if (!stale && !missed) return first
        return match(withPins(fetchIndex(client, previous = cached), state), ctx, remote)
    }

    private fun fetchIndex(client: JenkinsClient, previous: JobIndex?): JobIndex {
        val fresh = JobIndex.fetch(client, nowMillis = clock(), previous = previous)
        cacheDir?.let { JobIndex.save(it, fresh) }
        return fresh
    }

    private fun match(index: JobIndex, ctx: BranchContext, remote: String): RemoteMatcher.MatchResult =
        RemoteMatcher(index).match(remote, ctx.branch, ctx.prNumber)

    /** The index could not name this branch's own job, so a fresher one might. */
    private fun isMiss(match: RemoteMatcher.MatchResult): Boolean = when (match) {
        is RemoteMatcher.MatchResult.Unresolved -> true
        is RemoteMatcher.MatchResult.Matched -> !match.pinned && match.branch != null && match.branchJob == null
    }

    /**
     * Hand [index] the pins from the project's settings (§9.4 step 5).
     *
     * A pin names a job by full name, and the index usually holds it — but it need not: the job may
     * be newer than the cache, the tree may have been cut off by the cap, or the index may be empty
     * on a first run. Dropping such a pin would hand the user back a name guess instead of the job
     * they chose, so the node is built from the name. Pins are keyed per remote, so two repositories
     * may point at the same job.
     */
    private fun withPins(index: JobIndex, state: StagecraftState): JobIndex {
        var pinned = index
        for ((remoteKey, jobFullName) in state.pinnedJobs) {
            val name = jobFullName.trim()
            if (name.isEmpty()) continue
            val job = index.jobs.firstOrNull { it.fullName == name || it.rawPathString == name }
                ?: jobFromName(name, index.serverUrl)
            pinned = pinned.withPin(remoteKey, job)
        }
        return pinned
    }

    private fun readyState(
        client: JenkinsClient,
        match: RemoteMatcher.MatchResult.Matched,
        state: StagecraftState,
        ctx: BranchContext,
    ): ToolWindowState {
        val job = match.job
        val builds = client.builds(job.rawPath)
        val how = if (builds.isEmpty() || match.pinned) {
            match.how
        } else {
            match.how + confirmation(client, job, builds.first(), ctx.remoteUrl.orEmpty())
        }
        cacheBuilds(state, job, builds, how, client.versionWarning, ctx)
        return if (builds.isEmpty()) {
            ToolWindowState.Empty(job, how)
        } else {
            ToolWindowState.Ready(builds, job, how, client.versionWarning, fromCache = false)
        }
    }

    /**
     * §15.4 #3: remember the branch's rows so the next IDE start can paint them before the network.
     * A cache write is best effort - a read-only disk must never cost the user the build list.
     */
    private fun cacheBuilds(
        state: StagecraftState,
        job: JobNode,
        builds: List<BuildRef>,
        how: String,
        versionWarning: String?,
        ctx: BranchContext,
    ) {
        val dir = cacheDir ?: return
        val key = BuildListCache.key(state.serverUrl, job.rawPath, ctx.branch, ctx.prNumber)
        val entry = CachedBuildList(
            serverUrl = state.serverUrl.trimEnd('/'),
            key = key,
            job = job,
            builds = builds,
            how = how,
            versionWarning = versionWarning,
            fetchedAtMillis = clock(),
        )
        try {
            BuildListCache.save(dir, entry)
        } catch (_: Exception) {
            // A cache that cannot be written is not a failure worth reporting: the load succeeded,
            // and the next start simply paints a spinner instead of rows.
        }
    }

    /**
     * §9.4 step 3: ask the newest build which repository it was built from - once per job, since the
     * answer does not change. A build that names other remotes, and none of ours, means the name
     * match picked the wrong job, and the user is told so next to the list. A build that cannot say
     * (no git data, a 404 that may only mean "not visible", a hiccup) adds nothing: absence of
     * evidence is not a contradiction.
     */
    private fun confirmation(client: JenkinsClient, job: JobNode, newest: BuildRef, remoteUrl: String): String {
        val key = job.rawPathString
        confirmations[key]?.let { return it }
        val remote = RemoteInfo.parse(remoteUrl) ?: return ""
        val note = try {
            val found = confirmWithBuild(client, newest.url, remote)
            if (found.remoteMatches || found.foundRemoteUrls.isEmpty()) {
                ""
            } else {
                " - but its newest build was built from ${found.foundRemoteUrls.joinToString(", ")}, not from " +
                    "this repository; pin the right job in settings"
            }
        } catch (_: JenkinsException.Transport) {
            return "" // a hiccup: ask again next time
        } catch (_: JenkinsException) {
            "" // an answer, even if only "not visible to this account"
        } catch (_: RuntimeException) {
            // Best effort by design: the confirmation may add a sentence, it may never cost the
            // user the build list.
            return ""
        }
        confirmations[key] = note
        return note
    }

    companion object {
        /** An index older than this is refetched on the next load, hit or miss. */
        const val MAX_INDEX_AGE_MILLIS = 30L * 60 * 1000

        /** A miss refetches the index only when the cached one is at least this old. */
        const val MISS_REFETCH_AGE_MILLIS = 60L * 1000
    }
}

/**
 * Holds the current [ToolWindowState] and drives loads on an executor (never the EDT — §7.3
 * rule 2). The UI subscribes with [onState]; tests inject a direct executor so every step is
 * synchronous and reproducible with no IDE and no live server.
 */
class BuildsViewModel(
    private val loader: BuildsLoader,
    private val executor: Executor,
) {

    /**
     * What the tool window shows before any load has run.
     *
     * It starts on [ToolWindowState.Loading] rather than [ToolWindowState.Unconfigured] because the
     * panel paints whatever this is: a project that is already configured would be told, in the
     * plugin's own words, that it has no Jenkins server yet and offered a Configure button, which
     * reads as lost settings. "Checking this branch's builds..." is true of every project and is
     * replaced by the real state as soon as [showPlaceholder] or a load answers.
     */
    @Volatile
    var state: ToolWindowState = ToolWindowState.Loading(null)
        private set

    var onState: ((ToolWindowState) -> Unit)? = null

    /** Set on the executor, read from the EDT through [state]. */
    @Volatile
    private var started = false

    /**
     * Bumped by every [refresh] and by [cancel]. A load that finishes with a stale value is thrown
     * away, so a cancelled request cannot overwrite the state that replaced it. An [AtomicInteger]
     * rather than a plain field because [cancel] runs on the EDT while [refresh] may run on the I/O
     * thread.
     */
    private val generation = AtomicInteger(0)

    /**
     * Publish what the project's in-memory settings already say, before the I/O that cannot be
     * instant - git and the cache read.
     *
     * [ToolWindowState.Unconfigured] is the one state answerable with no git, no disk and no network,
     * and it is the one state that must not be shown late: it is wrong about a configured project,
     * where the panel's placeholder would otherwise stand for as long as the first load takes. Does
     * nothing once a load has been started, so a second tool window cannot put a spinner over a list
     * that is already on screen.
     */
    fun showPlaceholder(isConfigured: Boolean) {
        if (started) return
        update(if (isConfigured) ToolWindowState.Loading(null) else ToolWindowState.Unconfigured)
    }

    /**
     * First paint: disk-only, no network. The panel shows this instantly and a [refresh] refines
     * it on the executor right after.
     */
    fun paintFirst(state: StagecraftState, ctx: BranchContext): ToolWindowState {
        started = true
        val next = loader.snapshotForFirstPaint(state, ctx)
        update(next)
        return next
    }

    /**
     * Full load on the executor. Returns immediately; results arrive through [onState].
     *
     * A refresh over a list that is already on screen is quiet: the list stays put until the answer
     * replaces it. Publishing [ToolWindowState.Loading] first would swap the builds for a "checking"
     * card on every poll - every 15 seconds - and drop the user's selection with it.
     */
    fun refresh(config: StagecraftState, ctx: BranchContext) {
        started = true
        val token = generation.incrementAndGet()
        // An unconfigured project must not flash a spinner for a load it cannot do: the load has
        // nothing to wait for, so its Unconfigured answer should simply appear.
        val showing = state
        val contentOnScreen = showing is ToolWindowState.Ready || showing is ToolWindowState.Empty
        if (config.isConfigured && !contentOnScreen) {
            update(ToolWindowState.Loading(null))
        }
        executor.execute {
            val result = loader.load(config, ctx)
            // A cancelled or superseded load must not publish: the state on screen is newer than
            // its answer, however long the socket took to give up (§15.5).
            if (generation.get() == token) update(result)
        }
    }

    /**
     * Stop a load that is taking too long (§15.5). The in-flight request is not aborted - a
     * blocking read cannot be - but its answer is discarded and the spinner is replaced by a state
     * that says the wait was stopped, with a Retry the user can press when ready. Callers stop
     * polling too, so the cancelled state is not immediately replaced by a fresh load.
     */
    fun cancel() {
        generation.incrementAndGet()
        update(ToolWindowState.Cancelled)
    }

    private fun update(next: ToolWindowState) {
        state = next
        onState?.invoke(next)
    }
}
