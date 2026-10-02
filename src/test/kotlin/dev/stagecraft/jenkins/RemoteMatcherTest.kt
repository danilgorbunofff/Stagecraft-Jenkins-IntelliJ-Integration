package dev.stagecraft.jenkins

import dev.stagecraft.FakeTransport
import dev.stagecraft.Fixtures
import dev.stagecraft.model.JobKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §9.4 in one file: remote parsing, ranking, the PR-N fallback, honest unresolveds, pinning, and
 * the one-build confirmation — everything against fixtures or synthetic indexes, no server.
 */
class RemoteMatcherTest {

    private val base = "http://localhost:18080/"
    private val buildUrl = base + "job/multibranch-demo/job/main/1/"

    private fun client(fake: FakeTransport) =
        JenkinsClient(base, JenkinsAuth(JenkinsCredential.ApiToken("admin", "token")), fake)

    // ---------------------------------------------------------------- remote parsing

    @Test
    fun `remotes parse into the same repository however git spells them`() {
        val spellings = listOf(
            "git@github.com:danilgorbunofff/stagecraft-day0-fixture.git",
            "ssh://git@github.com:22/danilgorbunofff/stagecraft-day0-fixture",
            "https://github.com/danilgorbunofff/stagecraft-day0-fixture",
            "http://github.com/danilgorbunofff/stagecraft-day0-fixture/",
            "git://GitHub.com/Danilgorbunofff/Stagecraft-Day0-Fixture.git",
        )

        val first = RemoteInfo.parse(spellings.first())!!

        for (spelling in spellings) {
            val parsed = RemoteInfo.parse(spelling)!!
            assertEquals("github.com", parsed.host, spelling)
            // Host is canonicalised, org/repo keep the spelling the remote carried — matching is
            // case-insensitive everywhere else, so the parser preserves what it was given.
            assertTrue(parsed.org.equals("danilgorbunofff", ignoreCase = true), spelling)
            assertTrue(parsed.repo.equals("stagecraft-day0-fixture", ignoreCase = true), spelling)
            assertTrue(first.matches(parsed), "expected $spelling to equal the first spelling")
        }
        assertEquals("github.com/danilgorbunofff/stagecraft-day0-fixture", first.key)
    }

    @Test
    fun `a remote stagecraft cannot name is refused`() {
        assertNull(RemoteInfo.parse(""))
        assertNull(RemoteInfo.parse("not a url at all"))
        assertNull(RemoteInfo.parse("file:///tmp/repo"))
        assertNull(RemoteInfo.parse("C:\\repos\\thing"))
    }

    @Test
    fun `a deeper https path keeps its subpath`() {
        val parsed = RemoteInfo.parse("https://git.company.com/team/org/repo.git")!!

        assertEquals("git.company.com", parsed.host)
        // team/ is the subpath beyond the host, org is the immediate parent of repo, and the
        // key keeps every segment in order so all three match under one canonical form.
        assertEquals("team", parsed.subpath)
        assertEquals("org", parsed.org)
        assertEquals("repo", parsed.repo)
        assertEquals("git.company.com/team/org/repo", parsed.key)
    }

    // ---------------------------------------------------------------- ranking and honesty

    @Test
    fun `the multibranch job named like the repository is the match`() {
        val index = JobIndex.of(
            listOf(
                testJob(listOf("stagecraft"), MULTIBRANCH_CLASS),
                testJob(listOf("stagecraft"), FOLDER_CLASS),
            ),
        )

        val match = RemoteMatcher(index).match("https://github.com/corp/stagecraft.git") as RemoteMatcher.MatchResult.Matched

        assertEquals(JobKind.MULTIBRANCH, match.job.kind)
        assertTrue(match.certain)
        assertTrue(match.how.contains("multibranch"), match.how)
    }

    @Test
    fun `weak evidence is a guess the interface must show as one`() {
        val index = JobIndex.of(listOf(testJob(listOf("stagecraft-ui"), WORKFLOW_CLASS)))

        val match = RemoteMatcher(index).match("https://github.com/corp/stagecraft.git") as RemoteMatcher.MatchResult.Matched

        assertEquals("stagecraft-ui", match.job.displayName)
        assertTrue(!match.certain)
        assertTrue(match.how.contains("contains"), match.how)
    }

    @Test
    fun `honest not-found - a repository the index has never seen stays unresolved`() {
        // The real Day-0 server has no job named stagecraft-day0-fixture (fixture 07 records its
        // remote, but no job of that name exists). The matcher must refuse, never pretend.
        val index = JobIndex.of(
            listOf(
                testJob(listOf("multibranch-demo"), MULTIBRANCH_CLASS),
                testJob(listOf("stagecraft"), MULTIBRANCH_CLASS),
            ),
        )

        val match = RemoteMatcher(index).match("https://github.com/danilgorbunofff/stagecraft-day0-fixture")

        assertTrue(match is RemoteMatcher.MatchResult.Unresolved, "expected refusal, got ${match.explanation}")
        assertTrue(match.reason.contains("stagecraft-day0-fixture"), match.reason)
    }

    @Test
    fun `an unnameable remote stays unresolved`() {
        val match = RemoteMatcher(JobIndex.of(emptyList())).match("C:\\repos\\thing")

        assertTrue(match is RemoteMatcher.MatchResult.Unresolved)
        assertTrue(match.reason.contains("not a git remote"), match.reason)
    }

    @Test
    fun `a truncated index admits what it hid`() {
        val index = JobIndex.of(emptyList(), truncated = true, totalSeen = 3000)

        val match = RemoteMatcher(index).match("https://github.com/corp/hidden.git")

        assertTrue(match is RemoteMatcher.MatchResult.Unresolved)
        assertTrue(match.reason.contains("0 of 3000 jobs"), match.reason)
        assertTrue(match.reason.contains("pin your job manually"), match.reason)
    }

    // ---------------------------------------------------------------- branches

    @Test
    fun `a branch child is preferred over its parent`() {
        val index = JobIndex.of(
            listOf(
                testJob(listOf("svc"), MULTIBRANCH_CLASS),
                testJob(listOf("svc", "feature%2FORD-214"), WORKFLOW_CLASS),
            ),
        )

        val match = RemoteMatcher(index).match("https://github.com/corp/svc.git", branch = "feature/ORD-214")
            as RemoteMatcher.MatchResult.Matched

        assertEquals("feature/ORD-214", match.job.displayName)
        assertEquals("feature/ORD-214", match.branch)
        assertEquals(match.job, match.branchJob)
        assertTrue(match.certain)
    }

    @Test
    fun `a branch with no indexed child still names the parent, uncertainly`() {
        val index = JobIndex.of(listOf(testJob(listOf("svc"), MULTIBRANCH_CLASS)))

        val match = RemoteMatcher(index).match("https://github.com/corp/svc.git", branch = "feature/nope")
            as RemoteMatcher.MatchResult.Matched

        assertEquals("svc", match.job.displayName)
        assertEquals("feature/nope", match.branch)
        assertTrue(!match.certain)
        assertTrue(match.how.contains("no indexed job"), match.how)
    }

    // ---------------------------------------------------------------- the PR-N fallback

    @Test
    fun `a PR job is matched directly by its PR number`() {
        val index = JobIndex.of(
            listOf(
                testJob(listOf("github-mb-fixture"), MULTIBRANCH_CLASS),
                testJob(listOf("github-mb-fixture", "PR-1"), WORKFLOW_CLASS),
            ),
        )

        val match = RemoteMatcher(index).match("git@github.com:corp/github-mb-fixture.git", prNumber = 1)
            as RemoteMatcher.MatchResult.Matched

        assertEquals("PR-1", match.job.displayName)
        assertTrue(match.how.contains("PR-1 job of"), match.how)
        assertTrue(match.certain)
    }

    @Test
    fun `a PR that resolves to a source branch finds the branch job`() {
        val resolver = object : PrSourceResolver {
            override fun sourceBranch(remote: RemoteInfo, prNumber: Int): String? = "develop"
        }
        val index = JobIndex.of(
            listOf(
                testJob(listOf("svc"), MULTIBRANCH_CLASS),
                testJob(listOf("svc", "develop"), WORKFLOW_CLASS),
            ),
        )

        val match = RemoteMatcher(index, resolver).match("https://github.com/corp/svc.git", prNumber = 7)
            as RemoteMatcher.MatchResult.Matched

        assertEquals("develop", match.job.displayName)
        assertTrue(match.how.contains("PR-7 built from branch"), match.how)
    }

    @Test
    fun `a PR nobody can resolve stays unresolved`() {
        val index = JobIndex.of(listOf(testJob(listOf("svc"), MULTIBRANCH_CLASS)))

        val match = RemoteMatcher(index).match("https://github.com/corp/svc.git", prNumber = 7)

        assertTrue(match is RemoteMatcher.MatchResult.Unresolved)
        assertTrue(match.reason.contains("PR-7"), match.reason)
        assertTrue(match.reason.contains("Pin the job manually"), match.reason)
    }

    // ---------------------------------------------------------------- pinning

    @Test
    fun `a pin outranks every name tier`() {
        val pinned = testJob(listOf("team-a", "real-svc"), WORKFLOW_CLASS)
        val index = JobIndex.of(listOf(testJob(listOf("svc"), MULTIBRANCH_CLASS)))
            .withPin("github.com/corp/svc", pinned)

        val match = RemoteMatcher(index).match("git@github.com:CORP/SVC.git") as RemoteMatcher.MatchResult.Matched

        assertEquals(pinned, match.job)
        assertTrue(match.how.contains("pinned"), match.how)
        assertTrue(match.certain)
    }

    // ---------------------------------------------------------------- the one-build confirmation

    @Test
    fun `the one-build confirmation reads remotes and branch`() {
        val fake = FakeTransport()
        fake.onGetPrefix(buildUrl + "api/json", Fixtures.json("07.remotedata.json"))
        val remote = RemoteInfo.parse("https://github.com/danilgorbunofff/stagecraft-day0-fixture")!!

        val confirmation = confirmWithBuild(client(fake), buildUrl, remote, expectedBranch = null)

        assertTrue(confirmation.remoteMatches)
        assertNull(confirmation.branchMatches)
        assertEquals(listOf("https://github.com/danilgorbunofff/stagecraft-day0-fixture"), confirmation.foundRemoteUrls)
        assertTrue(confirmation.foundBranches.isEmpty())
    }

    @Test
    fun `the one-build confirmation compares branches, refs and case aside`() {
        val fake = FakeTransport()
        val body = """{"_class":"org.jenkinsci.plugins.workflow.job.WorkflowRun","actions":[
            {"_class":"hudson.plugins.git.util.BuildData",
             "remoteUrls":["git@github.com:corp/svc.git"],
             "lastBuiltRevision":{"SHA1":"abc","branch":[{"SHA1":"abc","name":"refs/heads/feature/x"}]}}]}"""
        fake.onGetPrefix(buildUrl + "api/json", Fixtures.of(body))
        val remote = RemoteInfo.parse("https://github.com/CORP/SVC")!!

        val matching = confirmWithBuild(client(fake), buildUrl, remote, expectedBranch = "feature/x")
        assertTrue(matching.remoteMatches)
        assertTrue(matching.branchMatches == true)
        assertEquals(listOf("refs/heads/feature/x"), matching.foundBranches)

        val disagreeing = confirmWithBuild(client(fake), buildUrl, remote, expectedBranch = "main")
        assertEquals(false, disagreeing.branchMatches)
    }

    @Test
    fun `a 404 on the confirming build is a permissions question, not a lie`() {
        val fake = FakeTransport()
        fake.onGetPrefix(
            buildUrl + "api/json",
            HttpResponse(404, emptyList(), "nope".toByteArray()),
        )

        assertFailsWith<JenkinsException.NotFound> {
            confirmWithBuild(
                client(fake),
                buildUrl,
                RemoteInfo.parse("https://github.com/corp/svc")!!,
                null,
            )
        }
    }
}
