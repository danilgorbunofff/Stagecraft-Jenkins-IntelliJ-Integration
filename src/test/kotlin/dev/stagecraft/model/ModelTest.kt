package dev.stagecraft.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two string-to-enum mappings are the whole reason this package exists: no caller compares a
 * raw `color` or `result` string, and no caller special-cases `result == null`.
 */
class ModelTest {

    @Test
    fun `a building job is running whatever else its colour says`() {
        // Jenkins appends `_anime` to the colour of a job that is building right now.
        assertEquals(BuildStatus.RUNNING, BuildStatus.fromColour("red_anime"))
        assertEquals(BuildStatus.RUNNING, BuildStatus.fromColour("blue_anime"))
        assertEquals(BuildStatus.RUNNING, BuildStatus.fromColour("notbuilt_anime"))
    }

    @Test
    fun `job colours map to the same enum as build results`() {
        assertEquals(BuildStatus.SUCCESS, BuildStatus.fromColour("blue"))
        assertEquals(BuildStatus.FAILURE, BuildStatus.fromColour("red"))
        assertEquals(BuildStatus.UNSTABLE, BuildStatus.fromColour("yellow"))
        assertEquals(BuildStatus.NOT_BUILT, BuildStatus.fromColour("grey"))
        assertEquals(BuildStatus.NOT_BUILT, BuildStatus.fromColour("notbuilt"))
        assertEquals(BuildStatus.ABORTED, BuildStatus.fromColour("aborted"))
        assertEquals(BuildStatus.DISABLED, BuildStatus.fromColour("disabled"))
    }

    @Test
    fun `an absent colour is unknown rather than a guess`() {
        // Re-check R5: a container job reports no colour at all, and "no colour" must not be
        // read as success or as failure.
        assertEquals(BuildStatus.UNKNOWN, BuildStatus.fromColour(null))
        assertEquals(BuildStatus.UNKNOWN, BuildStatus.fromColour(""))
        assertEquals(BuildStatus.UNKNOWN, BuildStatus.fromColour("   "))
        assertEquals(BuildStatus.UNKNOWN, BuildStatus.fromColour("chartreuse"))
    }

    @Test
    fun `a running build is running, not not-built`() {
        // Re-check R9: `result` is null while a build is running. NOT_BUILT is a real, different
        // result that Jenkins spells out when it means it.
        assertEquals(BuildStatus.RUNNING, BuildStatus.fromResult(null, building = true))
        assertEquals(BuildStatus.RUNNING, BuildStatus.fromResult("SUCCESS", building = true))
        assertEquals(BuildStatus.NOT_BUILT, BuildStatus.fromResult("NOT_BUILT", building = false))
        assertEquals(BuildStatus.UNKNOWN, BuildStatus.fromResult(null, building = false))
    }

    @Test
    fun `build results are case-insensitive`() {
        assertEquals(BuildStatus.SUCCESS, BuildStatus.fromResult("success", building = false))
        assertEquals(BuildStatus.FAILURE, BuildStatus.fromResult("failure", building = false))
        assertEquals(BuildStatus.ABORTED, BuildStatus.fromResult("ABORTED", building = false))
        assertEquals(BuildStatus.UNSTABLE, BuildStatus.fromResult("UNSTABLE", building = false))
    }

    @Test
    fun `the recorded job classes map to the recorded kinds`() {
        // These are the exact `_class` strings out of the Day-0 captures.
        assertEquals(JobKind.FREESTYLE, JobKind.fromClass("hudson.model.FreeStyleProject"))
        assertEquals(
            JobKind.MULTIBRANCH,
            JobKind.fromClass("org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject"),
        )
        assertEquals(JobKind.FOLDER, JobKind.fromClass("com.cloudbees.hudson.plugins.folder.Folder"))
        assertEquals(JobKind.WORKFLOW, JobKind.fromClass("org.jenkinsci.plugins.workflow.job.WorkflowJob"))
        assertEquals(JobKind.ORGANIZATION_FOLDER, JobKind.fromClass("jenkins.branch.OrganizationFolder"))
        assertEquals(JobKind.MATRIX, JobKind.fromClass("hudson.matrix.MatrixProject"))
        assertEquals(JobKind.OTHER, JobKind.fromClass("hudson.model.Hudson"))
        assertEquals(JobKind.OTHER, JobKind.fromClass(null))
        assertEquals(JobKind.OTHER, JobKind.fromClass("  "))
    }

    @Test
    fun `only containers contain jobs`() {
        assertTrue(JobKind.FOLDER.isContainer)
        assertTrue(JobKind.ORGANIZATION_FOLDER.isContainer)
        assertTrue(JobKind.MULTIBRANCH.isContainer)
        assertFalse(JobKind.WORKFLOW.isContainer)
        assertFalse(JobKind.FREESTYLE.isContainer)
        assertFalse(JobKind.MATRIX.isContainer)
        assertFalse(JobKind.OTHER.isContainer)
    }

    @Test
    fun `a job node exposes both identities`() {
        // Re-check R5: the branch `feature/ORD-214` arrives as `feature%2FORD-214`, and the raw
        // path is the only identity that survives a round trip back into a URL.
        val node = JobNode(
            name = "feature%2FORD-214",
            displayName = "feature/ORD-214",
            fullName = "multibranch-demo/feature/ORD-214",
            rawPath = listOf("multibranch-demo", "feature%2FORD-214"),
            className = "org.jenkinsci.plugins.workflow.job.WorkflowJob",
            kind = JobKind.WORKFLOW,
            url = "http://localhost:18080/job/multibranch-demo/job/feature%252FORD-214/",
            depth = 2,
            colour = "yellow",
        )

        assertEquals("multibranch-demo/feature%2FORD-214", node.rawPathString)
        assertEquals(BuildStatus.UNSTABLE, node.status)
        assertFalse(node.isContainer)
        assertEquals("multibranch-demo", node.rawPath.first())
        assertEquals(node.fullName, node.rawPath.joinToString("/") { java.net.URLDecoder.decode(it, "UTF-8") })
    }

    @Test
    fun `a build reference names itself the way a human writes it`() {
        val build = BuildRef(
            jobFullName = "multibranch-demo/main",
            jobRawPath = listOf("multibranch-demo", "main"),
            number = 12,
            url = "http://localhost:18080/job/multibranch-demo/job/main/12/",
            status = BuildStatus.SUCCESS,
            timestampMillis = 1_790_863_881_145,
            durationMillis = 11_157,
        )

        assertEquals("#12", build.displayName)
        assertFalse(build.isRunning)

        val running = build.copy(number = 13, status = BuildStatus.RUNNING)
        assertEquals("#13", running.displayName)
        assertTrue(running.isRunning)
    }
}
