package dev.stagecraft.jenkins

import dev.stagecraft.model.JobNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A git remote parsed into the parts jobs are named after.
 *
 * Jenkins records remotes in every shape git does — `git@host:org/repo`, `ssh://`, `https://` —
 * and Jenkins jobs are named after the repository, not the URL. §9.4 step 3 (b): normalise before
 * comparing (lower-case host, no `.git`, no trailing `/`, no scheme or port), so all spellings of
 * one repository are equal.
 *
 * `org` and `repo` keep their original case because job names do; [matches] compares them
 * case-insensitively anyway.
 */
data class RemoteInfo(
    val raw: String,
    val host: String,
    val org: String,
    val repo: String,
    val subpath: String? = null,
) {

    /** Canonical identity, used for cache keys and pins: `host/subpath/org/repo`. */
    val key: String
        get() = host + "/" + listOfNotNull(
            subpath?.takeIf { it.isNotEmpty() },
            org.takeIf { it.isNotEmpty() },
            repo,
        ).joinToString("/")

    /** Is [other] another spelling of the same repository? */
    fun matches(other: RemoteInfo): Boolean = key.equals(other.key, ignoreCase = true)

    companion object {

        fun parse(raw: String): RemoteInfo? {
            val trimmed = raw.trim().substringBefore('#').substringBefore('?')
            if (trimmed.isEmpty()) return null

            val host: String
            val path: String
            val scp = SCP.matchEntire(trimmed)
            if (scp != null) {
                host = scp.groupValues[1]
                path = scp.groupValues[2]
            } else {
                val url = URL_FORM.matchEntire(trimmed) ?: return null
                host = url.groupValues[1]
                path = url.groupValues[2]
            }
            if (host.isBlank()) return null

            var value = path.trim().trimStart('/').trimEnd('/')
            if (value.substringAfterLast('/').endsWith(".git")) value = value.removeSuffix(".git")
            val segments = value.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return null

            return when (segments.size) {
                1 -> RemoteInfo(raw, host.lowercase(), "", segments[0])
                2 -> RemoteInfo(raw, host.lowercase(), segments[0], segments[1])
                else -> RemoteInfo(
                    raw,
                    host.lowercase(),
                    segments[segments.size - 2],
                    segments.last(),
                    subpath = segments.take(segments.size - 2).joinToString("/"),
                )
            }
        }

        /** `git@github.com:org/repo.git` — the scp syntax git documents first. */
        private val SCP = Regex("^(?:[^@/]+)@([^:/]+):(.+)$")

        /** `[scheme://][user@]host[:port]/path` — http, https, ssh, git. `file://` has no host. */
        private val URL_FORM =
            Regex("^(?:[A-Za-z][A-Za-z0-9+.-]*://)?(?:(?:[^/@]+)@)?([^/:?#]+)(?::\\d+)?/(.+)$")
    }
}

/**
 * The branch a pull request was built from, or null when this resolver cannot tell.
 *
 * §9.4 step 3 (a): branches filed as PRs have no branch job, and which source branch a PR came
 * from is knowledge the plugin layer has (Jenkins records it in metadata actions, GitHub knows it
 * directly). The matcher keeps the seam pluggable and reports null honestly instead of guessing.
 */
interface PrSourceResolver {
    fun sourceBranch(remote: RemoteInfo, prNumber: Int): String?
}

/**
 * §9.4: a git remote names a job, ranked and honest.
 *
 * The matcher never pretends. A `contains` tier is reported as a guess ([MatchResult.Matched]
 * with [MatchResult.Matched.certain] false), a PR with no source branch is unresolved, and an
 * empty index or a truncated index that hides the job says exactly that.
 */
class RemoteMatcher(
    private val index: JobIndex,
    private val prResolver: PrSourceResolver? = null,
) {

    sealed interface MatchResult {
        val explanation: String

        /** A job the matcher is willing to name, and how strong the evidence is. */
        data class Matched(
            val job: JobNode,
            val how: String,
            val certain: Boolean,
            val branch: String? = null,
            val branchJob: JobNode? = null,
        ) : MatchResult {
            override val explanation: String get() = how
        }

        data class Unresolved(val reason: String) : MatchResult {
            override val explanation: String get() = reason
        }
    }

    fun match(remoteUrl: String, branch: String? = null, prNumber: Int? = null): MatchResult {
        val remote = RemoteInfo.parse(remoteUrl)
            ?: return MatchResult.Unresolved("\"$remoteUrl\" is not a git remote Stagecraft can name a job from")

        index.pinFor(remote.key)?.let { pinned ->
            return MatchResult.Matched(pinned, "pinned by you in Stagecraft's settings", certain = true)
        }

        val ranked = index.rank(remote)
        if (ranked.isEmpty()) {
            val missing = if (index.truncated) {
                " The index holds ${index.jobs.size} of ${index.totalSeen} jobs; pin your job manually for an exact match."
            } else {
                ""
            }
            return MatchResult.Unresolved("no indexed job matches the repository \"${remote.repo}\".$missing")
        }

        if (prNumber != null) return resolvePr(remote, ranked, prNumber)
        if (branch != null) return resolveBranch(ranked, branch, prLabel = null)

        val best = ranked.first()
        return MatchResult.Matched(best.job, best.reason, certain = best.tier >= EXACT_NAME_TIER)
    }

    private fun resolvePr(remote: RemoteInfo, ranked: List<JobIndex.RankedJob>, prNumber: Int): MatchResult {
        val label = "PR-$prNumber"
        // Trap (a): a branch filed as a pull request has no branch job — the PR job itself is
        // what Jenkins builds, and its children carry the runs.
        for (candidate in ranked.filter { it.job.isContainer }) {
            val prJob = index.childNamed(candidate.job, label)
            if (prJob != null) {
                return MatchResult.Matched(
                    prJob,
                    "$label job of \"${candidate.job.displayName}\"",
                    certain = candidate.tier >= EXACT_NAME_TIER,
                )
            }
        }
        val source = prResolver?.sourceBranch(remote, prNumber)
        if (source != null) return resolveBranch(ranked, source, prLabel = label)
        return MatchResult.Unresolved(
            "$label: no PR job in the index, and no configured PR source could name the branch it came from. Pin the job manually.",
        )
    }

    private fun resolveBranch(ranked: List<JobIndex.RankedJob>, branch: String, prLabel: String?): MatchResult {
        for (candidate in ranked.filter { it.job.isContainer }) {
            val branchJob = index.childNamed(candidate.job, branch)
            if (branchJob != null) {
                val lead = prLabel?.let { "$it built from branch \"$branch\"" } ?: "branch \"$branch\""
                return MatchResult.Matched(
                    branchJob,
                    "$lead — child of \"${candidate.job.displayName}\"",
                    certain = candidate.tier >= EXACT_NAME_TIER,
                    branch = branch,
                    branchJob = branchJob,
                )
            }
        }
        val best = ranked.first()
        val lead = prLabel?.let { "$it could not be resolved to an indexed branch job" }
            ?: "branch \"$branch\" has no indexed job"
        return MatchResult.Matched(
            best.job,
            "$lead; closest match by name is \"${best.job.displayName}\" (${best.reason})",
            certain = false,
            branch = branch,
        )
    }

    companion object {
        /** Tiers at or above this are exact-name evidence; below it the matcher is guessing. */
        const val EXACT_NAME_TIER = 80
    }
}

/**
 * What one build's `actions` said (§9.4 step 3): whether the build we are about to show really
 * belongs to the repository we matched, and which branch it recorded. Either half can be unknown —
 * a build with no BuildData action, or a caller that does not care about branches.
 */
data class BuildConfirmation(
    val remoteMatches: Boolean,
    val branchMatches: Boolean?,
    val foundRemoteUrls: List<String>,
    val foundBranches: List<String>,
)

/**
 * Confirm a matched job against the one build we are about to show (§9.4 step 3).
 *
 * Traps: `remoteUrls` is a list and there can be several BuildData actions, so remotes are
 * compared after [RemoteInfo] normalisation; `lastBuiltRevision.branch` may be absent entirely,
 * which is reported as null rather than false. A 404 from Jenkins is a permissions question, not
 * a certainty, and is thrown as [JenkinsException.NotFound] untouched.
 */
fun confirmWithBuild(
    client: JenkinsClient,
    buildUrl: String,
    remote: RemoteInfo,
    expectedBranch: String? = null,
): BuildConfirmation {
    val actions = client.buildActions(buildUrl)
    val urls = actions.flatMap { it.stringList("remoteUrls") }
    val found = urls.mapNotNull { RemoteInfo.parse(it) }
    val branches = actions.flatMap { action ->
        action.obj("lastBuiltRevision")
            ?.arr("branch")
            ?.mapNotNull { entry -> (entry as? JsonObject)?.str("name") }
            ?: emptyList()
    }
    return BuildConfirmation(
        remoteMatches = found.any { it.matches(remote) },
        branchMatches = expectedBranch?.let { expected -> branches.any { recorded -> sameBranch(recorded, expected) } },
        foundRemoteUrls = urls,
        foundBranches = branches,
    )
}

private fun sameBranch(recorded: String, expected: String): Boolean =
    recorded.removePrefix("refs/heads/").trim().equals(expected.removePrefix("refs/heads/").trim(), ignoreCase = true)

private fun JsonObject.stringList(key: String): List<String> =
    arr(key)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
