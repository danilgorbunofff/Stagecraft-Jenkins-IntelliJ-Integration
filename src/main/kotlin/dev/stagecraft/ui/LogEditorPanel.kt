package dev.stagecraft.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.stagecraft.jenkins.LineKind
import dev.stagecraft.jenkins.LogChange
import dev.stagecraft.jenkins.LogFilterMode
import dev.stagecraft.jenkins.LogView
import dev.stagecraft.jenkins.StackFrame
import dev.stagecraft.jenkins.StackFrames
import dev.stagecraft.jenkins.Stripe
import dev.stagecraft.jenkins.TailDelta
import dev.stagecraft.model.BuildRef
import dev.stagecraft.service.ConsoleRead
import dev.stagecraft.service.JenkinsService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JPanel

/**
 * The log view (§7.2, §9.5, §9.7): a real read-only [Editor] over a memory-bounded [LogView].
 *
 * The editor is the platform's own, not a hand-rolled text widget, so search, selection, folding and
 * accessibility come for free. Error and warning lines get a gutter bar, a mark on the error stripe
 * and a tinted background,
 * the view opens scrolled to the **first error** rather than the end, and a running build's log
 * grows through `progressiveText` deltas.
 *
 * Everything the panel needs was decided headlessly: the reader caps memory, [LogView] keeps it
 * capped while tailing and maps full-log line numbers through truncation and the filter, and the
 * tailer is a tested protocol. This class only paints the result and never blocks the EDT - every
 * read and poll runs on `JenkinsService`'s worker pool and arrives back through `invokeLater`.
 */
class LogEditorPanel(
    private val project: Project,
    private val service: JenkinsService,
    private val build: BuildRef,
    private val isShowing: () -> Boolean = { true },
) : JPanel(BorderLayout()), Disposable {

    private val document = EditorFactory.getInstance().createDocument("").also {
        // A read-only log needs no undo; recording it would keep old copies of a 22 M-char text alive.
        UndoUtil.disableUndoFor(it)
    }
    private val editor: Editor =
        EditorFactory.getInstance().createEditor(document, project, PlainTextFileType.INSTANCE, true)

    private val status = JBLabel()
    private val banner = JBLabel()
    private val filter = JComboBox(FILTERS)
    private val collapse = JCheckBox("collapse blanks")
    private val openInJenkins = JButton("Open in Jenkins")

    /** The retained log and its filter; null until the first read lands. */
    private var view: LogView? = null
    private var tailId: Long? = null

    /** EDT-only. Every callback that arrives after the tab closed is dropped on this flag. */
    private var disposed = false

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "Stagecraft log tail").apply { isDaemon = true }
        }
    private var tailFuture: ScheduledFuture<*>? = null

    /** A stage clicked before the log arrived; applied once it has. */
    private var pendingStage: Pair<String, Int?>? = null

    /** The stack frames currently underlined, so a Ctrl-click can be mapped to one by offset. */
    private val hyperlinkFrames = ArrayList<StackFrame>()

    init {
        status.foreground = UIUtil.getInactiveTextColor()
        banner.foreground = UIUtil.getErrorForeground()

        filter.addActionListener { applyFilter() }
        collapse.addActionListener { applyFilter() }
        openInJenkins.addActionListener { BrowserUtil.browse(build.url) }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JBLabel("Filter:"))
            add(filter)
            add(collapse)
            add(openInJenkins)
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("<html><b>${escape(build.jobFullName)} ${escape(build.displayName)}</b></html>"), BorderLayout.NORTH)
            add(toolbar, BorderLayout.CENTER)
            add(banner, BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)
        add(editor.component, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)

        // §9.6: Ctrl/Cmd-click on a stack frame opens its source line, or does nothing when the file
        // is not in the project.
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) {
                val awt = event.mouseEvent
                if (!(awt.isControlDown || awt.isMetaDown)) return
                val offset = event.offset
                val frame = hyperlinkFrames.firstOrNull { offset >= it.start && offset < it.end } ?: return
                SourceNavigator.navigate(project, frame)
            }
        })

        status.text = "Loading the console of ${build.displayName}…"
        service.readConsole(build) { result -> if (!disposed) result.fold(::showLog, ::showFailure) }
    }

    /**
     * Scroll to stage [name], whose first line in the full log is [firstLine] when the console parse
     * knows it. The stage's own `[Pipeline] { (name)` line is looked up in what is shown - which works
     * on the stage-view path too, where no line range exists - and the full-log line number is mapped
     * through truncation and the filter otherwise.
     */
    fun scrollToStage(name: String, firstLine: Int?) {
        val view = view
        if (view == null) {
            pendingStage = name to firstLine
            return
        }
        val line = view.stageDocumentLine(name) ?: firstLine?.let(view::documentLine) ?: return
        scrollToDocumentLine(line)
    }

    private fun scrollToDocumentLine(line: Int) {
        ApplicationManager.getApplication().invokeLater({
            if (disposed || document.lineCount == 0) return@invokeLater
            val offset = document.getLineStartOffset(line.coerceIn(0, document.lineCount - 1))
            editor.caretModel.moveToOffset(offset)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }, project.disposed)
    }

    private fun showLog(read: ConsoleRead) {
        val view = LogView(read.log)
        this.view = view
        tailId = read.tailId
        view.setLive(read.moreData)
        banner.text = read.log.truncationBanner.orEmpty()
        banner.isVisible = read.log.truncationBanner != null
        applyFilter()
        val pending = pendingStage
        pendingStage = null
        if (pending != null) {
            scrollToStage(pending.first, pending.second)
        } else {
            // §7.2: open at the first error, never at the end.
            view.firstErrorDocumentLine()?.let(::scrollToDocumentLine)
        }
        if (read.moreData && read.tailId != null) startTailing()
    }

    private fun showFailure(failure: Throwable) {
        status.text = "Stagecraft could not read this log: ${failure.message ?: failure.javaClass.simpleName}"
    }

    /**
     * Rebuild the editor's text for the current filter. The retained log is local, so a filter change
     * is never a network call.
     */
    private fun applyFilter() {
        val view = view ?: return
        val mode = filter.selectedItem as? LogFilterMode ?: LogFilterMode.ALL
        apply(view.setFilter(mode, collapse.isSelected))
    }

    /** Put a [LogChange] into the document, with its stripes and hyperlinks. */
    private fun apply(change: LogChange) {
        when (change) {
            is LogChange.Replace -> {
                WriteCommandAction.runWriteCommandAction(project) { document.setText(change.text) }
                editor.markupModel.removeAllHighlighters()
                hyperlinkFrames.clear()
                addStripes(change.stripes)
                addHyperlinks(change.text, 0)
            }
            is LogChange.Append -> {
                val base = document.textLength
                if (change.text.isNotEmpty()) {
                    WriteCommandAction.runWriteCommandAction(project) { document.insertString(base, change.text) }
                }
                addStripes(change.stripes)
                addHyperlinks(change.text, base)
            }
            LogChange.None -> Unit
        }
        updateStatus()
    }

    private fun updateStatus() {
        val view = view ?: return
        val error = view.firstErrorLine
        val shown = error != null && view.firstErrorDocumentLine() != null
        status.text = listOfNotNull(
            "${view.totalLines} lines",
            error?.let { if (shown) "first error at line $it" else "first error at line $it (not shown here)" },
            if (view.mode == LogFilterMode.ALL) null else "filtered: ${view.mode.name.lowercase()}",
            if (view.live) "following live output" else null,
        ).joinToString("  ·  ")
    }

    /** Gutter bar, error-stripe mark and a light tint per error/warning line ([LogView] caps the count). */
    private fun addStripes(stripes: List<Stripe>) {
        for (stripe in stripes) {
            if (stripe.line >= document.lineCount) continue
            val colour = if (stripe.kind == LineKind.ERROR) UIUtil.getErrorForeground() else WARNING_COLOR
            val start = document.getLineStartOffset(stripe.line)
            val end = document.getLineEndOffset(stripe.line)
            val highlighter = editor.markupModel.addRangeHighlighter(
                start,
                end,
                HighlighterLayer.ERROR,
                TextAttributes(null, translucent(colour), null, null, Font.PLAIN),
                HighlighterTargetArea.LINES_IN_RANGE,
            )
            highlighter.setErrorStripeMarkColor(colour)
            highlighter.isThinErrorStripeMark = stripe.kind == LineKind.WARNING
            highlighter.lineMarkerRenderer = LineMarkerRenderer { _, graphics, rectangle ->
                graphics.color = colour
                graphics.fillRect(rectangle.x, rectangle.y, JBUI.scale(3), rectangle.height)
            }
        }
    }

    /**
     * §9.6: underline every stack frame / compiler location in [text], which starts at document
     * offset [base]. Capped so a pathological log cannot add an unbounded number of highlighters.
     */
    private fun addHyperlinks(text: String, base: Int) {
        val room = MAX_HYPERLINKS - hyperlinkFrames.size
        if (room <= 0 || text.isEmpty()) return
        val attributes = linkAttributes()
        for (frame in StackFrames.find(text, limit = room)) {
            val shifted = frame.copy(start = frame.start + base, end = frame.end + base)
            hyperlinkFrames += shifted
            editor.markupModel.addRangeHighlighter(
                shifted.start,
                shifted.end,
                HighlighterLayer.SYNTAX,
                attributes,
                HighlighterTargetArea.EXACT_RANGE,
            )
        }
    }

    private fun linkAttributes(): TextAttributes = TextAttributes(
        null,
        null,
        JBColor.BLUE,
        EffectType.LINE_UNDERSCORE,
        Font.PLAIN,
    )

    private fun translucent(colour: Color): Color = Color(colour.red, colour.green, colour.blue, ALPHA)

    /**
     * §9.5: poll `progressiveText` every two seconds while the build runs, appending only the delta.
     * Stops when Jenkins omits `X-More-Data`, or on the first failure (a dead server is reported, not
     * retried into an infinite loop). The tick decides on the EDT, where the window's visibility may
     * be read; a tick whose previous request is still on the wire is skipped by the service.
     */
    private fun startTailing() {
        if (tailFuture != null || disposed) return
        val id = tailId ?: return
        tailFuture = scheduler.scheduleWithFixedDelay(
            {
                ApplicationManager.getApplication().invokeLater({
                    if (!disposed && isShowing()) {
                        service.tailConsole(id) { result -> if (!disposed) result.fold(::appendDelta, ::tailFailed) }
                    }
                }, project.disposed)
            },
            TAIL_PERIOD_MILLIS,
            TAIL_PERIOD_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun appendDelta(delta: TailDelta) {
        val view = view ?: return
        if (delta.resetDetected) {
            // Jenkins reset to zero and re-sent the whole log: replace rather than duplicate.
            apply(view.reset(dev.stagecraft.jenkins.ConsoleLogReader().read(delta.text)))
        } else {
            apply(view.append(delta.text))
        }
        if (!delta.moreData) {
            apply(view.setLive(false))
            stopTailing()
        }
    }

    private fun tailFailed(failure: Throwable) {
        status.text = "Live output stopped: ${failure.message ?: failure.javaClass.simpleName}"
        stopTailing()
    }

    private fun stopTailing() {
        tailFuture?.cancel(false)
        tailFuture = null
    }

    override fun dispose() {
        disposed = true
        stopTailing()
        scheduler.shutdownNow()
        tailId?.let(service::stopTailing)
        EditorFactory.getInstance().releaseEditor(editor)
    }

    private companion object {
        /** §9.5: two-second cadence. */
        const val TAIL_PERIOD_MILLIS = 2_000L

        const val MAX_HYPERLINKS = 1_000
        const val ALPHA = 40

        /** Theme-neutral amber, so the warning stripe reads on light and dark schemes alike. */
        val WARNING_COLOR = Color(0xC8, 0x8A, 0x00)

        /** The combo's items are the enum itself; [LogFilterMode] renders its own name in the list. */
        val FILTERS = LogFilterMode.entries.toTypedArray()

        /** Job names may contain `&` and `<`, and the header is HTML. */
        fun escape(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
