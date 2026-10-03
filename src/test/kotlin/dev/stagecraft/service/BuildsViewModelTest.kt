package dev.stagecraft.service

import dev.stagecraft.FakeTransport
import dev.stagecraft.Fixtures
import dev.stagecraft.jenkins.JenkinsAuth
import dev.stagecraft.jenkins.JenkinsClient
import dev.stagecraft.jenkins.JenkinsCredential
import dev.stagecraft.jenkins.JenkinsException
import dev.stagecraft.jenkins.HttpTransport
import dev.stagecraft.jenkins.JobIndex
import dev.stagecraft.jenkins.FOLDER_CLASS
import dev.stagecraft.jenkins.MULTIBRANCH_CLASS
import dev.stagecraft.jenkins.WORKFLOW_CLASS
import dev.stagecraft.jenkins.jobFromName
import dev.stagecraft.jenkins.testJob
import dev.stagecraft.model.BuildStatus
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class BuildsViewModelTest {

    private val base = "http://localhost:18080/"

    // --------------------------------------------------------------- helpers

    private fun tempDir(): File = Files.createTempDirectory("stagecraft-svc").toFile()

    private fun transport(): FakeTransport = FakeTransport()

    /** The client factory every test uses; [fake] is shared so requests can be counted. */
    private fun factory(fake: FakeTransport): JenkinsClientFactory =
        JenkinsClientFactory { baseUrl, user, token ->
            JenkinsClient(baseUrl, JenkinsAuth(JenkinsCredential.ApiToken(user, token)), fake)
        }

    private fun loader(
        credentials: CredentialStore = InMemoryCredentialStore().apply { save(base, "admin", "token") },
        fake: FakeTransport = transport(),
        cacheDir: File? = null,
    ): Pair<DefaultBuildsLoader, FakeTransport> =
        DefaultBuildsLoader(credentials, factory(fake), cacheDir) to fake

    /** An index with svc (multibranch) + its main branch child, cached for [base]. */
    private fun seedCache(dir: File) {
        JobIndex.save(
            dir,
            JobIndex.of(
                listOf(
                    testJob(listOf("svc"), MULTIBRANCH_CLASS),
                    testJob(listOf("svc", "main"), WORKFLOW_CLASS),
                    testJob(listOf("unrelated"), FOLDER_CLASS),
                ),
                serverUrl = base,
                // Fresh: a cache older than the loader's refresh window is refetched on load.
                fetchedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun configured() = StagecraftState(serverUrl = base, user = "admin")

    /** A model whose executor runs inline, so a state is final as soon as the call returns. */
    private fun viewModel(
        credentials: CredentialStore = InMemoryCredentialStore().apply { save(base, "admin", "token") },
        fake: FakeTransport = transport(),
        cacheDir: File? = null,
    ): BuildsViewModel = BuildsViewModel(
        DefaultBuildsLoader(credentials, factory(fake), cacheDir),
        Executor { it.run() },
    )

    private fun ctx(
        remote: String? = "git@github.com:corp/svc.git",
        branch: String? = "main",
        pr: Int? = null,
    ) = BranchContext(remote, branch, pr)

    private fun FakeTransport.routeMe() {
        // The tree parameter is URL-encoded by the client, so route by prefix.
        onGetPrefix(base + "me/api", Fixtures.json("01.me.json"))
    }

    // --------------------------------------------------------------- states

    @Test
    fun `unconfigured loads nothing and says so`() {
        val (model, fake) = loader(credentials = InMemoryCredentialStore(), cacheDir = null)

        val state = model.load(StagecraftState(), ctx())

        assertEquals(ToolWindowState.Unconfigured, state)
        assertEquals(0, fake.count)
    }

    @Test
    fun `a missing token explains where to put one and retrying cannot help`() {
        val (model, fake) = loader(credentials = InMemoryCredentialStore(), cacheDir = null)

        val state = model.load(configured(), ctx())

        assertTrue(state is ToolWindowState.Failed, "was $state")
        assertTrue(state.reason.contains("token"), state.reason)
        assertTrue(state.reason.contains("Settings"), state.reason)
        assertTrue(!state.retryable)
        assertEquals(0, fake.count)
    }

    @Test
    fun `a missing git remote explains what Stagecraft needs`() {
        val (model, fake) = loader(cacheDir = null)

        val state = model.load(configured(), ctx(remote = null))

        assertTrue(state is ToolWindowState.Failed, "was $state")
        assertTrue(state.reason.contains("git remote"), state.reason)
        assertTrue(state.retryable)
        assertEquals(0, fake.count)
    }

    @Test
    fun `branch builds load newest first without being told a job name`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.routeMe()
            fake.onGetPrefix(base + "job/svc/job/main/api", Fixtures.json("06.branchbuilds.json"))
            fake.onGetPrefix(base + "job/svc/job/main/1/api", Fixtures.json("07.remotedata.json"))
            val (model, _) = loader(fake = fake, cacheDir = dir)

            val state = model.load(configured(), ctx())

            assertTrue(state is ToolWindowState.Ready, "was $state")
            assertEquals(1, state.builds.size)
            val build = state.builds[0]
            assertEquals(1, build.number)
            assertEquals(BuildStatus.FAILURE, build.status)
            // Built from the matched job, not copied from the payload (whose url names another job).
            assertEquals("http://localhost:18080/job/svc/job/main/1/", build.url)
            assertTrue(state.how.contains("main"), state.how)
            // Exactly three calls, in this order: the identity check, the branch's builds, and the
            // one-build confirmation of §9.4 step 3. The warm index is read from disk, so the tree
            // is never refetched (§9.2).
            assertEquals(3, fake.count)
            assertTrue(fake.urls()[0].startsWith(base + "me/api"), fake.urls().toString())
            assertTrue(
                fake.urls()[1].startsWith(base + "job/svc/job/main/api/json?tree=builds"),
                fake.urls().toString(),
            )
            assertTrue(fake.urls()[2].startsWith(base + "job/svc/job/main/1/api/json?tree=actions"), fake.urls().toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an empty job says so instead of pretending it is ready`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.routeMe()
            fake.onGetPrefix(base + "job/svc/job/main/api", Fixtures.of("""{"builds":[]}"""))
            val (model, _) = loader(fake = fake, cacheDir = dir)

            val state = model.load(configured(), ctx())

            assertTrue(state is ToolWindowState.Empty, "was $state")
            assertEquals("main", state.job.displayName)
            assertTrue(state.how.contains("main"), state.how)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a dead server is a retryable failure, not a crash`() {
        val model = DefaultBuildsLoader(
            InMemoryCredentialStore().apply { save(base, "admin", "token") },
            JenkinsClientFactory { baseUrl, user, token ->
                JenkinsClient(
                    baseUrl,
                    JenkinsAuth(JenkinsCredential.ApiToken(user, token)),
                    HttpTransport { throw JenkinsException.Transport("connection refused") },
                )
            },
            cacheDir = null,
        )

        val state = model.load(configured(), ctx())

        assertTrue(state is ToolWindowState.Failed, "was $state")
        assertTrue(state.retryable)
        assertTrue(state.reason.isNotBlank())
    }

    @Test
    fun `an anonymous answer is named at connection time`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.onGetPrefix(
                base + "me/api",
                Fixtures.of("""{"id":"anonymous","fullName":"Anonymous"}"""),
            )
            val (model, _) = loader(fake = fake, cacheDir = dir)

            val state = model.load(configured(), ctx())

            assertTrue(state is ToolWindowState.Failed, "was $state")
            assertTrue(state.reason.contains("anonymous"), state.reason)
            // Credentials are verified before anything else; the index must not be fetched.
            assertTrue(fake.urls().none { it.contains("api/json?tree=") && !it.contains("/me/") }, fake.urls().toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a 401 is a readable failure`() {
        val (model, fake) = loader(cacheDir = null)
        fake.onGetPrefix(
            base + "me/api",
            Fixtures.empty(401, mapOf("WWW-Authenticate" to "Basic realm=\"Jenkins\"")),
        )

        val state = model.load(configured(), ctx())

        assertTrue(state is ToolWindowState.Failed, "was $state")
        assertTrue(state.reason.contains("401"), state.reason)
        assertTrue(state.retryable)
    }

    @Test
    fun `a pinned job wins over the matcher`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.routeMe()
            fake.onGetPrefix(base + "job/othername/job/main/api", Fixtures.json("06.branchbuilds.json"))
            val (model, _) = loader(fake = fake, cacheDir = dir)

            val state = model.load(
                configured().copy(pinnedJobs = mapOf("github.com/corp/svc" to "othername")),
                ctx(),
            )

            assertTrue(state is ToolWindowState.Ready, "was $state")
            assertTrue(state.how.contains("pinned"), state.how)
            // The pin names a job the index has never held, so the branch child is built from the
            // pin rather than silently replaced by the closest name match (§9.4 step 5).
            assertTrue(
                fake.urls().any { it.startsWith(base + "job/othername/job/main/api/json?tree=builds") },
                fake.urls().toString(),
            )
            assertEquals("othername/main", state.builds[0].jobFullName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a version below the floor is carried to the UI as a warning`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.onGetPrefix(
                base + "me/api",
                Fixtures.json("01.me.json", headers = mapOf("X-Jenkins" to "2.164.3")),
            )
            fake.onGetPrefix(
                base + "job/svc/job/main/api",
                Fixtures.json("06.branchbuilds.json", headers = mapOf("X-Jenkins" to "2.164.3")),
            )
            val (model, _) = loader(fake = fake, cacheDir = dir)

            val state = model.load(configured(), ctx())

            assertTrue(state is ToolWindowState.Ready, "was $state")
            assertTrue(!state.versionWarning.isNullOrBlank())
        } finally {
            dir.deleteRecursively()
        }
    }

    // --------------------------------------------------------------- first paint

    @Test
    fun `first paint with a warm cache names the job and costs no network`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val (model, fake) = loader(cacheDir = dir)

            var painted: ToolWindowState? = null
            val elapsed = measureTimeMillis { painted = model.snapshotForFirstPaint(configured(), ctx()) }
            val state = painted ?: fail("no state was painted")

            assertTrue(state is ToolWindowState.Loading, "was $state")
            assertTrue(state.hint?.contains("main") == true, state.hint)
            assertEquals(0, fake.count)
            assertTrue(elapsed < 200, "first paint took $elapsed ms; the exit criterion is 200")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `first paint with no cache shows an honest loading and costs no network`() {
        val (model, fake) = loader(cacheDir = null)

        val state = model.snapshotForFirstPaint(configured(), ctx())

        assertEquals(ToolWindowState.Loading(null), state)
        assertEquals(0, fake.count)
    }

    @Test
    fun `first paint before settings lands on Unconfigured`() {
        val (model, fake) = loader(cacheDir = null)

        val state = model.snapshotForFirstPaint(StagecraftState(), ctx())

        assertEquals(ToolWindowState.Unconfigured, state)
        assertEquals(0, fake.count)
    }

    @Test
    fun `first paint paints the cached rows, with their age, and costs no network`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.routeMe()
            fake.onGetPrefix(base + "job/svc/job/main/api", Fixtures.json("06.branchbuilds.json"))
            fake.onGetPrefix(base + "job/svc/job/main/1/api", Fixtures.json("07.remotedata.json"))
            val (model, _) = loader(fake = fake, cacheDir = dir)
            assertTrue(model.load(configured(), ctx()) is ToolWindowState.Ready)
            val afterLoad = fake.count

            val state = model.snapshotForFirstPaint(configured(), ctx())

            assertTrue(state is ToolWindowState.Ready, "was $state")
            assertTrue(state.fromCache)
            assertEquals(1, state.builds.size)
            assertEquals(1, state.builds[0].number)
            assertTrue(state.ageMillis != null && state.ageMillis < 60_000, "age=${state.ageMillis}")
            assertEquals(afterLoad, fake.count)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an empty build list is cached and painted with its age`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.routeMe()
            fake.onGetPrefix(base + "job/svc/job/main/api", Fixtures.of("""{"builds":[]}"""))
            val (model, _) = loader(fake = fake, cacheDir = dir)
            assertTrue(model.load(configured(), ctx()) is ToolWindowState.Empty)

            val state = model.snapshotForFirstPaint(configured(), ctx())

            assertTrue(state is ToolWindowState.Empty, "was $state")
            assertTrue(state.ageMillis != null, "age=${state.ageMillis}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a cached list for one branch is not painted for another`() {
        val dir = tempDir()
        try {
            JobIndex.save(
                dir,
                JobIndex.of(
                    listOf(testJob(listOf("svc"), WORKFLOW_CLASS)),
                    serverUrl = base,
                    fetchedAtMillis = System.currentTimeMillis(),
                ),
            )
            val fake = transport()
            fake.routeMe()
            fake.onGetPrefix(base + "job/svc/api", Fixtures.json("06.branchbuilds.json"))
            fake.onGetPrefix(base + "job/svc/1/api", Fixtures.json("07.remotedata.json"))
            val (model, _) = loader(fake = fake, cacheDir = dir)
            assertTrue(model.load(configured(), ctx(branch = "main")) is ToolWindowState.Ready)

            val other = model.snapshotForFirstPaint(configured(), ctx(branch = "feature-x"))

            assertTrue(other is ToolWindowState.Loading, "was $other")
        } finally {
            dir.deleteRecursively()
        }
    }

    // --------------------------------------------------------------- cancel

    @Test
    fun `cancelling a slow load discards its answer`() {
        val gate = CountDownLatch(1)
        val loader = object : BuildsLoader {
            override fun load(state: StagecraftState, ctx: BranchContext): ToolWindowState {
                gate.await()
                return ToolWindowState.Ready(emptyList(), jobFromName("svc", base), "loaded", null, false)
            }

            override fun snapshotForFirstPaint(state: StagecraftState, ctx: BranchContext): ToolWindowState =
                ToolWindowState.Loading(null)
        }
        val io = Executors.newSingleThreadExecutor()
        try {
            val model = BuildsViewModel(loader, io)
            model.refresh(configured(), ctx())

            model.cancel()
            assertEquals(ToolWindowState.Cancelled, model.state)

            gate.countDown()
            io.submit {}.get() // let the discarded load finish

            assertEquals(ToolWindowState.Cancelled, model.state)
        } finally {
            io.shutdownNow()
        }
    }

    @Test
    fun `retry after a cancel loads again`() {
        val io = Executors.newSingleThreadExecutor()
        try {
            val loader = object : BuildsLoader {
                override fun load(state: StagecraftState, ctx: BranchContext): ToolWindowState =
                    ToolWindowState.Ready(emptyList(), jobFromName("svc", base), "loaded", null, false)

                override fun snapshotForFirstPaint(state: StagecraftState, ctx: BranchContext): ToolWindowState =
                    ToolWindowState.Loading(null)
            }
            val model = BuildsViewModel(loader, io)
            model.cancel()

            model.refresh(configured(), ctx())
            io.submit {}.get()

            assertTrue(model.state is ToolWindowState.Ready, "was ${model.state}")
        } finally {
            io.shutdownNow()
        }
    }

    // ------------------------------------------------- placeholder vs. the first load

    @Test
    fun `a fresh model says it is checking, not that nothing is configured`() {
        val model = viewModel(cacheDir = null)

        // The panel paints this value before any load runs; an Unconfigured default would tell a
        // configured project, in the plugin's own words, that its settings are gone.
        assertTrue(model.state is ToolWindowState.Loading, "was ${model.state}")
    }

    @Test
    fun `the placeholder reports saved settings without touching the network`() {
        val fake = transport()
        val model = viewModel(cacheDir = null, fake = fake)
        val seen = ArrayList<ToolWindowState>()
        model.onState = { seen += it }

        model.showPlaceholder(isConfigured = false)

        assertEquals(listOf<ToolWindowState>(ToolWindowState.Unconfigured), seen)
        assertEquals(0, fake.count)
    }

    @Test
    fun `the placeholder leaves a configured project checking`() {
        val model = viewModel(cacheDir = null)
        val seen = ArrayList<ToolWindowState>()
        model.onState = { seen += it }

        model.showPlaceholder(isConfigured = true)

        assertEquals(listOf<ToolWindowState>(ToolWindowState.Loading(null)), seen)
    }

    @Test
    fun `the placeholder cannot overwrite a load that has already started`() {
        val model = viewModel(cacheDir = null)

        model.paintFirst(configured(), ctx())
        val settled = model.state
        model.showPlaceholder(isConfigured = false)

        // An unguarded placeholder would put Unconfigured here, i.e. flip the window back to
        // "configure me" over a load that is already running.
        assertEquals(settled, model.state)
        assertEquals(ToolWindowState.Loading(null), model.state)
    }

    // --------------------------------------------------------------- the model

    @Test
    fun `the model paints first, then loads, then reports ready`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.routeMe()
            fake.onGetPrefix(base + "job/svc/job/main/api", Fixtures.json("06.branchbuilds.json"))
            val seen = ArrayList<ToolWindowState>()
            val model = BuildsViewModel(
                DefaultBuildsLoader(
                    InMemoryCredentialStore().apply { save(base, "admin", "token") },
                    factory(fake),
                    dir,
                ),
                Executor { it.run() },
            )
            model.onState = { seen += it }

            model.paintFirst(configured(), ctx())
            model.refresh(configured(), ctx())

            assertTrue(seen.first() is ToolWindowState.Loading, seen.toString())
            assertTrue(seen.last() is ToolWindowState.Ready, seen.toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `refreshing an unconfigured project never flashes a spinner`() {
        val fake = transport()
        val seen = ArrayList<ToolWindowState>()
        val model = BuildsViewModel(
            DefaultBuildsLoader(InMemoryCredentialStore(), factory(fake), null),
            Executor { it.run() },
        )
        model.onState = { seen += it }

        model.refresh(StagecraftState(), ctx())

        assertTrue(seen.none { it is ToolWindowState.Loading }, seen.toString())
        assertEquals(ToolWindowState.Unconfigured, model.state)
    }

    @Test
    fun `a failed load is carried through the model`() {
        val fake = transport()
        // Nothing routed: the /me call errors out as an unrouted transport failure.
        val model = BuildsViewModel(
            DefaultBuildsLoader(
                InMemoryCredentialStore().apply { save(base, "admin", "token") },
                factory(fake),
                null,
            ),
            Executor { it.run() },
        )

        model.refresh(configured(), ctx(remote = null))

        val state = model.state
        assertTrue(state is ToolWindowState.Failed, "was $state")
        assertTrue(state.reason.contains("git remote"), state.reason)
    }

    // --------------------------------------------------------------- the settings bean

    @Test
    fun `a fresh state bean is empty and not configured`() {
        val state = StagecraftState()

        assertTrue(!state.isConfigured)
        assertTrue(!state.trustCertificate)
        assertTrue(state.useProxy)
        assertEquals(emptyMap<String, String>(), state.pinnedJobs)
    }

    // --------------------------------------------------------------- the poller

    @Test
    fun `the poller repeats until cancelled and then stays silent`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            var fires = 0
            val poller = BuildPoller(scheduler, periodMillis = 10)
            val handle = poller.start { fires++ }

            Thread.sleep(250)
            assertTrue(fires >= 2, "poller fired $fires times in 250 ms")
            handle.cancel()
            val afterCancel = fires

            Thread.sleep(200)
            assertEquals(afterCancel, fires)
        } finally {
            scheduler.shutdownNow()
        }
    }

    // --------------------------------------------------------------- audit regressions

    private val treeWithNewBranch =
        """{"jobs":[{"name":"svc","_class":"$MULTIBRANCH_CLASS","jobs":[""" +
            """{"name":"main","_class":"$WORKFLOW_CLASS"},{"name":"feature-x","_class":"$WORKFLOW_CLASS"}]}]}"""

    @Test
    fun `a branch pushed after the index was cached is found by refetching the index`() {
        val dir = tempDir()
        try {
            JobIndex.save(
                dir,
                JobIndex.of(
                    listOf(testJob(listOf("svc"), MULTIBRANCH_CLASS), testJob(listOf("svc", "main"), WORKFLOW_CLASS)),
                    serverUrl = base,
                    fetchedAtMillis = 1_000_000,
                ),
            )
            val fake = transport()
            fake.routeMe()
            fake.onGetPrefix(base + "api/json", Fixtures.of(treeWithNewBranch))
            fake.onGetPrefix(base + "job/svc/job/feature-x/api", Fixtures.json("06.branchbuilds.json"))
            val loader = DefaultBuildsLoader(
                InMemoryCredentialStore().apply { save(base, "admin", "token") },
                factory(fake),
                dir,
                clock = { 1_000_000 + DefaultBuildsLoader.MISS_REFETCH_AGE_MILLIS + 1 },
            )

            val state = loader.load(configured(), ctx(branch = "feature-x"))

            assertTrue(state is ToolWindowState.Ready, "was $state")
            assertEquals("feature-x", state.job.displayName)
            // ...and the fresher index is what the next IDE start reads.
            assertTrue(JobIndex.load(dir, base)!!.jobs.any { it.displayName == "feature-x" })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a miss on a just-fetched index does not refetch on every poll`() {
        val dir = tempDir()
        try {
            seedCache(dir) // fetched "now"
            val fake = transport()
            fake.routeMe()
            val (loader, _) = loader(fake = fake, cacheDir = dir)

            val state = loader.load(configured(), ctx(branch = "not-pushed-yet"))

            assertTrue(state is ToolWindowState.Failed, "was $state")
            assertTrue(state.reason.contains("not-pushed-yet"), state.reason)
            assertTrue(fake.urls().none { it.startsWith(base + "api/json") }, fake.urls().toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an old index is refetched even on a hit`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.routeMe()
            fake.onGetPrefix(base + "api/json", Fixtures.of(treeWithNewBranch))
            fake.onGetPrefix(base + "job/svc/job/main/api", Fixtures.json("06.branchbuilds.json"))
            val loader = DefaultBuildsLoader(
                InMemoryCredentialStore().apply { save(base, "admin", "token") },
                factory(fake),
                dir,
                clock = { System.currentTimeMillis() + DefaultBuildsLoader.MAX_INDEX_AGE_MILLIS + 1 },
            )

            assertTrue(loader.load(configured(), ctx()) is ToolWindowState.Ready)
            assertEquals(1, fake.urls().count { it.startsWith(base + "api/json") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `credentials are verified once per client, not on every poll`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.routeMe()
            fake.onGetPrefix(base + "job/svc/job/main/api", Fixtures.json("06.branchbuilds.json"))
            fake.onGetPrefix(base + "job/svc/job/main/1/api", Fixtures.of("""{"actions":[]}"""))
            val client = JenkinsClient(base, JenkinsAuth(JenkinsCredential.ApiToken("admin", "token")), fake)
            val loader = DefaultBuildsLoader(
                InMemoryCredentialStore().apply { save(base, "admin", "token") },
                { _, _, _ -> client },
                dir,
            )

            repeat(3) { assertTrue(loader.load(configured(), ctx()) is ToolWindowState.Ready) }

            assertEquals(1, fake.requestsMatching("/me/api").size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a poll over a list on screen is quiet`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.routeMe()
            fake.onGetPrefix(base + "job/svc/job/main/api", Fixtures.json("06.branchbuilds.json"))
            val seen = ArrayList<ToolWindowState>()
            val model = viewModel(fake = fake, cacheDir = dir)
            model.refresh(configured(), ctx())
            model.onState = { seen += it }

            model.refresh(configured(), ctx()) // what one poll tick does

            assertTrue(seen.none { it is ToolWindowState.Loading }, seen.toString())
            assertTrue(seen.single() is ToolWindowState.Ready, seen.toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a job whose newest build came from another repository says so, once`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.routeMe()
            fake.onGetPrefix(base + "job/svc/job/main/api", Fixtures.json("06.branchbuilds.json"))
            fake.onGetPrefix(
                base + "job/svc/job/main/1/api",
                Fixtures.of("""{"actions":[{"remoteUrls":["https://github.com/other-org/svc.git"]}]}"""),
            )
            val (loader, _) = loader(fake = fake, cacheDir = dir)

            val first = loader.load(configured(), ctx())
            val second = loader.load(configured(), ctx())

            assertTrue(first is ToolWindowState.Ready && second is ToolWindowState.Ready)
            assertTrue(first.how.contains("other-org/svc"), first.how)
            assertEquals(first.how, second.how)
            assertEquals(1, fake.requestsMatching("job/svc/job/main/1/api").size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a token is the configured user's, not any user's on that server`() {
        val credentials = InMemoryCredentialStore().apply { save(base, "alice", "alices-token") }
        val (loader, fake) = loader(credentials = credentials)

        val state = loader.load(configured(), ctx()) // configured as "admin"

        assertTrue(state is ToolWindowState.Failed && !state.retryable, "was $state")
        assertEquals(0, fake.count)
    }

    @Test
    fun `cached rows painted at start are not the notification baseline`() {
        // The cached list is from the last session; the live list shows build 1 finished since then.
        // Announcing it on every IDE start is exactly what the baseline exists to prevent.
        val announced = ArrayList<Int>()
        val cached = ToolWindowState.Ready(
            builds = listOf(dev.stagecraft.model.BuildRef("svc/main", listOf("svc", "main"), 0, "u/0/", BuildStatus.SUCCESS, 0, 0)),
            job = testJob(listOf("svc", "main"), WORKFLOW_CLASS),
            how = "",
            versionWarning = null,
            fromCache = true,
        )
        val live = cached.copy(
            builds = listOf(dev.stagecraft.model.BuildRef("svc/main", listOf("svc", "main"), 1, "u/1/", BuildStatus.FAILURE, 0, 0)) + cached.builds,
            fromCache = false,
        )
        val states = ArrayDeque(listOf<ToolWindowState>(live, live))
        val model = BuildsViewModel(
            object : BuildsLoader {
                override fun load(state: StagecraftState, ctx: BranchContext) = states.removeFirst()
                override fun snapshotForFirstPaint(state: StagecraftState, ctx: BranchContext) = cached
            },
            Executor { it.run() },
        )
        model.onBuildFinished = { announced += it.number }

        model.paintFirst(configured(), ctx())
        model.refresh(configured(), ctx())

        assertEquals(emptyList(), announced)
    }
}
