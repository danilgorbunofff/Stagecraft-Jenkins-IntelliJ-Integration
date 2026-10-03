package dev.stagecraft.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.net.HttpConfigurable
import com.intellij.util.net.ssl.CertificateManager
import dev.stagecraft.jenkins.ConsoleLog
import dev.stagecraft.jenkins.ConsoleLogReader
import dev.stagecraft.jenkins.ConsoleStages
import dev.stagecraft.jenkins.ConsoleTailer
import dev.stagecraft.jenkins.StageView
import dev.stagecraft.jenkins.JenkinsAuth
import dev.stagecraft.jenkins.JenkinsClient
import dev.stagecraft.jenkins.JenkinsCredential
import dev.stagecraft.jenkins.JenkinsException
import dev.stagecraft.jenkins.JenkinsUrls
import dev.stagecraft.jenkins.LintClient
import dev.stagecraft.jenkins.LintResult
import dev.stagecraft.jenkins.RebuildResult
import dev.stagecraft.jenkins.TailDelta
import dev.stagecraft.jenkins.UrlConnectionTransport
import dev.stagecraft.model.BuildRef
import dev.stagecraft.model.TestReport
import java.io.File
import java.net.ProxySelector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import javax.net.ssl.SSLContext

/**
 * A console read: the bounded log, the cursor to continue from, whether the build is running, and -
 * when it is - the handle [JenkinsService.tailConsole] follows it with.
 */
data class ConsoleRead(val log: ConsoleLog, val cursor: Long, val moreData: Boolean, val tailId: Long? = null)

/**
 * The per-project Stagecraft service (§7.3 rule 3: never one global server).
 *
 * It owns everything that must live as long as the project - the credential store, the I/O
 * executor, the poller, and the view model the tool window renders - and it is the seam where the
 * IDE's own network configuration enters the plugin: `dev.stagecraft.jenkins` is plain Kotlin and
 * may not know that a proxy setting or a trust store exists (§9.1 draws that line, §9.2 requires
 * the proxy and the certificate to be the user's, not the plugin's).
 *
 * This is a light service: the `@Service` annotation is the whole registration.
 */
@Service(Service.Level.PROJECT)
class JenkinsService(private val project: Project) : Disposable {

    val settings: StagecraftProjectSettings get() = StagecraftProjectSettings.getInstance(project)

    /** The API token lives in the platform's password safe, never in the project's settings file. */
    val credentials: CredentialStore = PasswordSafeCredentialStore()

    /**
     * The job index cache: the IDE's system directory, never the project directory, so a cache can
     * never land in version control.
     *
     * `ProjectUtil.getProjectCachePath` would be the obvious call, but it is `@ApiStatus.Internal`
     * and therefore invisible to plugins. The path is composed from public API instead: the IDE's
     * system directory plus the platform's own hash of the project's location, which is exactly what
     * makes two projects with the same name share nothing.
     */
    private val cacheDir: File = File(PathManager.getSystemPath(), "$CACHE_DIR_NAME/${cacheDirKey(project)}")

    /** The thread [io] runs on, so a Cancel can abort exactly the request it is blocked in. */
    @Volatile
    private var ioThread: Thread? = null

    /**
     * The build list, the settings and the password safe: one thread, so a token write is always
     * followed - never overtaken - by the reload that uses it.
     */
    private val io: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Stagecraft I/O").apply {
            isDaemon = true
            ioThread = this
        }
    }

    /**
     * Everything a build tab does - reading a 50 MB log, a tail tick, stages, tests, lint, rebuild -
     * on its own small pool. On [io] a large log read stalled the build list, its poll and every
     * other tab behind it.
     */
    private val work: ExecutorService = Executors.newFixedThreadPool(WORKERS) { task ->
        Thread(task, "Stagecraft worker").apply { isDaemon = true }
    }

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "Stagecraft poll").apply { isDaemon = true }
        }

    /** The 15 s cadence; [startPolling] decides on each tick whether a load is worth its request. */
    val poller: BuildPoller = BuildPoller(scheduler)

    private var pollHandle: PollerHandle? = null

    /** Set by a user's Cancel: the cadence stays stopped until they ask for a load again (§15.5). */
    @Volatile
    private var pollingPaused = false

    private var pollTicks = 0L

    private val activated = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The licence verdict and when it was taken; re-checked every [LICENSE_RECHECK_MILLIS]. */
    @Volatile
    private var licence: Pair<Boolean?, Long>? = null

    /** One state per project, so two views of one project cannot disagree about it. */
    val viewModel: BuildsViewModel = BuildsViewModel(
        DefaultBuildsLoader(credentials, ::clientFor, cacheDir),
        io,
    )

    init {
        // A build that appears after the window was already showing a list gets one balloon (§7.2).
        viewModel.onBuildFinished = { NotificationService.getInstance(project).onNewBuild(it) }
    }

    /**
     * Cache-only paint first, full load second - both off the EDT, both arriving through
     * [BuildsViewModel.onState]. The tool window calls this when its content is created.
     *
     * The placeholder is published before git runs, so a configured project never sits on the
     * panel's "no Jenkins server yet" placeholder while subprocesses and the disk are busy.
     */
    fun activate() {
        // The project-open activity and the tool window both call this; only the first does work.
        if (!activated.compareAndSet(false, true)) return
        io.execute {
            try {
                firstLoad()
            } catch (failure: Exception) {
                // Nothing here may leave the window on "Loading" for good: log it and load anyway.
                LOG.warn("Stagecraft's first paint failed; loading without it", failure)
                reload()
            }
        }
    }

    private fun firstLoad() {
        val startedAt = System.nanoTime()
        viewModel.showPlaceholder(settings.state.isConfigured)
        if (licensed() == false) {
            viewModel.showUnlicensed()
            return
        }

        // Paint from the last known branch before git runs. The Day 5-6 measurement put git on
        // the critical path at 1210 ms under IDE-startup load, over the 200 ms budget, and the
        // matcher cannot run without a branch; the cached context is what lets the first paint
        // be disk-only (§9.7). It is refined below by the real git answer.
        val cachedContext = BranchContextCache.load(cacheDir) ?: BranchContext(null, null, null)
        viewModel.paintFirst(settings.state, cachedContext)
        val afterPaintMillis = millisSince(startedAt)

        val context = gitContext().asBranchContext()
        // Best effort: a cache that cannot be written costs the next start its warm paint, not
        // this one its load.
        runCatching { BranchContextCache.save(cacheDir, context) }
            .onFailure { LOG.info("Stagecraft could not cache the branch context: ${it.message}") }
        val afterGitMillis = millisSince(startedAt)

        // The cached match is the half of the first paint Stagecraft owns; the EDT being busy
        // with the rest of the IDE is not. Logging both keeps the two apart (§9.7).
        LOG.info(
            "First paint computed in $afterPaintMillis ms (from the cached branch context); " +
                "git answered at ${afterGitMillis}ms",
        )

        viewModel.refresh(settings.state, context)
    }

    /**
     * §7.2 balloons need loads to happen, and the panel is not always open. Every 15 s tick decides:
     * a visible window or a build still running on this branch is worth a load each tick; otherwise
     * one load a minute, a single bounded request, is enough to notice a build that was pushed and
     * finished while nobody was looking. Paused by Cancel until the user asks again.
     */
    fun startPolling() {
        synchronized(this) {
            pollingPaused = false
            if (pollHandle != null) return
            pollHandle = poller.start(::pollTick)
        }
    }

    /** §15.5: a Cancel stops the cadence too, or the next tick would undo it. */
    fun pausePolling() {
        pollingPaused = true
    }

    private fun pollTick() {
        if (pollingPaused || !settings.state.isConfigured) return
        val tick = pollTicks++
        ApplicationManager.getApplication().invokeLater({
            if (pollingPaused) return@invokeLater
            val visible = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.isVisible == true
            if (visible || viewModel.hasRunningBuild || tick % HIDDEN_POLL_EVERY_TICKS == 0L) refresh()
        }, project.disposed)
    }

    /**
     * The licence verdict, re-checked every few minutes. Null while the IDE's licensing facade is
     * still starting, which is treated as "allowed" rather than locking the user out (§8.3).
     */
    private fun licensed(): Boolean? {
        val now = System.currentTimeMillis()
        licence?.let { (verdict, at) -> if (now - at < LICENSE_RECHECK_MILLIS && verdict != null) return verdict }
        val verdict = try {
            LicenseCheck.isLicensed()
        } catch (failure: Exception) {
            LOG.warn("Stagecraft could not read its licence", failure)
            null
        }
        licence = verdict to now
        return verdict
    }

    private fun millisSince(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    /**
     * The token the password safe holds for [serverUrl], read off the EDT and handed back on it.
     *
     * The settings dialog cannot read the safe itself: `PasswordSafe.get` is guarded by
     * `SlowOperations`, and the platform calls `Configurable.isModified` on the EDT on every
     * keystroke, so a read there freezes the IDE and is reported as this plugin's fault (§7.3
     * rule 2). Reading on [io] also puts the read in the same queue as [updateCredentials]'s write,
     * so a read can never land after a write and show a token that is already out of date.
     */
    fun readToken(serverUrl: String, user: String, onLoaded: (String) -> Unit) {
        io.execute {
            val token = credentials.token(serverUrl, user).orEmpty()
            // `any()`, not a bare `invokeLater`: from a background thread a bare call posts with
            // `defaultModalityState()`, which off the EDT is `nonModal()` - "the state when no modal
            // dialogs are open" - and a runnable posted in that state is deferred until the dialog
            // closes, i.e. until after the user has stopped looking at the field. Capturing
            // `current()` or `stateForComponent` instead would be precise only if `reset` happened to
            // run while the dialog was already modal, which a configurable cannot rely on; `any()` is
            // timing-independent. It is allowed here because [onLoaded] touches nothing but the
            // dialog's own fields and its help text - no PSI, no VFS, no project model.
            ApplicationManager.getApplication()
                .invokeLater({ onLoaded(token) }, ModalityState.any())
        }
    }

    /**
     * Writes what the settings dialog changed and then reloads, in that order, on [io].
     *
     * The password safe refuses `set` on the EDT as well, and the order is the point: the reload
     * reads the token this write just stored rather than the one it replaced, so a new token takes
     * effect as soon as the dialog closes.
     */
    fun updateCredentials(change: () -> Unit) {
        io.execute {
            try {
                change()
            } catch (failure: Exception) {
                LOG.warn("Could not write the Stagecraft credential", failure)
            }
            reload()
        }
        if (settings.state.isConfigured) startPolling()
    }

    /** Re-reads git and Jenkins. Safe to call from the EDT: the work happens on [io]. */
    fun refresh() {
        pollingPaused = false
        io.execute { reload() }
    }

    /**
     * §15.5: stop a load the user is no longer willing to wait for. Safe to call from the EDT - it
     * only publishes a state and bumps the generation the in-flight load checks before it reports.
     */
    fun cancel() {
        pausePolling()
        viewModel.cancel()
        // Close the socket the build-list load is blocked on, so Retry does not queue behind it for
        // up to the 20 s read timeout. Only [io]'s request: a log read on [work] is left alone.
        val thread = ioThread ?: return
        synchronized(this) { client?.second }?.http?.abortRequestsOn(thread)
    }

    /**
     * §9.7: read a build's console once, bounded, off the EDT, and hand the result back on it.
     *
     * The read streams `progressiveText` from zero rather than `/consoleText`: it is the same bytes
     * once the reader has stripped the console notes and normalised CRLF, and it also returns the
     * cursor, so a running build can then be followed with deltas instead of re-fetched. The reader
     * keeps only the head and a rolling tail, so a 180 MB log never lands in the heap.
     */
    fun readConsole(build: BuildRef, onDone: (Result<ConsoleRead>) -> Unit) {
        work.execute {
            val result = runCatching {
                val client = currentClientOrNull()
                    ?: error("Stagecraft is not configured, so it cannot read a log.")
                var cursor = 0L
                var more = false
                val log = client.withProgressiveText(build.url) { reader, handle ->
                    cursor = handle.nextOffset
                    more = handle.moreData
                    ConsoleLogReader().read(reader)
                }
                val tailId = if (more) {
                    val id = nextTailId.incrementAndGet()
                    synchronized(tails) { tails[id] = Tail(build.url, ConsoleTailer(client, build.url, cursor)) }
                    id
                } else {
                    null
                }
                ConsoleRead(log, cursor, more, tailId)
            }
            post(onDone, result)
        }
    }

    /**
     * One live-tail poll (§9.5) for the tab holding [tailId]. Each tab owns its cursor, so two tabs on
     * one build, or one tab closing, cannot steal or reset another's. A tick that finds the previous
     * one still on the wire is skipped rather than queued, so a slow server never builds a backlog.
     * Returns false when the tick was skipped or the tail is gone.
     */
    fun tailConsole(tailId: Long, onDelta: (Result<TailDelta>) -> Unit): Boolean {
        val tail = synchronized(tails) { tails[tailId] } ?: return false
        if (!tail.busy.compareAndSet(false, true)) return false
        work.execute {
            val result = try {
                runCatching {
                    // The client may have been rebuilt (a settings change): continue the same cursor
                    // on the new one rather than restart the log from zero.
                    val client = currentClientOrNull()
                        ?: error("Stagecraft is not configured, so it cannot follow a log.")
                    tail.tailerFor(client).poll()
                }
            } finally {
                tail.busy.set(false)
            }
            post(onDelta, result)
        }
        return true
    }

    /** The log tab is closing: drop its cursor. */
    fun stopTailing(tailId: Long) {
        synchronized(tails) { tails.remove(tailId) }
    }

    /**
     * §9.3 fallback chain, off the EDT: wfapi first (real statuses and timings), console parse
     * second (exact boundaries, no status). A 404 from wfapi is the normal "no stage view" answer,
     * not an error.
     */
    fun readStages(build: BuildRef, onDone: (Result<StageView>) -> Unit) {
        work.execute {
            val result = runCatching {
                val client = currentClientOrNull()
                    ?: error("Stagecraft is not configured, so it cannot read stages.")
                // Any stage-view failure - a 404 (plugin absent), a 500, a 403 on that one endpoint -
                // falls back to the console parse rather than costing the user the stage list.
                val wfapi = try {
                    client.wfapiDescribe(build.url)
                } catch (failure: JenkinsException) {
                    LOG.info("Stagecraft stage view unavailable for ${build.url}: ${failure.message}")
                    null
                }
                wfapi?.let { StageView.fromWfapi(it) }
                    // Streamed line by line: the parse keeps only its stack, so a log of any size works.
                    ?: StageView.fromConsole(client.withConsoleText(build.url) { reader -> ConsoleStages.parse(reader) })
            }
            post(onDone, result)
        }
    }

    /**
     * §7.4: lint a Jenkinsfile with the server's own validator. The caller passes the **editor
     * buffer**, so an unsaved edit is linted - the incumbent's most-quoted defect.
     */
    fun lintJenkinsfile(jenkinsfile: String, onDone: (Result<LintResult>) -> Unit) {
        work.execute {
            val result = runCatching {
                val client = currentClientOrNull()
                    ?: error("Stagecraft is not configured, so it cannot lint a Jenkinsfile.")
                LintClient(client).validate(jenkinsfile)
            }
            post(onDone, result)
        }
    }

    /**
     * §7.4 "Re-run with parameters": trigger the job again with the parameters [build] ran with, so a
     * parameterized job is re-run as it was rather than refused. Feature-gated honestly by
     * [JenkinsClient.triggerBuild].
     */
    fun triggerBuild(build: BuildRef, onDone: (Result<RebuildResult>) -> Unit) {
        work.execute {
            val result = runCatching {
                val client = currentClientOrNull()
                    ?: error("Stagecraft is not configured, so it cannot rebuild.")
                val parameters = client.buildParameters(build.url)
                client.triggerBuild(build.jobRawPath, parameters, parameterized = parameters.isNotEmpty())
            }
            post(onDone, result)
        }
    }

    /** Whether [build]'s job can be triggered at all (not disabled, buildable), for the Rebuild button. */
    fun canRebuild(build: BuildRef, onDone: (Result<Boolean>) -> Unit) {
        work.execute {
            val result = runCatching {
                val client = currentClientOrNull() ?: return@runCatching false
                client.isBuildable(build.jobRawPath)
            }
            post(onDone, result)
        }
    }

    /** §9.6: the build's test report, or null when it published none. Off the EDT. */
    fun readTests(build: BuildRef, onDone: (Result<TestReport?>) -> Unit) {
        work.execute {
            val result = runCatching {
                val client = currentClientOrNull()
                    ?: error("Stagecraft is not configured, so it cannot read tests.")
                client.testReport(build.url)
            }
            post(onDone, result)
        }
    }

    private fun <T> post(onDone: (Result<T>) -> Unit, result: Result<T>) {
        // `any()` so the callback is not deferred until a modal dialog closes (see [readToken]).
        ApplicationManager.getApplication().invokeLater({ onDone(result) }, ModalityState.any())
    }

    /**
     * The current settings' client; null when the project is not configured or has no token. Throws
     * when the licence has ended, so every build-tab feature says so instead of working past it.
     */
    private fun currentClientOrNull(): JenkinsClient? {
        val state = settings.state
        if (!state.isConfigured) return null
        if (licensed() == false) error(UNLICENSED_MESSAGE)
        val token = credentials.token(state.serverUrl, state.user) ?: return null
        return clientFor(JenkinsUrls.normalizeBase(state.serverUrl), state.user, token)
    }

    /** Reads the project settings and git, so it only ever runs on [io]. */
    private fun reload() {
        if (licensed() == false) {
            viewModel.showUnlicensed()
            return
        }
        viewModel.refresh(settings.state, gitContext().asBranchContext())
    }

    /** The project's git remote and branch. Starts processes, so never call it on the EDT. */
    fun gitContext(): GitContext = GitProbe(project.basePath?.let { File(it) }).read()

    override fun dispose() {
        io.shutdownNow()
        work.shutdownNow()
        scheduler.shutdownNow()
    }

    /** What a [JenkinsClient] was built from; any change means a new client. */
    private data class ClientKey(
        val baseUrl: String,
        val user: String,
        val token: String,
        val secretIsPassword: Boolean,
        val useProxy: Boolean,
        val trustCertificate: Boolean,
    )

    /** Shared by [io] and [work]; every access holds the service's monitor. */
    private var client: Pair<ClientKey, JenkinsClient>? = null

    /** One live tail per log tab, keyed by the id [readConsole] handed out. */
    private class Tail(val buildUrl: String, private var tailer: ConsoleTailer) {
        /** A tick is on the wire; the next one is skipped rather than queued behind it. */
        val busy = java.util.concurrent.atomic.AtomicBoolean(false)

        @Synchronized
        fun tailerFor(client: JenkinsClient): ConsoleTailer {
            if (!tailer.usesClient(client)) tailer = tailer.continueWith(client)
            return tailer
        }
    }

    private val tails = HashMap<Long, Tail>()
    private val nextTailId = java.util.concurrent.atomic.AtomicLong()

    /**
     * A client wired to the IDE's own proxy and trust settings (§9.2), reused for as long as the
     * settings it was built from stand. Reuse is the point: one client is one cookie jar and one crumb
     * cache per server (§9.2), where a client per load would open a new Jenkins session on every poll
     * with password auth and throw the crumb away each time.
     */
    @Synchronized
    private fun clientFor(baseUrl: String, user: String, token: String): JenkinsClient {
        val state = settings.state
        val key = ClientKey(baseUrl, user, token, state.secretIsPassword, state.useProxy, state.trustCertificate)
        client?.let { (builtFrom, existing) -> if (builtFrom == key) return existing }

        val credential = if (state.secretIsPassword) {
            JenkinsCredential.Password(user, token)
        } else {
            JenkinsCredential.ApiToken(user, token)
        }
        val fresh = JenkinsClient(
            baseUrl = baseUrl,
            auth = JenkinsAuth(credential),
            transport = UrlConnectionTransport(
                proxySelector = proxySelector(state),
                sslContext = sslContext(state),
            ),
        )
        // Open tails keep their cursors: the next tick continues them on this client (Tail.tailerFor).
        client = key to fresh
        return fresh
    }

    /**
     * IntelliJ's proxy settings, including the "No proxy for" list, rather than the JVM's
     * `-Dhttp.proxyHost` flags: a user who configured a proxy in the IDE expects the plugin to use
     * it, and one who configured an exception expects the exception to be honoured (§7.3 rule 4).
     *
     * With the box clear the connection is forced to be direct - `ProxySelector.of(null)` means
     * "no proxy", not "whatever the JVM was started with".
     */
    private fun proxySelector(state: StagecraftState): ProxySelector =
        if (state.useProxy) ideProxySelector() else ProxySelector.of(null)

    /**
     * 2024.2 replaced `HttpConfigurable` with `JdkProxyProvider`, and the old class is deprecated
     * for removal. The new one is looked up reflectively so the plugin keeps loading on every IDE
     * from the floor up, whichever of the two that IDE has; the old one is the fallback, not the
     * default.
     */
    private fun ideProxySelector(): ProxySelector =
        try {
            val provider = Class.forName("com.intellij.util.net.JdkProxyProvider")
            val instance = provider.getMethod("getInstance").invoke(null)
            provider.getMethod("getProxySelector").invoke(instance) as ProxySelector
        } catch (_: ReflectiveOperationException) {
            legacyProxySelector()
        } catch (_: LinkageError) {
            legacyProxySelector()
        }

    @Suppress("DEPRECATION")
    private fun legacyProxySelector(): ProxySelector = HttpConfigurable.getInstance().onlyBySettingsSelector

    /**
     * "Trust this server's certificate" means "use the IDE's trust store", which already holds the
     * certificates the user added under Settings > Tools > Server Certificates.
     *
     * With the box clear the JDK default is used, so a certificate the machine does not trust fails
     * loudly instead of being accepted silently (§9.2: a certificate is trusted explicitly or not
     * at all).
     */
    private fun sslContext(state: StagecraftState): SSLContext? =
        if (state.trustCertificate) CertificateManager.getInstance().sslContext else null

    companion object {

        private val LOG = logger<JenkinsService>()

        private const val CACHE_DIR_NAME = "stagecraft"

        /** The tool window id declared in plugin.xml. */
        const val TOOL_WINDOW_ID = "Stagecraft"

        /** Threads for build-tab work, so a log read never blocks the build list or another tab. */
        private const val WORKERS = 3

        /** Hidden window, nothing running: one load every 4 ticks (a minute). */
        private const val HIDDEN_POLL_EVERY_TICKS = 4L

        private const val LICENSE_RECHECK_MILLIS = 10L * 60 * 1000

        const val UNLICENSED_MESSAGE =
            "Stagecraft's trial has ended and no licence was found. Buy or register a licence under " +
                "Help > Register (or Help > Manage Licenses)."

        /**
         * A single path segment naming one project. [Project.locationHash] is the platform's hash of
         * the project's location; filtering keeps a surprising value from escaping the directory.
         */
        private fun cacheDirKey(project: Project): String {
            val hash = project.locationHash.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            return hash.ifEmpty { Integer.toHexString(project.basePath.hashCode()) }
        }

        fun getInstance(project: Project): JenkinsService =
            project.getService(JenkinsService::class.java)
    }
}
