package dev.stagecraft.service

import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.stagecraft.model.BuildRef
import dev.stagecraft.model.BuildStatus

/**
 * §7.2: branch-scoped build balloons - one per build, click to open it, never one per job or per
 * server.
 *
 * It is a project service like the rest, so the branch scope is inherited from the view model that
 * feeds it (only the current branch's builds are ever passed in). The "one per build" rule itself
 * lives in [BuildNotifications]; this class is the IDE adapter that turns a build into a balloon.
 */
@Service(Service.Level.PROJECT)
class NotificationService(private val project: Project) : BuildNotifier {

    private val notifications = BuildNotifications(this)

    /** Called by the view model when a build appears that was not there before. */
    fun onNewBuild(build: BuildRef) = notifications.onBuild(build)

    override fun show(build: BuildRef) {
        val type = when (build.status) {
            BuildStatus.FAILURE, BuildStatus.UNSTABLE, BuildStatus.ABORTED -> NotificationType.WARNING
            else -> NotificationType.INFORMATION
        }
        val notification = Notification(
            GROUP_ID,
            "${build.jobFullName} ${build.displayName} ${statusText(build.status)}",
            "Stagecraft",
            type,
        )
        notification.addAction(
            NotificationAction.createSimple("Open in Jenkins") { BrowserUtil.browse(build.url) },
        )
        Notifications.Bus.notify(notification, project)
    }

    companion object {
        /** The group id declared in `plugin.xml`, under `Appearance | Notifications`. */
        const val GROUP_ID = "Stagecraft builds"

        fun getInstance(project: Project): NotificationService =
            project.getService(NotificationService::class.java)

        private fun statusText(status: BuildStatus): String = when (status) {
            BuildStatus.RUNNING -> "is running"
            BuildStatus.SUCCESS -> "succeeded"
            BuildStatus.UNSTABLE -> "is unstable"
            BuildStatus.FAILURE -> "failed"
            BuildStatus.ABORTED -> "was aborted"
            BuildStatus.NOT_BUILT -> "was not built"
            BuildStatus.DISABLED -> "is disabled"
            BuildStatus.UNKNOWN -> "finished"
        }
    }
}
