package dev.stagecraft.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.net.HttpConfigurable
import com.intellij.util.net.ssl.CertificateManager
import dev.stagecraft.jenkins.JenkinsAuth
import dev.stagecraft.jenkins.JenkinsClient
import dev.stagecraft.jenkins.JenkinsCredential
import dev.stagecraft.jenkins.UrlConnectionTransport
import java.io.File
import java.net.ProxySelector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import javax.net.ssl.SSLContext

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

    private val io: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Stagecraft I/O").apply { isDaemon = true }
    }

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "Stagecraft poll").apply { isDaemon = true }
        }

    /** Quiet refresh while a tool window is showing; live console output reuses it on Day 7. */
    val poller: BuildPoller = BuildPoller(scheduler)

    /** One state per project, so two views of one project cannot disagree about it. */
    val viewModel: BuildsViewModel = BuildsViewModel(
        DefaultBuildsLoader(credentials, ::clientFor, cacheDir),
        io,
    )

    /**
     * Cache-only paint first, full load second - both off the EDT, both arriving through
     * [BuildsViewModel.onState]. The tool window calls this when its content is created.
     *
     * The placeholder is published before git runs, so a configured project never sits on the
     * panel's "no Jenkins server yet" placeholder while subprocesses and the disk are busy.
     */
    fun activate() {
        io.execute {
            val startedAt = System.nanoTime()
            viewModel.showPlaceholder(settings.state.isConfigured)

            val context = gitContext().asBranchContext()
            val afterGitMillis = millisSince(startedAt)

            viewModel.paintFirst(settings.state, context)
            val afterPaintMillis = millisSince(startedAt)

            // The cached match is the half of the first paint Stagecraft owns; the EDT being busy
            // with the rest of the IDE is not. Logging both keeps the two apart (§9.7).
            LOG.info(
                "First paint computed in $afterPaintMillis ms " +
                    "(git ${afterGitMillis}ms, cached match ${afterPaintMillis - afterGitMillis}ms)",
            )

            viewModel.refresh(settings.state, context)
        }
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
    }

    /** Re-reads git and Jenkins. Safe to call from the EDT: the work happens on [io]. */
    fun refresh() {
        io.execute { reload() }
    }

    /** Reads the project settings and git, so it only ever runs on [io]. */
    private fun reload() {
        viewModel.refresh(settings.state, gitContext().asBranchContext())
    }

    /** The project's git remote and branch. Starts processes, so never call it on the EDT. */
    fun gitContext(): GitContext = GitProbe(project.basePath?.let { File(it) }).read()

    override fun dispose() {
        io.shutdownNow()
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

    /** Only touched on [io], which runs one task at a time. */
    private var client: Pair<ClientKey, JenkinsClient>? = null

    /**
     * A client wired to the IDE's own proxy and trust settings (§9.2), reused for as long as the
     * settings it was built from stand. Reuse is the point: one client is one cookie jar and one crumb
     * cache per server (§9.2), where a client per load would open a new Jenkins session on every poll
     * with password auth and throw the crumb away each time.
     */
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
