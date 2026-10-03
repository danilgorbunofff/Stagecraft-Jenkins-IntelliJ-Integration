package dev.stagecraft.jenkins

import dev.stagecraft.Fixtures
import dev.stagecraft.model.StageSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StageViewTest {

    @Test
    fun `wfapi stages carry status and timings, and the failed stage is a fact`() {
        val describe = parseJsonObject(Fixtures.text("12.wfapi.json"))

        val view = StageView.fromWfapi(describe)

        assertNotNull(view)
        assertEquals(4, view.stages.size)
        assertEquals("Deploy to staging", view.failedStage)
        assertFalse(view.failedStageInferred)
        assertEquals(StageSource.WFAPI, view.source)
        assertEquals(509L, view.stages.first { it.name == "Deploy to staging" }.durationMillis)
        assertNull(view.note)
    }

    @Test
    fun `an empty describe names no stages`() {
        assertNull(StageView.fromWfapi(parseJsonObject("""{"stages":[]}""")))
    }

    @Test
    fun `a console parse infers the failed stage and says it is inferred`() {
        val parse = ConsoleStages.parse(Fixtures.text("08.console-main.txt"))

        val view = StageView.fromConsole(parse)

        assertEquals("Deploy to staging", view.failedStage)
        assertTrue(view.failedStageInferred)
        assertEquals(StageSource.CONSOLE, view.source)
        // Console stages have no timings; the note says so rather than inventing them.
        assertTrue(view.note?.contains("timings") == true, view.note)
    }

    @Test
    fun `a parallel console parse warns that output is not separable`() {
        val parse = ConsoleStages.parse(Fixtures.text("10.console-parallel-demo.txt"))

        val view = StageView.fromConsole(parse)

        assertTrue(view.parallelInterleaved || view.note?.contains("parallel") == true, view.note)
    }

    @Test
    fun `a freestyle log is a build pseudo-stage, not an error`() {
        val parse = ConsoleStages.parse("just a plain log\nno pipeline markers\n")

        val view = StageView.fromConsole(parse)

        assertEquals(1, view.stages.size)
        assertEquals(StageSource.BUILD, view.source)
        assertTrue(view.stages.single().isPseudo)
    }
}
