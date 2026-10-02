package dev.stagecraft.model

/** What a job is, derived from the `_class` Jenkins reports for it. */
enum class JobKind {
    FOLDER,
    ORGANIZATION_FOLDER,
    MULTIBRANCH,
    WORKFLOW,
    FREESTYLE,
    MATRIX,
    OTHER,
    ;

    /** A job that contains other jobs and is not itself buildable. */
    val isContainer: Boolean
        get() = this == FOLDER || this == ORGANIZATION_FOLDER || this == MULTIBRANCH

    companion object {
        fun fromClass(className: String?): JobKind {
            if (className.isNullOrBlank()) return OTHER
            return when {
                className.endsWith(".Folder") -> FOLDER
                className.contains("OrganizationFolder") -> ORGANIZATION_FOLDER
                className.contains("MultiBranchProject") -> MULTIBRANCH
                className.contains("WorkflowJob") -> WORKFLOW
                className.contains("FreeStyleProject") -> FREESTYLE
                className.contains("MatrixProject") -> MATRIX
                else -> OTHER
            }
        }
    }
}

/**
 * A node in the job tree.
 *
 * Two identities, deliberately kept apart:
 *
 *  * [rawPath] is the canonical one - the `name` values exactly as Jenkins reported them, root
 *    first. Jenkins reports a folder's child name already percent-encoded once
 *    (`feature%2FORD-214` for the branch `feature/ORD-214`), so `rawPath` is what you feed back
 *    into a URL and what you key a cache on. It is also the only lossless identity: a flattened
 *    display path cannot be split back into segments, because a branch name contains `/`.
 *  * [fullName] is for humans - the percent-decoded names joined with `/`.
 *
 * [url] is built locally from the configured base rather than read out of the response, because
 * §9.4 forbids requesting `url` in the discovery tree (it multiplies the payload) and because
 * Jenkins' own URLs point at its configured root, not at the address we actually reached it on.
 */
data class JobNode(
    val name: String,
    val displayName: String,
    val fullName: String,
    val rawPath: List<String>,
    val className: String?,
    val kind: JobKind,
    val url: String,
    val depth: Int,
    val colour: String? = null,
) {
    val rawPathString: String get() = rawPath.joinToString("/")
    val isContainer: Boolean get() = kind.isContainer
    val status: BuildStatus get() = BuildStatus.fromColour(colour)
}
