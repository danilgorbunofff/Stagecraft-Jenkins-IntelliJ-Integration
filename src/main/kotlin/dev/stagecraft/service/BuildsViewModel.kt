package dev.stagecraft.service

import dev.stagecraft.jenkins.JenkinsClient
import dev.stagecraft.jenkins.JenkinsException
import dev.stagecraft.jenkins.JenkinsUrls
import dev.stagecraft.jenkins.JobIndex
import dev.stagecraft.jenkins.RemoteMatcher
import dev.stagecraft.jenkins.jobFromName
import dev.stagecraft.model.BuildRef
import dev.stagecraft.model.JobNode
import java.io.File
import java.util.concurrent.Executor

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
    ) : ToolWindowState

    /** The job matched and exists, but has no builds yet. */
    data class Empty(val job: JobNode, val how: String) : ToolWindowState

    /** Something went wrong. [retryable] says whether a Retry button can help. */
    data class Failed(val reason: String, val retryable: Boolean) : ToolWindowState
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
 * trusts server URLs that come back in build payloads (the client rebases them).
 */
class DefaultBuildsLoader(
    private val credentials: CredentialStore,
    private val clientFactory: JenkinsClientFactory,
    private val cacheDir: File?,
) : BuildsLoader {

    override fun load(state: StagecraftState, ctx: BranchContext): ToolWindowState {
        if (!state.isConfigured) return ToolWindowState.Unconfigured

        val token = credentials.token(state.serverUrl)
        if (token == null) {
            return ToolWindowState.Failed(
                "Stagecraft has no API token stored for this server yet. Add one under " +
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
            // time, not discovered three requests later (§9.2).
            client.verifyCredentials()

            val index = indexFor(state, client)
            val match = RemoteMatcher(index).match(ctx.remoteUrl, ctx.branch, ctx.prNumber)

            when (match) {
                is RemoteMatcher.MatchResult.Unresolved -> ToolWindowState.Failed(match.reason, retryable = true)
                is RemoteMatcher.MatchResult.Matched -> readyState(client, match.job, match.how, fromCache = false)
            }
        } catch (e: JenkinsException) {
            ToolWindowState.Failed(e.message ?: e.javaClass.simpleName, retryable = true)
        } catch (e: Exception) {
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
            is RemoteMatcher.MatchResult.Matched ->
                ToolWindowState.Loading("checking builds of \"${match.job.displayName}\"")
            is RemoteMatcher.MatchResult.Unresolved -> ToolWindowState.Loading(null)
        }
    }

    /** Cache-first index fetch: pins survive refetches via [JobIndex.loadOrFetch]'s `previous`. */
    private fun indexFor(state: StagecraftState, client: JenkinsClient): JobIndex {
        val previous = cacheDir?.let { JobIndex.load(it, state.serverUrl) }
        val fresh = if (cacheDir != null) {
            JobIndex.loadOrFetch(client, cacheDir, previous = previous)
        } else {
            JobIndex.fetch(client)
        }
        return withPins(fresh, state)
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
        job: JobNode,
        how: String,
        fromCache: Boolean,
    ): ToolWindowState {
        val builds = client.builds(job.rawPath)
        if (builds.isEmpty()) return ToolWindowState.Empty(job, how)
        return ToolWindowState.Ready(builds, job, how, client.versionWarning, fromCache)
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

    @Volatile
    var state: ToolWindowState = ToolWindowState.Unconfigured
        private set

    var onState: ((ToolWindowState) -> Unit)? = null

    /**
     * First paint: disk-only, no network. The panel shows this instantly and a [refresh] refines
     * it on the executor right after.
     */
    fun paintFirst(state: StagecraftState, ctx: BranchContext): ToolWindowState {
        val next = loader.snapshotForFirstPaint(state, ctx)
        update(next)
        return next
    }

    /** Full load on the executor. Returns immediately; results arrive through [onState]. */
    fun refresh(config: StagecraftState, ctx: BranchContext) {
        // An Unconfigured project must not flash a spinner for a load it cannot do yet — go
        // straight to the load result when it lands.
        if (state !is ToolWindowState.Unconfigured) {
            update(ToolWindowState.Loading(null))
        }
        executor.execute {
            update(loader.load(config, ctx))
        }
    }

    private fun update(next: ToolWindowState) {
        state = next
        onState?.invoke(next)
    }
}
