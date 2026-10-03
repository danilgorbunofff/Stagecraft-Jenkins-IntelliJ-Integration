package dev.stagecraft.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.stagecraft.model.BuildRef
import dev.stagecraft.model.BuildStatus
import dev.stagecraft.service.BuildsViewModel
import dev.stagecraft.service.JenkinsService
import dev.stagecraft.service.PollerHandle
import dev.stagecraft.service.ToolWindowState
import dev.stagecraft.ui.settings.StagecraftConfigurable
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel

/**
 * The tool window's contents: the current branch's builds, newest first.
 *
 * It renders [ToolWindowState] and does nothing else. Every load belongs to `JenkinsService` and
 * every decision about *what* to show was made by the headless service layer that the tests cover,
 * so this class is deliberately the thinnest thing that can put it on screen.
 *
 * [render] is the only method that touches Swing, and it is always reached through `invokeLater`,
 * so a load that finishes on a background thread can never paint (§7.3 rule 2).
 */
class BuildTreePanel(
    private val project: Project,
    private val service: JenkinsService,
    private val isShowing: () -> Boolean = { true },
    private val openLog: (BuildRef) -> Unit = {},
) : JPanel(BorderLayout()), Disposable {

    private val viewModel: BuildsViewModel = service.viewModel

    /**
     * When this content was created, so both paints below can be reported against the first-paint
     * budget of README 9.7 instead of being assumed to be inside it.
     */
    private val contentCreatedAt = System.nanoTime()
    private var placeholderPainted = false
    private var contentReported = false

    /** The last "checking" state, so the line that reports first content can name the wait. */
    private var lastUnsettled: ToolWindowState? = null

    /** The last failure already written to the log, so a poll that keeps failing logs once. */
    private var reportedFailure: String? = null

    private val model = CollectionListModel<BuildRef>(arrayListOf())
    private val list = JBList(model)

    private val title = JBLabel()

    /** The look and feel's own text colour, taken before any state overrides it. */
    private val labelForeground: Color = title.foreground ?: UIUtil.getInactiveTextColor()

    private val note = wrappingLabel(UIUtil.getInactiveTextColor())
    private val warning = wrappingLabel(UIUtil.getErrorForeground())
    private val message = wrappingLabel(labelForeground)

    private val refreshButton = JButton("Refresh")
    private val settingsButton = JButton("Settings...")
    private val openLogButton = JButton("Open log")
    private val retryButton = JButton("Retry")
    private val configureButton = JButton("Configure...")

    /** §15.5: no loading state may last more than 200 ms without a cancel button. */
    private val cancelButton = JButton("Cancel")

    private val cards = CardLayout()
    private val body = JPanel(cards)

    private var poller: PollerHandle? = null

    /**
     * Held in a field and compared by identity on [dispose], so a panel that is being thrown away
     * cannot unhook the handler of the panel that replaced it.
     */
    private val stateHandler: (ToolWindowState) -> Unit = { state ->
        ApplicationManager.getApplication().invokeLater({ if (!disposed) render(state) }, project.disposed)
    }

    /** Set on [dispose]; a state that arrives afterwards is for a panel nobody can see. */
    @Volatile
    private var disposed = false

    init {
        border = BorderFactory.createEmptyBorder(JBUI.scale(6), JBUI.scale(8), JBUI.scale(6), JBUI.scale(8))

        list.cellRenderer = BuildRenderer()
        list.emptyText.setText("No builds on this branch yet.")
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) openSelectedLog()
            }
        })
        // "Open log" only means something with a build under it; a disabled button says that better
        // than a click that silently does nothing.
        openLogButton.isEnabled = false
        list.addListSelectionListener { openLogButton.isEnabled = list.selectedValue != null }

        refreshButton.addActionListener { resumePolling(); service.refresh() }
        retryButton.addActionListener { resumePolling(); service.refresh() }
        cancelButton.addActionListener { cancelLoad() }
        openLogButton.addActionListener { openSelectedLog() }
        settingsButton.addActionListener { openSettings() }
        configureButton.addActionListener { openSettings() }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(openLogButton)
            add(refreshButton)
            add(settingsButton)
        }

        val headerText = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(title, BorderLayout.NORTH)
            add(note, BorderLayout.CENTER)
            add(warning, BorderLayout.SOUTH)
        }

        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(headerText, BorderLayout.CENTER)
                add(actions, BorderLayout.EAST)
            },
            BorderLayout.NORTH,
        )

        val messageCard = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(message, BorderLayout.NORTH)
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(8))).apply {
                    isOpaque = false
                    add(retryButton)
                    add(cancelButton)
                    add(configureButton)
                },
                BorderLayout.CENTER,
            )
        }

        body.isOpaque = false
        body.add(JBScrollPane(list), CARD_LIST)
        body.add(messageCard, CARD_MESSAGE)
        add(body, BorderLayout.CENTER)

        viewModel.onState = stateHandler
        val painted = viewModel.state
        render(painted)
        placeholderPainted = true
        val firstPaintMs = elapsedMs()
        if (firstPaintMs > FIRST_PAINT_BUDGET_MS) {
            LOG.warn(
                "The Stagecraft tool window took $firstPaintMs ms to paint ${describe(painted)}, " +
                    "over the $FIRST_PAINT_BUDGET_MS ms budget",
            )
        } else {
            LOG.info("The Stagecraft tool window painted in $firstPaintMs ms showing ${describe(painted)}")
        }

        // Quiet refresh, but only while somebody is looking: polling a server nobody is watching
        // is exactly the traffic §7.3 rule 1 exists to prevent.
        startPolling()
    }

    override fun dispose() {
        disposed = true
        poller?.cancel()
        poller = null
        if (viewModel.onState === stateHandler) viewModel.onState = null
    }

    /**
     * Quiet refresh while the tool window is open. Started once in [init] and restarted by Retry or
     * Refresh after a cancel, so a stopped poller stays stopped until the user asks for it again
     * (§15.5: a cancel that silently resumes is not a cancel).
     */
    private fun startPolling() {
        if (poller != null) return
        poller = service.poller.start {
            ApplicationManager.getApplication().invokeLater({ if (!disposed && isShowing()) service.refresh() }, project.disposed)
        }
    }

    /** A user action (Retry/Refresh) means the user wants the cadence back. */
    private fun resumePolling() {
        startPolling()
    }

    /**
     * §15.5: stop waiting without freezing the IDE. The poller is cancelled first so the next tick
     * cannot start a fresh load the user did not ask for, then the view model discards whatever the
     * blocked request eventually answers.
     */
    private fun cancelLoad() {
        poller?.cancel()
        poller = null
        service.cancel()
    }

    private fun render(state: ToolWindowState) {
        reportProgress(state)

        when (state) {
            is ToolWindowState.Ready -> {
                replaceBuilds(state.builds)
                val count = if (state.builds.size == 1) "1 build" else "${state.builds.size} builds"
                // §15.4 #3: cached rows carry their age, so the user knows how stale the first paint is.
                val cache = if (state.fromCache) {
                    val age = state.ageMillis?.let { ", updated ${formatAge(it)}" }.orEmpty()
                    " - from cache$age"
                } else {
                    ""
                }
                title.text = "<html><b>${escape(state.job.displayName)}</b> - $count$cache</html>"
                note.text = "${state.how}. Double-click a build to open its log."
                warning.text = state.versionWarning.orEmpty()
                warning.isVisible = state.versionWarning != null
                showButtons(refresh = true, retry = false, configure = false)
                cards.show(body, CARD_LIST)
            }

            is ToolWindowState.Loading -> {
                title.text = ""
                message.foreground = UIUtil.getInactiveTextColor()
                message.text = state.hint?.let { "Checking $it..." } ?: "Checking this branch's builds..."
                showMessageCard(refresh = false, retry = false, configure = false, cancel = true)
            }

            is ToolWindowState.Empty -> {
                title.text = "<html><b>${escape(state.job.displayName)}</b></html>"
                message.foreground = UIUtil.getInactiveTextColor()
                val checked = state.ageMillis?.let { " (checked ${formatAge(it)})" }.orEmpty()
                message.text = "${state.job.displayName} has no builds yet$checked. ${state.how}."
                showMessageCard(refresh = true, retry = false, configure = false)
            }

            ToolWindowState.Unconfigured -> {
                title.text = "<html><b>Stagecraft</b></html>"
                message.foreground = labelForeground
                message.text = "This project has no Jenkins server yet. Give Stagecraft the server " +
                    "address, your user name and an API token, and it will find the branch's job " +
                    "by itself."
                showMessageCard(refresh = false, retry = false, configure = true)
            }

            is ToolWindowState.Failed -> {
                title.text = ""
                message.foreground = UIUtil.getErrorForeground()
                message.text = state.reason
                showMessageCard(refresh = false, retry = state.retryable, configure = !state.retryable)
            }

            ToolWindowState.Cancelled -> {
                title.text = ""
                message.foreground = labelForeground
                message.text = "Stopped waiting for the server. Nothing is loading; press Retry when " +
                    "you are ready, or check the server address in Settings."
                showMessageCard(refresh = false, retry = true, configure = true)
            }
        }
    }

    /**
     * Swap in a fresh list without losing the user's place: a poll replaces the list every 15
     * seconds, and a selection that jumped away under the cursor would make double-click open the
     * wrong build. An unchanged list is not touched at all.
     */
    private fun replaceBuilds(builds: List<BuildRef>) {
        if (model.items == builds) return
        val selected = list.selectedValue?.url
        model.replaceAll(builds)
        val index = if (selected == null) -1 else builds.indexOfFirst { it.url == selected }
        if (index >= 0) list.selectedIndex = index
    }

    private fun elapsedMs(): Long = (System.nanoTime() - contentCreatedAt) / 1_000_000

    /**
     * One line per tool window, on the two waits the charter puts a number on: the paint itself
     * (README 9.7 budgets 200 ms with a warm cache) and the wait for something that is not
     * "checking" (README 15.4 calls a window that is empty for five seconds on every IDE start a
     * dead product). Keeping the second line - and how long it took - is what makes that a fact
     * rather than an assumption; the "checking" state the wait ended on is named on it so the delay
     * can be attributed without a line per transition.
     *
     * Failures are reported separately and every time the reason changes, because README 15.5
     * forbids silent degradation and an inaccessible server is what a support log has to explain.
     */
    private fun reportProgress(state: ToolWindowState) {
        if (state is ToolWindowState.Loading) {
            if (placeholderPainted) lastUnsettled = state
            return
        }

        if (state is ToolWindowState.Failed) {
            if (reportedFailure != state.reason) {
                reportedFailure = state.reason
                LOG.warn("Stagecraft could not load builds: ${state.reason} (retryable=${state.retryable})")
            }
        } else {
            reportedFailure = null
        }

        if (!placeholderPainted || contentReported) return

        contentReported = true
        val millis = elapsedMs()
        val waited = lastUnsettled?.let { " after ${describe(it)}" }.orEmpty()
        if (millis > EMPTY_WINDOW_BUDGET_MS) {
            LOG.warn("The Stagecraft tool window was empty for $millis ms before ${describe(state)}$waited")
        } else {
            LOG.info("The Stagecraft tool window ${describe(state)} in $millis ms$waited")
        }
    }

    /** One line per state, for the log only; the tool window shows the state's own text. */
    private fun describe(state: ToolWindowState): String = when (state) {
        is ToolWindowState.Ready -> "Ready(${state.builds.size} builds, fromCache=${state.fromCache})"
        is ToolWindowState.Loading -> "Loading(${state.hint ?: "no hint yet"})"
        is ToolWindowState.Empty -> "Empty(${state.job.displayName})"
        ToolWindowState.Unconfigured -> "Unconfigured"
        is ToolWindowState.Failed -> "Failed(retryable=${state.retryable}, ${state.reason})"
        ToolWindowState.Cancelled -> "Cancelled"
    }

    private fun showMessageCard(refresh: Boolean, retry: Boolean, configure: Boolean, cancel: Boolean = false) {
        warning.isVisible = false
        note.text = ""
        showButtons(refresh, retry, configure, cancel)
        cards.show(body, CARD_MESSAGE)
    }

    private fun showButtons(refresh: Boolean, retry: Boolean, configure: Boolean, cancel: Boolean = false) {
        refreshButton.isVisible = refresh
        retryButton.isVisible = retry
        configureButton.isVisible = configure
        cancelButton.isVisible = cancel
    }

    private fun openSelectedLog() {
        val build = list.selectedValue ?: return
        openLog(build)
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, StagecraftConfigurable::class.java)
    }

    /**
     * A label that wraps its text to the width the layout gives it. A plain label measures its
     * preferred width from the unwrapped text, which would clip every explanation and every error
     * message in a tool window this narrow.
     */
    private fun wrappingLabel(colour: Color): JBLabel = JBLabel().apply {
        setAllowAutoWrapping(true)
        foreground = colour
    }

    private inner class BuildRenderer : DefaultListCellRenderer() {

        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val build = value as? BuildRef ?: return this
            text = rowHtml(build)
            toolTipText = "${build.jobFullName} ${build.displayName} - ${build.url}"
            return this
        }
    }

    private companion object {

        const val CARD_LIST = "list"
        const val CARD_MESSAGE = "message"

        /** README 9.7: the tool window's first paint, cache warm. */
        const val FIRST_PAINT_BUDGET_MS = 200L

        /** README 15.4: a tool window that is empty this long on every IDE start is a dead product. */
        const val EMPTY_WINDOW_BUDGET_MS = 5_000L

        private val LOG = logger<BuildTreePanel>()

        /** Only ever touched on the EDT, so one instance is enough. */
        val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm")

        fun rowHtml(build: BuildRef): String {
            val status = statusText(build.status)
            // The status colour is the one signal worth keeping when the row is selected; the rest
            // of the row stays in whatever foreground the look and feel is using.
            val statusHtml = if (build.status == BuildStatus.FAILURE) {
                "<font color='${hex(UIUtil.getErrorForeground())}'>$status</font>"
            } else {
                status
            }
            val duration = if (build.isRunning) "" else " (${formatDuration(build.durationMillis)})"
            return "<html><b>${build.displayName}</b>&nbsp;&nbsp;$statusHtml&nbsp;&nbsp;" +
                "${TIME_FORMAT.format(Date(build.timestampMillis))}$duration</html>"
        }

        fun statusText(status: BuildStatus): String = when (status) {
            BuildStatus.RUNNING -> "running"
            BuildStatus.SUCCESS -> "success"
            BuildStatus.UNSTABLE -> "unstable"
            BuildStatus.FAILURE -> "failed"
            BuildStatus.ABORTED -> "aborted"
            BuildStatus.NOT_BUILT -> "not built"
            BuildStatus.DISABLED -> "disabled"
            BuildStatus.UNKNOWN -> "unknown"
        }

        fun formatDuration(millis: Long): String {
            val seconds = millis / 1000
            return when {
                seconds < 60 -> "${seconds}s"
                seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
                else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            }
        }

        /** §15.4 #3: how stale the cached rows are, in the shortest honest form. */
        fun formatAge(millis: Long): String = when {
            millis < 60_000 -> "just now"
            millis < 3_600_000 -> "${millis / 60_000}m ago"
            millis < 86_400_000 -> "${millis / 3_600_000}h ago"
            else -> "${millis / 86_400_000}d ago"
        }

        /** Job names may contain `&` and `<`, and this text is rendered as HTML. */
        fun escape(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun hex(colour: Color): String = String.format("#%06x", colour.rgb and 0xFFFFFF)
    }
}
