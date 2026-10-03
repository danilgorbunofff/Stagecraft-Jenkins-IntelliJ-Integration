package dev.stagecraft.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class CompatProbeTest {

    @Test
    fun `the job path skips a context path`() {
        assertEquals(listOf("a", "main"), rawPathFromBuildUrl("https://ci.example.com/jenkins/job/a/job/main/1/"))
    }

    @Test
    fun `a branch name in a url is decoded once, back to the name jenkins reports`() {
        assertEquals(
            listOf("multibranch-demo", "feature%2FORD-214"),
            rawPathFromBuildUrl("http://localhost:18080/job/multibranch-demo/job/feature%252FORD-214/1/"),
        )
    }

    @Test
    fun `a url with no job segment has no job path`() {
        assertEquals(emptyList(), rawPathFromBuildUrl("http://localhost:18080/view/all/"))
    }
}
