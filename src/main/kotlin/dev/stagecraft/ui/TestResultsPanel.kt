package dev.stagecraft.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.stagecraft.jenkins.StackFrames
import dev.stagecraft.model.BuildRef
import dev.stagecraft.model.TestCase
import dev.stagecraft.model.TestReport
import dev.stagecraft.service.JenkinsService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.JPanel

/**
 * The build's test results (§9.6), failed tests first.
 *
 * Core Jenkins, no plugin. Double-clicking a failed test opens the test's own source line, using the
 * first frame of its `errorStackTrace` (the frame in the test itself, not the code it called). A
 * test whose file is not in the project is left as plain text, like every other hyperlink.
 */
class TestResultsPanel(
    private val project: Project,
    service: JenkinsService,
    build: BuildRef,
) : JPanel(BorderLayout()), Disposable {

    private val model = CollectionListModel<TestCase>(arrayListOf())
    private val list = JBList(model)
    private val header = JBLabel()

    init {
        border = javax.swing.BorderFactory.createEmptyBorder(JBUI.scale(6), JBUI.scale(8), JBUI.scale(6), JBUI.scale(8))

        list.cellRenderer = CaseRenderer()
        list.emptyText.setText("This build published no test report.")
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) openSelected()
            }
        })

        header.text = "Loading test results…"
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)

        service.readTests(build) { result -> result.fold(::show, ::showFailure) }
    }

    private fun show(report: TestReport?) {
        if (report == null) {
            header.text = "This build published no test report."
            model.replaceAll(arrayListOf())
            return
        }
        header.text = "<html><b>${report.totalCount} tests</b> - ${report.summary}</html>"
        // Failed first, then by name, so the thing a person came for is at the top.
        model.replaceAll(
            report.cases.sortedWith(
                compareByDescending<TestCase> { it.failed }.thenBy { it.fullName },
            ),
        )
    }

    private fun showFailure(failure: Throwable) {
        header.text = "Stagecraft could not read this build's test report."
        model.replaceAll(arrayListOf())
    }

    private fun openSelected() {
        val case = list.selectedValue ?: return
        val frame = case.errorStackTrace?.let { StackFrames.find(it).firstOrNull() } ?: return
        SourceNavigator.navigate(project, frame)
    }

    override fun dispose() = Unit

    private inner class CaseRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val case = value as? TestCase ?: return this
            val status = when {
                case.failed -> "<font color='${hex(UIUtil.getErrorForeground())}'>failed</font>"
                case.skipped -> "skipped"
                else -> "passed"
            }
            val duration = case.durationMillis?.let { "  ${"%.2fs".format(it / 1000.0)}" }.orEmpty()
            val stage = case.enclosingBlockNames.firstOrNull()?.let { "  [$it]" }.orEmpty()
            text = "<html><b>${escape(case.name)}</b>&nbsp;&nbsp;$status$duration$stage</html>"
            toolTipText = case.errorDetails ?: case.fullName
            return this
        }
    }

    private companion object {
        fun escape(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun hex(colour: java.awt.Color): String = String.format("#%06x", colour.rgb and 0xFFFFFF)
    }
}
