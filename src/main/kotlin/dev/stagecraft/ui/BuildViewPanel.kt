package dev.stagecraft.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import dev.stagecraft.model.BuildRef
import dev.stagecraft.service.JenkinsService
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * One build, as a tool window tab: **Stages**, **Log**, **Tests** (§7.2).
 *
 * Clicking a stage selects the Log tab and scrolls to that stage's first line where the console
 * parse knows the range; the log is otherwise opened at the first error. Each child owns its own
 * load and tail, and [dispose] tears all three down together, so closing the tab stops every poll.
 */
class BuildViewPanel(
    project: Project,
    service: JenkinsService,
    build: BuildRef,
    isShowing: () -> Boolean,
) : JPanel(BorderLayout()), Disposable {

    private val tabs = JTabbedPane()
    private val log = LogEditorPanel(project, service, build, isShowing)
    private val stages = StagePanel(project, service, build) { line ->
        tabs.selectedComponent = log
        log.scrollToLine(line)
    }
    private val tests = TestResultsPanel(project, service, build)

    init {
        tabs.addTab("Stages", stages)
        tabs.addTab("Log", log)
        tabs.addTab("Tests", tests)
        add(tabs, BorderLayout.CENTER)
    }

    override fun dispose() {
        log.dispose()
        stages.dispose()
        tests.dispose()
    }
}
