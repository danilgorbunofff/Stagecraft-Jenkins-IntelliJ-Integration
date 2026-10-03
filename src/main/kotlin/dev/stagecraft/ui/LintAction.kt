package dev.stagecraft.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.DialogWrapper
import dev.stagecraft.service.JenkinsService
import javax.swing.JComponent

/**
 * §7.4: "Validate Jenkinsfile with Stagecraft" on the editor popup.
 *
 * It reads `editor.document.text` - the **buffer**, unsaved edits included - which is exactly the
 * defect the 148k-download incumbent is reviewed for. The response opens in a [LintPanel] dialog so
 * it can be selected and copied.
 */
class LintAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val service = JenkinsService.getInstance(project)
        // The provider re-reads the document, so Re-validate reflects the current buffer.
        val panel = LintPanel(service) { editor.document.text }

        object : DialogWrapper(project) {
            init {
                title = "Stagecraft Jenkinsfile Lint"
                init()
            }

            override fun createCenterPanel(): JComponent = panel

            override fun getPreferredFocusedComponent(): JComponent = panel
        }.show()
    }

    /**
     * Only on a Jenkinsfile (`Jenkinsfile`, `Jenkinsfile.prod`, `deploy.jenkinsfile`, ...): offering to
     * lint a Java file, a diff or Stagecraft's own log against the pipeline linter is noise.
     */
    override fun update(event: AnActionEvent) {
        val name = event.getData(CommonDataKeys.VIRTUAL_FILE)?.name
        event.presentation.isEnabledAndVisible =
            event.getData(CommonDataKeys.EDITOR) != null && name != null && isJenkinsfile(name)
    }

    /** [update] reads only the editor and file from the data context, so it may run off the EDT. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        fun isJenkinsfile(fileName: String): Boolean {
            val lower = fileName.lowercase()
            return lower == "jenkinsfile" || lower.startsWith("jenkinsfile.") || lower.endsWith(".jenkinsfile")
        }
    }
}
