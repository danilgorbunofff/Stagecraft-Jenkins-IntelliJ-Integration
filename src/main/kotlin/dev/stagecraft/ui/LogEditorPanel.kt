package dev.stagecraft.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.stagecraft.jenkins.ConsoleLog
import dev.stagecraft.jenkins.ConsoleStages
import dev.stagecraft.jenkins.LogFilter
import dev.stagecraft.jenkins.LogFilterMode
import dev.stagecraft.jenkins.StackFrame
import dev.stagecraft.jenkins.StackFrames
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
 * The log view (§7.2, §9.5, §9.7): a real read-only [Editor] over a memory-bounded [ConsoleLog].
 *
 * The editor is the platform's own, not a hand-rolled text widget, so search, selection, folding and
 * accessibility come for free. Error and warning lines get a gutter stripe via a `RangeHighlighter`,
 * the view opens scrolled to the **first error** rather than the end, and a running build's log
 * grows through `progressiveText` deltas.
 *
 * Everything the panel needs was decided headlessly: the reader caps memory, the filter is pure
 * Kotlin, and the tailer is a tested protocol. This class only paints the result and never blocks
 * the EDT - every read and poll runs on `JenkinsService`'s I/O thread and arrives back through
 * `invokeLater`.
 */
class LogEditorPanel(
    private val project: Project,
    private val service: JenkinsService,
    private val build: BuildRef,
    private val isShowing: () -> Boolean = { true },
) : JPanel(BorderLayout()), Disposable {

    private val document = EditorFactory.getInstance().createDocument("")
    private val editor: Editor =
        EditorFactory.getInstance().createEditor(document, project, PlainTextFileType.INSTANCE, true)

    private val status = JBLabel()
    private val banner = JBLabel()
    private val filter = JComboBox(FILTERS)
    private val collapse = JCheckBox("collapse blanks")
    private val openInJenkins = JButton("Open in Jenkins")

    /** The whole retained log, so a filter change does not need to re-fetch it. */
    private var fullText: String = ""
    private var log: ConsoleLog? = null

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "Stagecraft log tail").apply { isDaemon = true }
        }
    private var tailFuture: ScheduledFuture<*>? = null

    /** Only the first few thousand stripes: an editor with a highlighter per line is unusable. */
    private var stripes = 0

    /** The stack frames currently underlined, so a Ctrl-click can be mapped to one by offset. */
    private var hyperlinkFrames: List<StackFrame> = emptyList()

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
            add(JBLabel("<html><b>${build.jobFullName} ${build.displayName}</b></html>"), BorderLayout.NORTH)
            add(toolbar, BorderLayout.CENTER)
            add(banner, BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)
        add(editor.component, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)

        // §9.6: Ctrl/Cmd-click on a stack frame opens its source line, or does nothing when the file
        // is not in the project. Underlines are added with the other decorations.
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
        service.readConsole(build) { result -> result.fold(::showLog, ::showFailure) }
    }

    /** Scroll the editor to a 1-based line, used when a stage is clicked. */
    fun scrollToLine(line: Int?) {
        if (line == null || line <= 0) return
        ApplicationManager.getApplication().invokeLater {
            val last = maxOf(0, document.lineCount - 1)
            val offset = document.getLineStartOffset((line - 1).coerceIn(0, last))
            editor.caretModel.moveToOffset(offset)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }
    }

    private fun showLog(read: ConsoleRead) {
        log = read.log
        fullText = read.log.text
        banner.text = read.log.truncationBanner.orEmpty()
        banner.isVisible = read.log.truncationBanner != null
        applyFilter()
        val error = read.log.firstErrorOffset
        if (error != null) {
            // §7.2: scroll to the first error, never the end. Deferred so the editor has been laid
            // out at least once before we ask it to scroll.
            ApplicationManager.getApplication().invokeLater {
                editor.caretModel.moveToOffset(error.coerceAtMost(document.textLength))
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            }
        }
        if (read.moreData) startTailing()
    }

    private fun showFailure(failure: Throwable) {
        status.text = "Stagecraft could not read this log: ${failure.message ?: failure.javaClass.simpleName}"
    }

    /**
     * Rebuild the editor's text for the current filter and re-stripe it. The full log is kept, so a
     * filter change is a local operation and never a network call.
     */
    private fun applyFilter() {
        val mode = filter.selectedItem as? LogFilterMode ?: LogFilterMode.ALL
        val text = LogFilter.apply(
            fullText,
            mode,
            collapseBlankLines = collapse.isSelected,
        )
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        applyStripes()
        applyHyperlinks(text)
        val lines = log?.totalLines ?: document.lineCount
        val error = log?.firstErrorLine
        status.text = listOfNotNull(
            "$lines lines",
            error?.let { "first error at line $it" },
            if (mode == LogFilterMode.ALL) null else "filtered: ${mode.name.lowercase()}",
        ).joinToString("  ·  ")
    }

    /** Colour error and warning lines, capped so a huge log cannot drown the editor. */
    private fun applyStripes() {
        editor.markupModel.removeAllHighlighters()
        stripes = 0
        for (line in 0 until document.lineCount) {
            if (stripes >= MAX_STRIPES) break
            val start = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line)
            val text = document.getText(TextRange(start, end))
            val attributes = when {
                ConsoleStages.isErrorLine(text) -> errorAttributes()
                LogFilter.isWarning(text) -> warningAttributes()
                else -> null
            } ?: continue
            editor.markupModel.addRangeHighlighter(
                start,
                end,
                HighlighterLayer.ERROR,
                attributes,
                HighlighterTargetArea.LINES_IN_RANGE,
            )
            stripes++
        }
    }

    /**
     * §9.6: underline every stack frame / compiler location in the current text. The offsets are
     * into the same text the document holds, so a click maps straight back. Capped so a pathological
     * log cannot add an unbounded number of highlighters.
     */
    private fun applyHyperlinks(text: String) {
        hyperlinkFrames = StackFrames.find(text, limit = MAX_HYPERLINKS)
        val attributes = linkAttributes()
        for (frame in hyperlinkFrames) {
            editor.markupModel.addRangeHighlighter(
                frame.start,
                frame.end,
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

    private fun errorAttributes(): TextAttributes = TextAttributes(
        null,
        translucent(UIUtil.getErrorForeground()),
        null,
        null,
        Font.PLAIN,
    )

    private fun warningAttributes(): TextAttributes = TextAttributes(
        null,
        translucent(WARNING_COLOR),
        null,
        null,
        Font.PLAIN,
    )

    private fun translucent(colour: Color): Color = Color(colour.red, colour.green, colour.blue, ALPHA)

    /**
     * §9.5: poll `progressiveText` every two seconds while the build runs, appending only the delta.
     * Stops when Jenkins omits `X-More-Data`, or on the first failure (a dead server is reported,
     * not retried into an infinite loop).
     */
    private fun startTailing() {
        if (tailFuture != null) return
        tailFuture = scheduler.scheduleWithFixedDelay(
            { if (isShowing()) service.tailConsole(build) { result -> result.fold(::appendDelta, ::tailFailed) } },
            TAIL_PERIOD_MILLIS,
            TAIL_PERIOD_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun appendDelta(delta: dev.stagecraft.jenkins.TailDelta) {
        if (delta.resetDetected) {
            // Jenkins reset to zero and re-sent the whole log: replace rather than duplicate.
            fullText = delta.text
            applyFilter()
        } else if (delta.text.isNotEmpty()) {
            fullText += delta.text
            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(document.textLength, delta.text)
            }
            status.text = "${document.lineCount} lines  ·  following live output"
        }
        if (!delta.moreData) stopTailing()
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
        stopTailing()
        scheduler.shutdownNow()
        service.stopTailing(build)
        EditorFactory.getInstance().releaseEditor(editor)
    }

    private companion object {
        /** §9.5: two-second cadence. */
        const val TAIL_PERIOD_MILLIS = 2_000L

        const val MAX_STRIPES = 5_000
        const val MAX_HYPERLINKS = 1_000
        const val ALPHA = 40

        /** Theme-neutral amber, so the warning stripe reads on light and dark schemes alike. */
        val WARNING_COLOR = Color(0xC8, 0x8A, 0x00)

        /** The combo's items are the enum itself; [LogFilterMode] renders its own name in the list. */
        val FILTERS = LogFilterMode.entries.toTypedArray()
    }
}
