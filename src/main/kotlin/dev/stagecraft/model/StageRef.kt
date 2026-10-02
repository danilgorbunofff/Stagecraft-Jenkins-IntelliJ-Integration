package dev.stagecraft.model

/**
 * How we learned about a stage. The fallback chain (§9.3) walks these in order and the UI is
 * allowed to be less precise as the source gets weaker - but never less honest.
 */
enum class StageSource {
    /** `wfapi/describe` answered with real statuses and timings. */
    WFAPI,

    /** Console text parsed into stages; line ranges yes, statuses and timings no. */
    CONSOLE,

    /** Pipeline log with no stage blocks - one pseudo stage named "Pipeline". */
    PIPELINE,

    /** Non-pipeline log (e.g. free-style) - one pseudo stage named "Build". */
    BUILD,
}

/**
 * One stage of a build, however it was obtained.
 *
 * Exactly which fields are populated depends on [source]:
 *
 *  * [StageSource.WFAPI] fills [status], [durationMillis], [errorMessage];
 *  * [StageSource.CONSOLE] fills [firstLine], [lastLine], plus nesting metadata;
 *  * the pseudo stages fill only [name].
 *
 * Line numbers are 1-based and inclusive. Timings are never invented: if wfapi was unavailable,
 * [durationMillis] stays null and the UI says "duration unknown" instead of guessing.
 */
data class StageRef(
    val name: String,
    val status: String? = null,
    val durationMillis: Long? = null,
    val errorMessage: String? = null,
    val firstLine: Int? = null,
    val lastLine: Int? = null,
    val synthetic: Boolean = false,
    val interleaved: Boolean = false,
    val skippedReason: String? = null,
    val source: StageSource,
) {
    val isPseudo: Boolean get() = source == StageSource.PIPELINE || source == StageSource.BUILD
}
