package dev.stagecraft.service

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * §7.2: branch notifications must not depend on the tool window having been opened. A configured
 * project starts its service - the cached first paint, one live load, and the poller - when the
 * project opens; an unconfigured one costs nothing.
 */
class StagecraftStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = JenkinsService.getInstance(project)
        if (!service.settings.state.isConfigured) return
        service.activate()
        service.startPolling()
    }
}
