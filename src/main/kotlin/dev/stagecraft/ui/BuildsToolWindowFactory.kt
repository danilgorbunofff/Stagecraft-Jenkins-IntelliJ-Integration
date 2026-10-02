package dev.stagecraft.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.stagecraft.service.JenkinsService

/**
 * The tool window declared in `plugin.xml`.
 *
 * It owns one [BuildTreePanel] per project and nothing else: the panel renders state, the service
 * produces it, and this class only joins them. The load starts here rather than in the panel
 * because opening the window is the moment the project's settings become worth reading.
 *
 * This class is the boundary the charter draws in §9.1 - `ui/` is the only package allowed to know
 * that IntelliJ exists.
 */
class BuildsToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = JenkinsService.getInstance(project)
        val panel = BuildTreePanel(project, service) { toolWindow.isVisible }

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)

        service.activate()
    }
}
