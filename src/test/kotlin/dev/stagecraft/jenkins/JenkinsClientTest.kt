package dev.stagecraft.jenkins

import dev.stagecraft.FakeTransport
import dev.stagecraft.Fixtures
import dev.stagecraft.model.BuildStatus
import dev.stagecraft.model.JobKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The typed API against the recorded Day-0 responses. No server is contacted; every assertion is
 * about what Stagecraft makes of bytes a real Jenkins really sent.
 */
class JenkinsClientTest {

    private val base = "http://localhost:18080/"
    private val mePrefix = base + "me/api/json"
    private val rootPrefix = base + "api/json"
    private val mainBuildUrl = base + "job/multibranch-demo/job/main/1/"
    private val tickingBuildUrl = base + "job/stagecraft/job/ticking/2/"

    private fun client(fake: FakeTransport, credential: JenkinsCredential = JenkinsCredential.ApiToken("admin", "token")) =
        JenkinsClient(base, JenkinsAuth(credential), fake)

    private fun withBody(recorded: Fixtures.Recorded, body: String): HttpResponse =
        HttpResponse(recorded.status, recorded.headers, body.toByteArray(Charsets.UTF_8))

    private fun bodyOfLength(recorded: Fixtures.Recorded): ByteArray =
        ByteArray(recorded.header("Content-Length")?.toInt() ?: 0) { 'x'.code.toByte() }

    // ---------------------------------------------------------------- credentials

    @Test
    fun `me returns who jenkins thinks we are`() {
        val fake = FakeTransport()
        fake.onGetPrefix(mePrefix, Fixtures.json("01.me.json"))

        val info = client(fake).me()

        assertEquals("admin", info.id)
        assertEquals("admin", info.fullName)
    }

    @Test
    fun `credentials that answer as somebody else are rejected`() {
        // A 200 on /me/api/json proves nothing: a server with anonymous read answers it as
        // `anonymous`. §9.2 wants the returned id compared to the configured user, so configure a
        // user who is not the one the recorded server answered as.
        val fake = FakeTransport()
        fake.onGetPrefix(mePrefix, Fixtures.json("01.me.json"))

        val client = JenkinsClient(
            base,
            JenkinsAuth(JenkinsCredential.ApiToken("somebody-else", "token")),
            fake,
        )

        val failure = assertFailsWith<JenkinsException.Malformed> { client.verifyCredentials() }

        assertTrue(failure.message!!.contains("different Jenkins account"), failure.message!!)
        assertTrue(failure.message!!.contains("somebody-else"), failure.message!!)
    }

    @Test
    fun `an anonymous answer is named as anonymous, not as another account`() {
        val fake = FakeTransport()
        fake.onGetPrefix(mePrefix, Fixtures.of("""{"id":"anonymous","fullName":"Anonymous"}"""))

        val failure = assertFailsWith<JenkinsException.Malformed> { client(fake).verifyCredentials() }

        assertTrue(failure.message!!.contains("not actually authenticated"), failure.message!!)
        assertTrue(failure.message!!.contains("'anonymous'"), failure.message!!)
    }

    @Test
    fun `the configured user is matched the way jenkins matches ids - case aside`() {
        // Jenkins' default id strategy is case-insensitive and reports the stored spelling, so a
        // user who typed `Admin` is the `admin` the server answers with.
        val fake = FakeTransport()
        fake.onGetPrefix(mePrefix, Fixtures.json("01.me.json"))

        val client = JenkinsClient(base, JenkinsAuth(JenkinsCredential.ApiToken("Admin", "token")), fake)

        assertEquals("admin", client.verifyCredentials().id)
    }

    @Test
    fun `credentials that answer as the configured user are accepted`() {
        val fake = FakeTransport()
        fake.onGetPrefix(mePrefix, Fixtures.json("01.me.json"))

        assertEquals("admin", client(fake).verifyCredentials().id)
    }

    @Test
    fun `a response with no id is malformed rather than silently anonymous`() {
        val fake = FakeTransport()
        fake.onGetPrefix(mePrefix, Fixtures.of("""{"_class":"hudson.model.User"}"""))

        assertFailsWith<JenkinsException.Malformed> { client(fake).me() }
    }

    // ---------------------------------------------------------------- failure mapping

    @Test
    fun `a rejected token is reported as 401 with the challenge jenkins sent`() {
        val recorded = Fixtures.badTokenHeaders
        val fake = FakeTransport()
        fake.onGetPrefix(
            rootPrefix,
            withBody(recorded, Fixtures.text("rechecks/r6-bad-token.body.txt")),
        )

        val failure = assertFailsWith<JenkinsException.Unauthorized> { client(fake).rootJobs() }

        assertEquals(401, recorded.status)
        assertEquals("Basic realm=\"Jenkins\"", failure.challenge)
        assertEquals(recorded.header("WWW-Authenticate"), failure.challenge)
    }

    @Test
    fun `a 403 is reported as permissions, not as a crumb problem`() {
        val recorded = Fixtures.anonymousForbiddenHeaders
        val fake = FakeTransport()
        fake.onGetPrefix(rootPrefix, withBody(recorded, "<html><title>Error 403 Forbidden</title></html>"))

        val failure = assertFailsWith<JenkinsException.Forbidden> { client(fake).rootJobs() }

        assertFalse(failure.crumbProblem)
        assertEquals("Error 403 Forbidden", failure.detail)
    }

    @Test
    fun `a 404 says the job may merely be hidden`() {
        // Re-check R7: Jenkins answers 404 both for a job that does not exist and for one the
        // configured user may not read, so the message must not claim the job is missing.
        val recorded = Fixtures.hiddenJobHeaders
        val fake = FakeTransport()
        fake.onGetPrefix(rootPrefix, withBody(recorded, "<html><title>Error 404 Not Found</title></html>"))

        val failure = assertFailsWith<JenkinsException.NotFound> { client(fake).rootJobs() }

        assertEquals(404, recorded.status)
        assertTrue(failure.message!!.contains("not allowed to read"), failure.message!!)
    }

    @Test
    fun `a redirect to a login page is reported as single sign-on`() {
        val fake = FakeTransport()
        fake.onGetPrefix(
            rootPrefix,
            Fixtures.of("", 302, mapOf("Location" to "https://sso.example.com/securityRealm/login")),
        )

        val failure = assertFailsWith<JenkinsException.SsoRedirect> { client(fake).rootJobs() }

        assertTrue(failure.location.contains("securityRealm"))
    }

    @Test
    fun `a redirect that is not a login is an unexpected status`() {
        val fake = FakeTransport()
        fake.onGetPrefix(rootPrefix, Fixtures.of("", 302, mapOf("Location" to "https://elsewhere.example.com/x")))

        val failure = assertFailsWith<JenkinsException.UnexpectedStatus> { client(fake).rootJobs() }

        assertEquals(302, failure.status)
    }

    // ---------------------------------------------------------------- traversal

    @Test
    fun `root jobs come back with a locally built url`() {
        val fake = FakeTransport()
        fake.onGetPrefix(rootPrefix, Fixtures.json("04.rootjobs.json"))

        val jobs = client(fake).rootJobs()

        assertEquals(1, fake.count)
        assertEquals(listOf("freestyle-fail", "multibranch-demo", "stagecraft"), jobs.map { it.fullName })
        assertEquals(
            listOf(JobKind.FREESTYLE, JobKind.MULTIBRANCH, JobKind.FOLDER),
            jobs.map { it.kind },
        )
        assertEquals(base + "job/freestyle-fail/", jobs[0].url)
        assertEquals(BuildStatus.FAILURE, jobs[0].status)
        assertTrue(jobs[2].isContainer)
        assertFalse(jobs[0].isContainer)
        assertTrue(jobs.all { it.depth == 1 })
    }

    @Test
    fun `three levels of nesting arrive in one request`() {
        // §9.4: no search endpoint, so discovery is one nested tree query.
        val fake = FakeTransport()
        fake.onGetPrefix(rootPrefix, Fixtures.json("rechecks/r5-tree-3-levels.json"))

        val jobs = client(fake).jobTree()

        assertEquals(1, fake.count, "the whole tree must come back in a single request")
        assertEquals(
            listOf(
                "freestyle-fail",
                "multibranch-demo",
                "multibranch-demo/feature/ORD-214",
                "multibranch-demo/main",
                "stagecraft",
                "stagecraft/deep",
                "stagecraft/deep/nested-freestyle",
            ),
            jobs.map { it.fullName },
        )
        assertEquals(listOf(1, 1, 2, 2, 1, 2, 3), jobs.map { it.depth })
    }

    @Test
    fun `the discovery query does not ask for urls`() {
        // Requesting `url` multiplies the payload for a value we cannot use, because Jenkins
        // computes it from its own root URL rather than the address we reached it on.
        val fake = FakeTransport()
        fake.onGetPrefix(rootPrefix, Fixtures.json("04.rootjobs.json"))

        client(fake).rootJobs()

        val tree = java.net.URLDecoder.decode(fake.requests.single().url.substringAfter("?tree="), "UTF-8")
        assertEquals("jobs[name,_class,color]", tree)
        assertFalse(fake.requests.single().url.contains("url"))
    }

    @Test
    fun `a branch job keeps its encoded name and gets a readable one`() {
        // Re-check R5: Jenkins reports the branch as `feature%2FORD-214` and its own url as
        // `.../job/feature%252FORD-214/`. The raw name is what goes back into a URL; the decoded
        // one is what a human reads.
        val fake = FakeTransport()
        fake.onGetPrefix(base + "job/multibranch-demo/api/json", Fixtures.json("05.mbjobs.json"))

        val jobs = client(fake).childJobs(listOf("multibranch-demo"))

        assertEquals(listOf("feature%2FORD-214", "main"), jobs.map { it.name })
        assertEquals(listOf("feature/ORD-214", "main"), jobs.map { it.displayName })
        assertEquals(
            listOf(listOf("multibranch-demo", "feature%2FORD-214"), listOf("multibranch-demo", "main")),
            jobs.map { it.rawPath },
        )
        assertEquals(
            base + "job/multibranch-demo/job/feature%252FORD-214/",
            jobs[0].url,
        )
        assertEquals("multibranch-demo/feature/ORD-214", jobs[0].fullName)
        assertEquals(BuildStatus.UNSTABLE, jobs[0].status)
        assertEquals(BuildStatus.FAILURE, jobs[1].status)
    }

    @Test
    fun `a pull request job is a child job like any other`() {
        val fake = FakeTransport()
        fake.onGetPrefix(base + "job/github-mb-fixture/api/json", Fixtures.json("rechecks/r8-child-jobs.json"))

        val jobs = client(fake).childJobs(listOf("github-mb-fixture"))

        assertEquals(listOf("feature/ORD-214", "main", "PR-1"), jobs.map { it.displayName })
        assertEquals(BuildStatus.NOT_BUILT, jobs[2].status)
        assertEquals(base + "job/github-mb-fixture/job/PR-1/", jobs[2].url)
    }

    @Test
    fun `child jobs of the root are the root jobs`() {
        val fake = FakeTransport()
        fake.onGetPrefix(rootPrefix, Fixtures.json("04.rootjobs.json"))

        assertEquals(3, client(fake).childJobs(emptyList()).size)
        assertEquals(1, fake.count)
    }

    // ---------------------------------------------------------------- builds

    @Test
    fun `a finished build is read with its result and duration`() {
        val fake = FakeTransport()
        fake.onGetPrefix(base + "job/multibranch-demo/job/main/api/json", Fixtures.json("06.branchbuilds.json"))

        val builds = client(fake).builds(listOf("multibranch-demo", "main"))

        assertEquals(1, builds.size)
        val build = builds.single()
        assertEquals(1, build.number)
        assertEquals("#1", build.displayName)
        assertEquals(BuildStatus.FAILURE, build.status)
        assertFalse(build.isRunning)
        assertEquals(11157L, build.durationMillis)
        assertEquals(1790863881145L, build.timestampMillis)
        assertEquals(mainBuildUrl, build.url)
        assertEquals("multibranch-demo/main", build.jobFullName)
    }

    @Test
    fun `a running build is running, not not-built`() {
        // Re-check R9: a running build has `result: null`, and `null` must not be read as
        // NOT_BUILT, which is a real and different result.
        val fake = FakeTransport()
        fake.onGetPrefix(base + "job/stagecraft/job/long-run/api/json", Fixtures.json("rechecks/r9-running-build.json"))

        val build = client(fake).builds(listOf("stagecraft", "long-run")).single()

        assertEquals(BuildStatus.RUNNING, build.status)
        assertTrue(build.isRunning)
        assertEquals(0L, build.durationMillis)
        assertEquals(base + "job/stagecraft/job/long-run/1/", build.url)
    }

    @Test
    fun `a build url is rebased onto the address we actually reached`() {
        // Re-check R4: this is the real url out of r4-https-build-url.json, captured over HTTP from
        // a server whose configured root is https://localhost:18443. Using it verbatim would send
        // the next request to the wrong host and port.
        val recordedUrl = parseJsonObject(Fixtures.text("rechecks/r4-https-build-url.json")).str("url")!!
        assertTrue(recordedUrl.startsWith("https://localhost:18443/"), recordedUrl)

        val fake = FakeTransport()
        fake.onGetPrefix(
            base + "job/multibranch-demo/job/main/api/json",
            Fixtures.of("""{"builds":[{"number":1,"result":"SUCCESS","url":"$recordedUrl"}]}"""),
        )

        val build = client(fake).builds(listOf("multibranch-demo", "main")).single()

        assertEquals(mainBuildUrl, build.url)
    }

    @Test
    fun `the build query asks for a bounded number of builds`() {
        val fake = FakeTransport()
        fake.onGetPrefix(base + "job/multibranch-demo/job/main/api/json", Fixtures.json("06.branchbuilds.json"))

        client(fake).builds(listOf("multibranch-demo", "main"), limit = 5)

        val tree = java.net.URLDecoder.decode(fake.requests.single().url.substringAfter("?tree="), "UTF-8")
        // No `url`: build URLs are built locally, so the field would only cost payload.
        assertEquals("builds[_class,number,result,building,timestamp,duration]{0,5}", tree)
    }

    // ---------------------------------------------------------------- consoles

    @Test
    fun `a finished console is one chunk whose cursor is not its length`() {
        val headers = Fixtures.finishedFirstChunkHeaders
        val fake = FakeTransport()
        fake.onGet(
            "$mainBuildUrl" + "logText/progressiveText?start=0",
            HttpResponse(headers.status, headers.headers, Fixtures.bytes("16.progressive-main-finished.raw.txt")),
        )

        val chunk = client(fake).progressiveText(mainBuildUrl)

        assertEquals(12263L, chunk.nextOffset)
        assertEquals(12263, headers.header("X-Text-Size")!!.toLong().toInt())
        assertEquals(12336, chunk.rawByteCount, "the body is 12336 bytes")
        assertTrue(chunk.nextOffset != chunk.rawByteCount.toLong(), "the cursor must not be derived from the body")
        assertFalse(chunk.moreData, "X-More-Data is absent on a finished build, not false")
        assertFalse(chunk.resetDetected)
        assertEquals(Fixtures.text("08.console-main.txt"), chunk.text)
    }

    @Test
    fun `a running console asks for more`() {
        val headers = Fixtures.runningFirstChunkHeaders
        val fake = FakeTransport()
        fake.onGet(
            "$tickingBuildUrl" + "logText/progressiveText?start=0",
            HttpResponse(headers.status, headers.headers, Fixtures.bytes("16.progressive-running-first.raw.txt")),
        )

        val chunk = client(fake).progressiveText(tickingBuildUrl)

        assertEquals(4033L, chunk.nextOffset)
        assertEquals(4050, chunk.rawByteCount)
        assertTrue(chunk.moreData)
        assertFalse(chunk.resetDetected)
        assertTrue(chunk.text.contains("[Pipeline]"))
    }

    @Test
    fun `an empty delta is not a reset`() {
        // Re-check R1: polling a running build with the cursor it just gave back answers 200 with
        // no body and X-More-Data still true. The cursor does not move, and that is not a reset -
        // treating it as one would reprint the whole log every two seconds.
        val headers = Fixtures.runningDeltaHeaders
        val fake = FakeTransport()
        fake.onGet(
            "$mainBuildUrl" + "logText/progressiveText?start=2815",
            HttpResponse(headers.status, headers.headers, ByteArray(0)),
        )

        val chunk = client(fake).progressiveText(mainBuildUrl, start = 2815)

        assertEquals(2815L, chunk.nextOffset)
        assertTrue(chunk.moreData)
        assertTrue(chunk.isEmpty)
        assertFalse(chunk.resetDetected)
    }

    @Test
    fun `a cursor at the end of a finished log returns nothing`() {
        val headers = Fixtures.atEndHeaders
        val fake = FakeTransport()
        fake.onGet(
            "$mainBuildUrl" + "logText/progressiveText?start=17527",
            HttpResponse(headers.status, headers.headers, ByteArray(0)),
        )

        val chunk = client(fake).progressiveText(mainBuildUrl, start = 17527)

        assertEquals(17527L, chunk.nextOffset)
        assertEquals(0, chunk.rawByteCount)
        assertTrue(chunk.isEmpty)
        assertFalse(chunk.moreData)
        assertFalse(chunk.resetDetected)
    }

    @Test
    fun `a cursor past the end is flagged as a reset`() {
        // Re-check R1: asking for an offset past the end of a finished log answers 200 with the
        // **whole log again** and an unchanged cursor. Appending that would duplicate the console,
        // so the reader has to be told to resynchronise.
        val headers = Fixtures.beyondEndHeaders
        assertEquals(17527, headers.header("X-Text-Size")!!.toInt())
        assertEquals(17624, headers.header("Content-Length")!!.toInt())

        val fake = FakeTransport()
        fake.onGet(
            "$mainBuildUrl" + "logText/progressiveText?start=20000",
            HttpResponse(headers.status, headers.headers, bodyOfLength(headers)),
        )

        val chunk = client(fake).progressiveText(mainBuildUrl, start = 20_000)

        assertTrue(chunk.resetDetected)
        assertEquals(17527L, chunk.nextOffset)
        assertEquals(17624, chunk.rawByteCount, "the whole log came back")
        assertFalse(chunk.moreData)
    }

    @Test
    fun `a chunk with no cursor header is malformed`() {
        val fake = FakeTransport()
        fake.onGet("$mainBuildUrl" + "logText/progressiveText?start=0", Fixtures.of("some text", 200))

        val failure = assertFailsWith<JenkinsException.Malformed> { client(fake).progressiveText(mainBuildUrl) }

        assertTrue(failure.detail.contains("X-Text-Size"), failure.detail)
    }

    @Test
    fun `the whole console is streamed and normalised`() {
        val fake = FakeTransport()
        fake.onGet(mainBuildUrl + "consoleText", Fixtures.json("08.console-main.txt"))

        val text = client(fake).consoleText(mainBuildUrl)

        assertEquals(Fixtures.text("08.console-main.txt"), text)
        assertEquals(2856, text.toByteArray(Charsets.UTF_8).size)
        assertFalse(text.contains('\r'))
    }

    @Test
    fun `streaming a console writes the same bytes without buffering`() {
        val fake = FakeTransport()
        fake.onGet(mainBuildUrl + "consoleText", Fixtures.json("08.console-main.txt"))

        val out = java.io.ByteArrayOutputStream()
        val written = client(fake).streamConsoleText(mainBuildUrl, out)

        assertEquals(2856L, written)
        assertEquals(Fixtures.text("08.console-main.txt"), out.toString("UTF-8"))
    }

    @Test
    fun `a console that is too large is refused rather than buffered`() {
        val fake = FakeTransport()
        fake.onGet(mainBuildUrl + "consoleText", Fixtures.json("08.console-main.txt"))

        assertFailsWith<JenkinsException.Malformed> { client(fake).consoleText(mainBuildUrl, maxBytes = 100) }
    }

    @Test
    fun `a missing console is a 404`() {
        val fake = FakeTransport()
        fake.onGet(mainBuildUrl + "consoleText", Fixtures.of("<html><title>Error 404 Not Found</title></html>", 404))

        assertFailsWith<JenkinsException.NotFound> { client(fake).consoleText(mainBuildUrl) }
    }

    @Test
    fun `the console streams into a bounded reader with its first error`() {
        val fake = FakeTransport()
        fake.onGet(mainBuildUrl + "consoleText", Fixtures.json("08.console-main.txt"))

        val log = client(fake).withConsoleText(mainBuildUrl) { ConsoleLogReader().read(it) }

        assertEquals(Fixtures.text("08.console-main.txt"), log.text)
        assertFalse(log.truncated)
        // The parser's oracle says the main build's first error is line 72; both share the predicate.
        assertEquals(72, log.firstErrorLine)
    }

    // ---------------------------------------------------------------- version

    @Test
    fun `the server version is available after the first call`() {
        val fake = FakeTransport()
        fake.onGetPrefix(rootPrefix, Fixtures.json("04.rootjobs.json", headers = mapOf("X-Jenkins" to "2.541.3")))

        val client = client(fake)
        client.rootJobs()

        assertEquals("2.541.3", client.version.toString())
        assertEquals(null, client.versionWarning)
    }

    @Test
    fun `a server below the floor warns but still answers`() {
        // §9.8: warn, never refuse.
        val fake = FakeTransport()
        fake.onGetPrefix(rootPrefix, Fixtures.json("04.rootjobs.json", headers = mapOf("X-Jenkins" to "2.164.1")))

        val client = client(fake)
        val jobs = client.rootJobs()

        assertEquals(3, jobs.size)
        assertTrue(client.version!!.isBelowFloor)
        assertTrue(client.versionWarning!!.contains("2.204.1"), client.versionWarning!!)
    }

    @Test
    fun `a bare server reports its own version from the recorded headers`() {
        val recorded = Fixtures.bareServerMe
        assertEquals("2.479.3", recorded.header("X-Jenkins"))

        val fake = FakeTransport()
        fake.onGetPrefix(mePrefix, recorded.asResponse())

        val client = client(fake)

        assertEquals("admin", client.me().id)
        assertEquals("2.479.3", client.version.toString())
    }

    // --------------------------------------------------------------- audit regressions

    @Test
    fun `under a context path a build url is built once and the console is read from it`() {
        val contextBase = "http://h:8080/jenkins/"
        val fake = FakeTransport()
            .onGetPrefix(
                contextBase + "job/a/api/json",
                Fixtures.of("""{"builds":[{"number":1,"url":"http://h:8080/jenkins/job/a/1/","result":"SUCCESS"}]}"""),
            )
            .onGetPrefix(contextBase + "job/a/1/consoleText", Fixtures.of("log"))
        val client = JenkinsClient(contextBase, JenkinsAuth(JenkinsCredential.ApiToken("u", "t")), fake)

        val build = client.builds(listOf("a")).single()
        client.consoleText(build.url)

        assertEquals("http://h:8080/jenkins/job/a/1/", build.url)
        assertEquals("http://h:8080/jenkins/job/a/1/consoleText", fake.urls().last())
    }

    @Test
    fun `a container below the third level is followed with one more request`() {
        val fake = FakeTransport()
        fake.onGetPrefix(
            rootPrefix,
            Fixtures.of(
                """{"jobs":[{"name":"team","_class":"$FOLDER_CLASS","jobs":[{"name":"svc","_class":"$FOLDER_CLASS",""" +
                    """"jobs":[{"name":"api","_class":"$MULTIBRANCH_CLASS"}]}]}]}""",
            ),
        )
        fake.onGetPrefix(
            base + "job/team/job/svc/job/api/api/json",
            Fixtures.of("""{"jobs":[{"name":"feature%2Fx","_class":"$WORKFLOW_CLASS"}]}"""),
        )

        val tree = client(fake).jobTreeDeep()

        assertTrue(tree.complete)
        val branch = tree.jobs.single { it.displayName == "feature/x" }
        assertEquals(listOf("team", "svc", "api", "feature%2Fx"), branch.rawPath)
        assertEquals(4, branch.depth)
        assertEquals(2, fake.count)
    }

    @Test
    fun `the deep walk skips a subtree it may not read and admits a spent budget`() {
        val fake = FakeTransport()
        fake.onGetPrefix(
            rootPrefix,
            Fixtures.of(
                """{"jobs":[{"name":"a","_class":"$FOLDER_CLASS","jobs":[{"name":"b","_class":"$FOLDER_CLASS",""" +
                    """"jobs":[{"name":"hidden","_class":"$FOLDER_CLASS"},{"name":"deep","_class":"$FOLDER_CLASS"}]}]}]}""",
            ),
        )
        fake.onGetPrefix(base + "job/a/job/b/job/hidden/", Fixtures.empty(404))
        fake.onGetPrefix(base + "job/a/job/b/job/deep/", Fixtures.of("""{"jobs":[]}"""))

        assertTrue(client(fake).jobTreeDeep().complete)
        assertFalse(client(fake).jobTreeDeep(maxSubtreeRequests = 1).complete)
    }
}
