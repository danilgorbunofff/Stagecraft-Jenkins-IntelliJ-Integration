package dev.stagecraft.model

/**
 * One build of one job.
 *
 * [url] is always an absolute URL rebased onto the configured server base, never the value Jenkins
 * returned verbatim: Jenkins builds URLs from its own configured root URL, so a server reached over
 * HTTPS through a proxy still answers with `http://localhost:18080/...` (Day-0 re-check R4).
 */
data class BuildRef(
    val jobFullName: String,
    val jobRawPath: List<String>,
    val number: Int,
    val url: String,
    val status: BuildStatus,
    val timestampMillis: Long,
    val durationMillis: Long,
) {
    /** `#12` - what the build is called in the Jenkins UI. */
    val displayName: String get() = "#$number"

    val isRunning: Boolean get() = status == BuildStatus.RUNNING
}
