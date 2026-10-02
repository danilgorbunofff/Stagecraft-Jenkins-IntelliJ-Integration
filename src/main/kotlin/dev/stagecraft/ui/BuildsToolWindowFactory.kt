package dev.stagecraft.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The tool window declared in `plugin.xml`.
 *
 * Days 1-2 owe the project a window that exists and opens, not a window that works: the panel
 * below says what Stagecraft is and where it is up to, and nothing else. The job tree arrives with
 * the service layer.
 *
 * This class is the boundary the charter draws in §9.1 - it is the only thing in `ui/`, and it is
 * the only package allowed to know that IntelliJ exists.
 */
class BuildsToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel(BorderLayout())
        panel.add(
            JLabel(
                "<html><b>Stagecraft</b><br/><br/>" +
                    "Jenkins builds and console logs, without leaving the IDE.<br/><br/>" +
                    "This build contains the Jenkins client and the job model only. " +
                    "The job tree, the log view and the stage view arrive in later days.<br/><br/>" +
                    "Add a server under <i>Settings &gt; Tools &gt; Stagecraft</i> when it appears.</html>",
            ),
            BorderLayout.NORTH,
        )
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }
}
