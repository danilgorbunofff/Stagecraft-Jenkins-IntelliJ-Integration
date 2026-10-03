package dev.stagecraft.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.stagecraft.model.BuildRef
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
        val panel = BuildTreePanel(project, service, { toolWindow.isVisible }) { build ->
            openLog(project, service, toolWindow, build)
        }

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)

        // Usually already done at project open (StagecraftStartup); a no-op then.
        service.activate()
    }

    /**
     * Open a build as its own tool window tab: stages, log and tests. The tab owns a
     * [BuildViewPanel]; closing it disposes the log editor and the tail poller, so a log that is no
     * longer on screen stops polling (§9.5: "stop immediately if the tool window closes").
     */
    private fun openLog(project: Project, service: JenkinsService, toolWindow: ToolWindow, build: BuildRef) {
        val buildView = BuildViewPanel(project, service, build) { toolWindow.isVisible }
        val content = ContentFactory.getInstance()
            .createContent(buildView, "${build.jobFullName} ${build.displayName}", true)
        content.setDisposer(buildView)
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content)
    }
}
