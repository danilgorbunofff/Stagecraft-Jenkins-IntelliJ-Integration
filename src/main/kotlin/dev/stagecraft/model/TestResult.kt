package dev.stagecraft.model

/**
 * One test case from `{build}/testReport/api/json` (§9.6).
 *
 * [enclosingBlockNames] is Jenkins' own mapping of the test to the stage that ran it (Day-0 fixture
 * `11.testreport.json`: `["Deploy to staging"]`). It is used for the per-stage `[tests: …]` badge and
 * there is deliberately nothing matched client-side - Jenkins already knows.
 */
data class TestCase(
    val name: String,
    val className: String,
    val status: String,
    val durationMillis: Long?,
    val errorDetails: String?,
    val errorStackTrace: String?,
    val enclosingBlockNames: List<String>,
) {
    val failed: Boolean
        get() = status.equals("FAILED", ignoreCase = true) || status.equals("REGRESSION", ignoreCase = true)

    val skipped: Boolean get() = status.equals("SKIPPED", ignoreCase = true)

    val fullName: String get() = if (className.isBlank()) name else "$className.$name"
}

/** A build's test report: the counts Jenkins computed, plus every case, for the tests panel. */
data class TestReport(
    val totalCount: Int,
    val failCount: Int,
    val skipCount: Int,
    val passCount: Int,
    val durationMillis: Long?,
    val cases: List<TestCase>,
) {
    val failedCases: List<TestCase> get() = cases.filter { it.failed }

    /** The one-line summary the panel shows, e.g. `412 passed · 3 failed · 1 skipped`. */
    val summary: String
        get() = listOf(
            "$passCount passed",
            "$failCount failed",
            "$skipCount skipped",
        ).joinToString("  ·  ")
}
