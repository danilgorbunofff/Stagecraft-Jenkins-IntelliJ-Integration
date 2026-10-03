package dev.stagecraft.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.stagecraft.jenkins.LintResult
import dev.stagecraft.service.JenkinsService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JPanel

/**
 * §7.4: the server's Jenkinsfile lint response, formatted and **copyable** - the incumbent's second
 * defect was *"only displays a long string that I can't even copy"*.
 *
 * The Jenkinsfile is read through a provider so **Re-validate** lints the current buffer, not the
 * text captured when the action ran. The panel only paints; the POST and its crumb handling happen
 * on `JenkinsService`'s I/O thread.
 */
class LintPanel(
    private val service: JenkinsService,
    private val jenkinsfile: () -> String,
) : JPanel(BorderLayout()), Disposable {

    private val headline = JBLabel()
    private val text = JBTextArea()
    private val revalidate = JButton("Re-validate")
    private val copy = JButton("Copy")

    init {
        border = javax.swing.BorderFactory.createEmptyBorder(JBUI.scale(8), JBUI.scale(8), JBUI.scale(8), JBUI.scale(8))
        text.isEditable = false
        text.lineWrap = true
        text.font = Font(Font.MONOSPACED, Font.PLAIN, text.font.size)

        copy.addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(text.text)) }
        revalidate.addActionListener { runLint() }

        add(headline, BorderLayout.NORTH)
        add(JBScrollPane(text), BorderLayout.CENTER)
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(revalidate)
                add(copy)
            },
            BorderLayout.SOUTH,
        )

        runLint()
    }

    private fun runLint() {
        headline.foreground = UIUtil.getInactiveTextColor()
        headline.text = "Validating with the server's own linter…"
        service.lintJenkinsfile(jenkinsfile()) { result -> result.fold(::show, ::showFailure) }
    }

    private fun show(result: LintResult) {
        headline.text = if (result.valid) "Jenkinsfile is valid." else result.headline
        headline.foreground = if (result.valid) UIUtil.getInactiveTextColor() else UIUtil.getErrorForeground()
        text.text = result.message
    }

    private fun showFailure(failure: Throwable) {
        headline.text = "Stagecraft could not reach the linter."
        headline.foreground = UIUtil.getErrorForeground()
        text.text = failure.message ?: failure.javaClass.simpleName
    }

    override fun dispose() = Unit
}
