package dev.stagecraft.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.stagecraft.jenkins.StageView
import dev.stagecraft.model.BuildRef
import dev.stagecraft.model.StageRef
import dev.stagecraft.model.StageSource
import dev.stagecraft.service.JenkinsService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.JPanel

/**
 * The stage tree of one build (§7.2, §9.3).
 *
 * Timings are shown **only** where wfapi supplied them; on the console path a stage shows its line
 * count instead, and never a fabricated duration. The failed stage is selected before the user
 * clicks anything, and an inferred failure carries an "inferred" marker rather than being presented
 * as fact. Double-clicking a stage opens the log at that stage's first line when the line range is
 * known.
 */
class StagePanel(
    private val project: Project,
    private val service: JenkinsService,
    private val build: BuildRef,
    private val onOpenLog: (Int?) -> Unit,
) : JPanel(BorderLayout()), Disposable {

    private val model = CollectionListModel<StageRef>(arrayListOf())
    private val list = JBList(model)
    private val header = JBLabel()
    private val note = JBLabel()

    /** The wfapi status of the failed stage, kept so the renderer can mark it without re-deriving. */
    private var view: StageView? = null

    init {
        border = javax.swing.BorderFactory.createEmptyBorder(JBUI.scale(6), JBUI.scale(8), JBUI.scale(6), JBUI.scale(8))

        list.cellRenderer = StageRenderer()
        list.emptyText.setText("This build has no stages.")
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) openSelected()
            }
        })

        header.text = "Loading stages…"
        note.foreground = UIUtil.getInactiveTextColor()
        note.setAllowAutoWrapping(true)
        note.isVisible = false

        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(header, BorderLayout.NORTH)
                add(note, BorderLayout.CENTER)
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(list), BorderLayout.CENTER)

        service.readStages(build) { result -> result.fold(::show, ::showFailure) }
    }

    private fun show(stages: StageView) {
        view = stages
        model.replaceAll(stages.stages)
        val source = when (stages.source) {
            StageSource.WFAPI -> "stage view"
            StageSource.CONSOLE -> "parsed from the console"
            StageSource.PIPELINE -> "pipeline log, no stages"
            StageSource.BUILD -> "freestyle build"
        }
        header.text = "<html><b>${stages.stages.size} stage(s)</b> - $source</html>"
        note.text = stages.note.orEmpty()
        note.isVisible = stages.note != null

        val failed = stages.failedStage
        if (failed != null) {
            val index = stages.stages.indexOfFirst { it.name == failed }
            if (index >= 0) list.selectedIndex = index
        }
    }

    private fun showFailure(failure: Throwable) {
        header.text = "Stagecraft could not read this build's stages."
        note.text = failure.message ?: failure.javaClass.simpleName
        note.isVisible = true
    }

    private fun openSelected() {
        val stage = list.selectedValue ?: return
        onOpenLog(stage.firstLine)
    }

    override fun dispose() = Unit

    private inner class StageRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val stage = value as? StageRef ?: return this
            text = rowHtml(stage)
            toolTipText = stage.errorMessage
            return this
        }
    }

    private fun rowHtml(stage: StageRef): String {
        val failed = stage.name == view?.failedStage
        val status = when {
            stage.skippedReason != null -> "skipped"
            stage.status != null -> stage.status.lowercase().replace('_', ' ')
            failed -> "failed (inferred)"
            else -> "ok"
        }
        val detail = when {
            stage.durationMillis != null -> formatDuration(stage.durationMillis)
            stage.firstLine != null && stage.lastLine != null -> "${stage.lastLine - stage.firstLine + 1} lines"
            stage.isPseudo -> "whole console"
            else -> "-"
        }
        val statusHtml = if (failed || stage.status.equals("FAILED", ignoreCase = true)) {
            "<font color='${hex(UIUtil.getErrorForeground())}'>$status</font>"
        } else {
            status
        }
        return "<html><b>${escape(stage.name)}</b>&nbsp;&nbsp;$statusHtml&nbsp;&nbsp;$detail</html>"
    }

    private companion object {
        fun formatDuration(millis: Long): String {
            val seconds = millis / 1000
            return when {
                seconds < 60 -> "%.1fs".format(millis / 1000.0)
                seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
                else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            }
        }

        fun escape(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun hex(colour: java.awt.Color): String = String.format("#%06x", colour.rgb and 0xFFFFFF)
    }
}
