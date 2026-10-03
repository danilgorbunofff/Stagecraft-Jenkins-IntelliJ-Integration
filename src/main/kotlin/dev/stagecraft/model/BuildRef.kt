package dev.stagecraft.model

/**
 * One build of one job.
 *
 * [url] is always an absolute URL built locally from the configured server base, the job's raw path
 * and the build number - never the value Jenkins returned. Jenkins builds its URLs from its own idea
 * of the root URL, which need not be the address the IDE reaches it on, nor carry the same context
 * path.
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
