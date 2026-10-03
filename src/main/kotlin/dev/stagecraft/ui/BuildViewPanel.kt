package dev.stagecraft.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.stagecraft.model.BuildRef
import dev.stagecraft.service.JenkinsService
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * One build, as a tool window tab: **Stages**, **Log**, **Tests**, with a **Rebuild** action
 * (§7.2, §7.4).
 *
 * Clicking a stage selects the Log tab and scrolls to that stage's first line where the console
 * parse knows the range; the log is otherwise opened at the first error. Each child owns its own
 * load and tail, and [dispose] tears all three down together, so closing the tab stops every poll.
 */
class BuildViewPanel(
    private val project: Project,
    private val service: JenkinsService,
    private val build: BuildRef,
    isShowing: () -> Boolean,
) : JPanel(BorderLayout()), Disposable {

    private val tabs = JTabbedPane()
    private val log = LogEditorPanel(project, service, build, isShowing)
    private val stages = StagePanel(project, service, build) { stage ->
        tabs.selectedComponent = log
        log.scrollToStage(stage.name, stage.firstLine)
    }
    private val tests = TestResultsPanel(project, service, build)
    private val rebuild = JButton("Rebuild")

    init {
        // §7.4: rebuild only where the server allows it - hidden until the job is known to be
        // buildable (a disabled job is not) - and re-run with the parameters this build used. Every
        // refusal is reported as what it is.
        rebuild.isVisible = false
        rebuild.toolTipText = "Run this job again, with the parameters this build used"
        service.canRebuild(build) { result -> rebuild.isVisible = result.getOrDefault(false) }
        rebuild.addActionListener {
            rebuild.isEnabled = false
            service.triggerBuild(build) { result ->
                rebuild.isEnabled = true
                result.fold(
                    { answer -> Messages.showInfoMessage(project, answer.message, "Stagecraft rebuild") },
                    { failure ->
                        Messages.showErrorDialog(
                            project,
                            failure.message ?: "Stagecraft could not rebuild this job.",
                            "Stagecraft rebuild",
                        )
                    },
                )
            }
        }

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(rebuild)
        }

        tabs.addTab("Stages", stages)
        tabs.addTab("Log", log)
        tabs.addTab("Tests", tests)
        add(header, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
    }

    override fun dispose() {
        log.dispose()
        stages.dispose()
        tests.dispose()
    }
}
