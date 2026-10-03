package dev.stagecraft.jenkins

import dev.stagecraft.Fixtures
import dev.stagecraft.model.StageSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Executable spec for the console stage parser (charter section 9.3).
 * One-for-one port of `tools/test_parse_stages.py`; the two must stay in lockstep.
 */
class ConsoleStagesTest {

    private fun parse(text: String): ConsoleStages.StageParse = ConsoleStages.parse(text)

    private fun ranges(p: ConsoleStages.StageParse): List<Triple<String, Int, Int>> =
        p.stages.map { Triple(it.name, it.firstLine, it.lastLine) }

    private fun recordedRanges(name: String): List<Triple<String, Int, Int>> =
        parseJsonObject(Fixtures.text(name)).objects("stages").map {
            Triple(it.str("name")!!, it.int("firstLine")!!, it.int("lastLine")!!)
        }

    private fun byName(p: ConsoleStages.StageParse) = p.stages.associateBy { it.name }

    private fun log(vararg parts: Any?): String = parts.flatMap { part ->
        when (part) {
            is String -> listOf(part)
            is List<*> -> part.filterNotNull().map { it.toString() }
            else -> emptyList()
        }
    }.joinToString("\n")

    private fun stage(name: String, vararg body: String): Array<String> =
        (listOf("[Pipeline] stage", "[Pipeline] { ($name)") +
            body.toList() +
            listOf("[Pipeline] }", "[Pipeline] // stage")).toTypedArray()

    @Test
    fun `multibranch main matches recorded parse`() {
        val r = parse(Fixtures.text("08.console-main.txt"))
        assertEquals(recordedRanges("09.parse-main.json"), ranges(r))
        assertEquals(
            listOf("Declarative: Checkout SCM", "Checkout", "Build", "Deploy to staging"),
            r.stages.map { it.name },
        )
        assertTrue(r.stages[0].synthetic)
        assertEquals(0, r.diagnostics.stackLeftAtEnd)
    }

    @Test
    fun `multibranch main error is outside every stage`() {
        // Declarative prints the failure message after End of Pipeline, not inside the stage.
        val d = parse(Fixtures.text("08.console-main.txt")).diagnostics
        assertEquals("FAILURE", d.result)
        assertEquals(72, d.firstErrorLine)
        assertNull(d.firstErrorStage)
        assertEquals(
            ConsoleStages.FailedStage("Deploy to staging", "last-executed-stage"),
            d.inferredFailedStage,
        )
    }

    @Test
    fun `nosv seed matches recorded parse`() {
        val r = parse(Fixtures.text("nosv.console-seed.txt"))
        assertEquals(recordedRanges("nosv.parse.json"), ranges(r))
        assertEquals("SUCCESS", r.diagnostics.result)
        assertNull(r.diagnostics.inferredFailedStage)
    }

    @Test
    fun `freestyle degrades to plain log`() {
        val r = parse(Fixtures.text("08.console-freestyle-fail.txt"))
        val d = r.diagnostics
        assertEquals(0, r.stages.size)
        assertFalse(d.isPipelineLog)
        assertEquals("plain-log", d.fallbackMode)
        assertEquals(8, d.firstErrorLine) // "Build step 'Execute shell' marked build as failure"
    }

    @Test
    fun `parallel lanes are siblings under the parallel stage`() {
        val r = parse(Fixtures.text("10.console-parallel-demo.txt"))
        assertEquals(
            listOf("Setup", "Parallel", "Branch A", "Branch B", "Branch C", "Teardown"),
            r.stages.map { it.name },
        )
        for (s in r.stages) {
            if (s.name.startsWith("Branch ")) {
                assertEquals("Parallel", s.parent) // not chained A -> B -> C
                assertEquals(s.name, s.branch)
                assertEquals("name", s.laneBinding)
                assertFalse(s.parentUncertain)
            }
        }
        assertEquals(emptyList(), r.diagnostics.unattributedStages)
        assertEquals(
            listOf(
                ConsoleStages.ParallelRegion(14, 51, listOf("Branch A", "Branch B", "Branch C")),
            ),
            r.diagnostics.parallelRegions,
        )
    }

    @Test
    fun `stage nested in a lane is not attributed to a sibling lane`() {
        // Lane A and Lane B open before either body runs, so there is no data that
        // binds A Inner to Lane A. Attaching it to Lane B would be a fabrication.
        val r = parse(Fixtures.text("11.console-parallel-nested.txt"))
        val byName = byName(r)
        for (lane in listOf("Lane A", "Lane B")) {
            assertEquals("Lane Parallel", byName.getValue(lane).parent)
            assertEquals(lane, byName.getValue(lane).branch)
        }
        for (inner in listOf("A Inner", "B Inner")) {
            assertEquals("Lane Parallel", byName.getValue(inner).parent)
            assertNull(byName.getValue(inner).branch)
            assertTrue(byName.getValue(inner).parentUncertain)
        }
        assertEquals(listOf("A Inner", "B Inner"), r.diagnostics.unattributedStages)
    }

    @Test
    fun `sequential lane keeps its own nesting`() {
        // A lane whose body is sequential must still nest: only one lane frame is open,
        // so the enclosing brace is unambiguous.
        val text = log(
            OPEN,
            "[Pipeline] node",
            "[Pipeline] {",
            "[Pipeline] stage",
            "[Pipeline] { (Par)",
            "[Pipeline] parallel",
            "[Pipeline] { (Branch: L1)",
            "[Pipeline] stage",
            "[Pipeline] { (L1)",
            "[Pipeline] stage",
            "[Pipeline] { (L1 Inner)",
            "inner",
            "[Pipeline] }",
            "[Pipeline] // stage",
            "[Pipeline] }",
            "[Pipeline] // stage",
            "[Pipeline] }",
            "[Pipeline] // parallel",
            "[Pipeline] }",
            "[Pipeline] // stage",
            "[Pipeline] }",
            "[Pipeline] }",
            "[Pipeline] // node",
            END,
        )
        val byName = byName(parse(text))
        assertEquals("Par", byName.getValue("L1").parent)
        assertEquals("L1", byName.getValue("L1").branch)
        assertEquals("L1", byName.getValue("L1 Inner").parent)
        assertFalse(byName.getValue("L1 Inner").parentUncertain)
    }

    @Test
    fun `real nested stages`() {
        val r = parse(Fixtures.text("12.console-nested-stages-demo.txt"))
        val byName = byName(r)
        assertEquals("Outer", byName.getValue("Inner One").parent)
        assertEquals("Outer", byName.getValue("Inner Two").parent)
        assertNull(byName.getValue("After Outer").parent)
        assertEquals(4, r.stages.size)
    }

    @Test
    fun `real skipped stage and post actions`() {
        val r = parse(Fixtures.text("13.console-skipped-post-demo.txt"))
        val byName = byName(r)
        assertEquals("when conditional", byName.getValue("Skipped").skipped)
        assertTrue(byName.getValue(ConsoleStages.POST_ACTIONS).synthetic)
        assertEquals("FAILURE", r.diagnostics.result)
        assertEquals(
            ConsoleStages.FailedStage("Fails", "last-executed-stage"),
            r.diagnostics.inferredFailedStage,
        )
        assertEquals(35, r.diagnostics.firstErrorLine)
    }

    @Test
    fun `nested stage close does not truncate outer`() {
        val text = log(
            OPEN,
            "[Pipeline] node",
            "[Pipeline] {",
            "[Pipeline] stage",
            "[Pipeline] { (Outer)",
            *stage("Inner", "inner line"),
            "outer tail line",
            "[Pipeline] }",
            "[Pipeline] // stage",
            "[Pipeline] }",
            "[Pipeline] // node",
            END,
        )
        val r = parse(text)
        assertEquals(listOf(Triple("Outer", 5, 12), Triple("Inner", 7, 9)), ranges(r))
        assertEquals("Outer", r.stages[1].parent)
        assertEquals(0, r.diagnostics.stackLeftAtEnd)
        assertEquals(0, r.diagnostics.strayStageClose)
    }

    @Test
    fun `parallel stages are named but marked interleaved`() {
        val text = log(
            OPEN,
            "[Pipeline] stage",
            "[Pipeline] { (Par)",
            "[Pipeline] parallel",
            "[Pipeline] { (Branch: A)",
            "[Pipeline] { (Branch: B)",
            "[Pipeline] stage",
            "[Pipeline] { (A)",
            "[Pipeline] stage",
            "[Pipeline] { (B)",
            "a-out",
            "b-out",
            "[Pipeline] }",
            "[Pipeline] // stage",
            "[Pipeline] }",
            "[Pipeline] }",
            "[Pipeline] // stage",
            "[Pipeline] }",
            "[Pipeline] // parallel",
            "[Pipeline] }",
            "[Pipeline] // stage",
            END,
        )
        val r = parse(text)
        assertEquals(listOf("Par", "A", "B"), r.stages.map { it.name })
        val (par, a, b) = r.stages
        assertEquals(3 to 20, par.firstLine to par.lastLine)
        assertFalse(par.interleaved)
        for (s in listOf(a, b)) {
            assertTrue(s.interleaved)
            assertEquals(4 to 19, s.firstLine to s.lastLine) // the whole parallel region
        }
        assertEquals(
            listOf(ConsoleStages.ParallelRegion(4, 19, listOf("A", "B"))),
            r.diagnostics.parallelRegions,
        )
        assertEquals(0, r.diagnostics.stackLeftAtEnd)
    }

    @Test
    fun `post actions and skipped stages do not mislead inference`() {
        val text = log(
            OPEN,
            *stage("Build", "ok"),
            *stage("Test", "+ ./gradlew test", "tests failing"),
            *stage("Publish", "Stage \"Publish\" skipped due to earlier failure(s)"),
            *stage("Declarative: Post Actions", "cleaning up"),
            END,
            "ERROR: script returned exit code 1",
            "Finished: FAILURE",
        )
        val r = parse(text)
        val byName = byName(r)
        assertEquals("earlier failure(s)", byName.getValue("Publish").skipped)
        assertTrue(byName.getValue(ConsoleStages.POST_ACTIONS).synthetic)
        assertEquals(
            ConsoleStages.FailedStage("Test", "last-executed-stage"),
            r.diagnostics.inferredFailedStage,
        )
    }

    @Test
    fun `error inside stage wins over heuristic`() {
        val text = log(
            OPEN,
            *stage("Compile", "[ERROR] /ws/src/main/java/a/B.java:[12,5] cannot find symbol"),
            *stage("Declarative: Post Actions", "ERROR: post step failed too"),
            END,
            "Finished: FAILURE",
        )
        val d = parse(text).diagnostics
        assertEquals("Compile", d.firstErrorStage)
        assertEquals(
            ConsoleStages.FailedStage("Compile", "first-error-inside-stage"),
            d.inferredFailedStage,
        )
    }

    @Test
    fun `truncated log is still a pipeline`() {
        // The head-only fetch (charter 9.7) produces exactly this: no End marker.
        val text = log(
            OPEN,
            "[Pipeline] node",
            "[Pipeline] {",
            *stage("Build", "x"),
            "[Pipeline] stage",
            "[Pipeline] { (Deploy)",
            "still going",
        )
        val r = parse(text)
        val d = r.diagnostics
        assertTrue(d.isPipelineLog)
        assertFalse(d.endMarkerSeen)
        assertEquals(2, d.stackLeftAtEnd) // node block + open Deploy stage
        assertTrue(r.stages.last().open)
        assertEquals(11, r.stages.last().lastLine)
    }

    @Test
    fun `pipeline without stages`() {
        val text = log(
            OPEN,
            "[Pipeline] node",
            "[Pipeline] {",
            "[Pipeline] sh",
            "+ make",
            "[Pipeline] }",
            "[Pipeline] // node",
            END,
            "Finished: SUCCESS",
        )
        val r = parse(text)
        assertTrue(r.stages.isEmpty())
        assertEquals("pipeline-without-stages", r.diagnostics.fallbackMode)
    }

    @Test
    fun `ansi and crlf do not hide markers`() {
        val text = listOf(
            OPEN,
            "\u001B[0m[Pipeline] stage",
            "[Pipeline] { (Build)\u001B[0m",
            "x",
            "[Pipeline] }",
            "[Pipeline] // stage",
            END,
        ).joinToString("\r\n")
        assertEquals(listOf(Triple("Build", 3, 5)), ranges(parse(text)))
    }

    // ---------------------------------------------------------------- mappers (fallback chain)

    @Test
    fun `wfapi describe maps to stage refs with real statuses and durations`() {
        val refs = ConsoleStages.fromWfapi(parseJsonObject(Fixtures.text("12.wfapi.json")))
        assertEquals(
            listOf("Declarative: Checkout SCM", "Checkout", "Build", "Deploy to staging"),
            refs.map { it.name },
        )
        assertEquals("FAILED", refs.last().status)
        assertEquals("Deploy failed: Discount table is empty for region EU", refs.last().errorMessage)
        assertEquals(509L, refs.last().durationMillis)
        assertNull(refs.last().firstLine)
    }

    @Test
    fun `console fallback maps parsed stages with line ranges and no invented timings`() {
        val refs = ConsoleStages.fromConsole(parse(Fixtures.text("08.console-main.txt")))
        assertEquals(
            listOf("Declarative: Checkout SCM", "Checkout", "Build", "Deploy to staging"),
            refs.map { it.name },
        )
        assertEquals(StageSource.CONSOLE, refs[0].source)
        assertNull(refs[0].durationMillis)
        assertNull(refs[0].status)
        assertTrue(refs[0].firstLine != null && refs[0].lastLine != null)
    }

    @Test
    fun `freestyle console fallback yields the Build pseudo stage`() {
        val refs = ConsoleStages.fromConsole(parse(Fixtures.text("08.console-freestyle-fail.txt")))
        assertEquals(listOf("Build"), refs.map { it.name })
        assertEquals(StageSource.BUILD, refs[0].source)
        assertNull(refs[0].firstLine)
    }

    // ---------------------------------------------------------------- audit regressions

    @Test
    fun `a streamed parse equals the in-memory parse`() {
        val text = Fixtures.text("08.console-main.txt")

        val streamed = ConsoleStages.parse(java.io.StringReader(text))

        assertEquals(ConsoleStages.parse(text), streamed)
    }

    @Test
    fun `a console over the old 8 MB limit still parses into stages`() {
        val noise = "x".repeat(99) + "\n"
        val text = "[Pipeline] { (Build)\n" + noise.repeat(100_000) + "[Pipeline] }\n" // ~10 MB

        val parse = ConsoleStages.parse(java.io.StringReader(text))

        assertEquals(listOf("Build"), parse.stages.map { it.name })
        assertEquals(100_002, parse.stages.single().lastLine)
    }
}

private const val OPEN = "[Pipeline] Start of Pipeline"
private const val END = "[Pipeline] End of Pipeline"
