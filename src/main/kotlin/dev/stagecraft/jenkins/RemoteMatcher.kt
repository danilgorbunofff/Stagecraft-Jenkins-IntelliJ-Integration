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

    /** The repository's path on its host, `subpath/org/repo`, without the host. */
    val pathKey: String get() = key.substringAfter('/')

    /**
     * Same repository path, host aside. Used where the two sides legitimately name the host
     * differently - the developer's SSH alias (`git@github-work:org/repo`) against the URL the
     * Jenkins agent cloned - and a false "this is not your repository" would be worse than the
     * vanishing chance of two hosts holding the same `org/repo`.
     */
    fun samePath(other: RemoteInfo): Boolean = pathKey.equals(other.pathKey, ignoreCase = true)

    companion object {

        fun parse(raw: String): RemoteInfo? {
            val trimmed = raw.trim().substringBefore('#').substringBefore('?')
            if (trimmed.isEmpty()) return null

            var host: String
            val path: String
            val scp = if (trimmed.contains("://")) null else SCP.matchEntire(trimmed)
            if (scp != null) {
                host = scp.groupValues[1]
                path = scp.groupValues[2]
            } else {
                val url = URL_FORM.matchEntire(trimmed) ?: return null
                host = url.groupValues[1]
                path = url.groupValues[2]
            }
            if (host.isBlank()) return null
            host = host.lowercase()

            var value = path.trim().trimStart('/').trimEnd('/')
            if (value.substringAfterLast('/').endsWith(".git")) value = value.removeSuffix(".git")
            val segments = canonicalSegments(host, value.split('/').filter { it.isNotEmpty() }).toMutableList()
            if (segments.isEmpty()) return null
            host = canonicalHost(host)

            return when (segments.size) {
                1 -> RemoteInfo(raw, host, "", segments[0])
                2 -> RemoteInfo(raw, host, segments[0], segments[1])
                else -> RemoteInfo(
                    raw,
                    host,
                    segments[segments.size - 2],
                    segments.last(),
                    subpath = segments.take(segments.size - 2).joinToString("/"),
                )
            }
        }

        /**
         * Hosting-specific spellings of one repository, folded together so the HTTPS and SSH remotes
         * of the same repository compare equal:
         *
         *  * Bitbucket Server / Data Center clones over HTTPS from `/scm/PROJ/repo` and over SSH from
         *    `/PROJ/repo` - the `scm` prefix is dropped;
         *  * Azure DevOps clones over HTTPS from `dev.azure.com/org/project/_git/repo` and over SSH
         *    from `ssh.dev.azure.com:v3/org/project/repo` - the `_git` marker and the `v3` prefix are
         *    dropped, and the SSH host is the HTTPS host.
         */
        private fun canonicalSegments(host: String, segments: List<String>): List<String> {
            var result = segments
            if (result.size >= 3 && result.first().equals("scm", ignoreCase = true)) result = result.drop(1)
            if (host == AZURE_SSH_HOST && result.firstOrNull() == "v3") result = result.drop(1)
            if (host == AZURE_HOST || host == AZURE_SSH_HOST) result = result.filter { it != "_git" }
            return result
        }

        private fun canonicalHost(host: String): String = if (host == AZURE_SSH_HOST) AZURE_HOST else host

        private const val AZURE_HOST = "dev.azure.com"
        private const val AZURE_SSH_HOST = "ssh.dev.azure.com"

        /**
         * `git@github.com:org/repo.git` — the scp syntax git documents first. The user is optional
         * (`github.com:org/repo.git` with the user in `~/.ssh/config`); a one-letter "host" is a
         * Windows drive (`C:\repos\x`), which git reads as a local path, so it is not a remote.
         */
        private val SCP = Regex("^(?:[^@/]+@)?([^:/\\\\]{2,}):(.+)$")

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
            /** The user chose this job in settings; nothing about it is inferred. */
            val pinned: Boolean = false,
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

        index.pinFor(remote.key)?.let { pinned -> return pinnedMatch(pinned, branch) }

        val ranked = index.rank(remote)
        if (ranked.isEmpty()) {
            val missing = index.truncationNote?.let { " $it" }.orEmpty()
            return MatchResult.Unresolved("no indexed job matches the repository \"${remote.repo}\".$missing")
        }

        if (prNumber != null) return resolvePr(remote, ranked, prNumber)
        if (branch != null) return resolveBranch(ranked, branch, prLabel = null)

        val best = ranked.first()
        val rival = ranked.drop(1).firstOrNull { it.tiesWith(best) }
        return MatchResult.Matched(
            best.job,
            best.reason + ambiguityNote(rival),
            certain = best.tier >= EXACT_NAME_TIER && rival == null,
        )
    }

    /**
     * Said whenever a second job carries exactly the same name evidence as the one we picked: the
     * pick is then a coin toss, and showing it as certain would put somebody else's builds on screen
     * with this plugin's confidence (two organisation folders that both hold an `api` repository).
     */
    private fun ambiguityNote(rival: JobIndex.RankedJob?): String =
        if (rival == null) "" else "; \"${rival.job.fullName}\" matches just as well, so pin the right one in settings"

    /**
     * §9.4 step 5: a pin is a user decision, so it is answered before any tier is consulted.
     *
     * A pinned *container* still needs its branch child, exactly like a ranked candidate does: the
     * builds of `svc` are not the builds of `svc/main`. When the index holds that child we use it;
     * when it does not — the pin names a job the index never saw, or the tree was truncated — the
     * child path is built from the pin itself. Falling back to the closest name match instead, as
     * this used to, showed the container's own builds under the user's pinned job.
     *
     * A pin that is itself a buildable job the index holds is used as it stands; the branch is
     * context, not something to descend into.
     */
    private fun pinnedMatch(pinned: JobNode, branch: String?): MatchResult {
        if (branch == null) return MatchResult.Matched(pinned, PIN_HOW, certain = true, pinned = true)

        val how = "branch \"$branch\" of the job pinned in Stagecraft's settings (\"${pinned.displayName}\")"

        index.childNamed(pinned, branch)?.let { child ->
            return MatchResult.Matched(child, how, certain = true, branch = branch, branchJob = child, pinned = true)
        }
        if (!pinned.isContainer && index.jobs.any { it.rawPath == pinned.rawPath }) {
            return MatchResult.Matched(pinned, PIN_HOW, certain = true, branch = branch, pinned = true)
        }

        // A path the user pinned is a path we can build: `.../job/<pinned>/job/<branch>`. It is
        // still certain, because a wrong path fails loudly with a 404 rather than quietly showing
        // somebody else's builds.
        val child = jobFromRawPath(pinned.rawPath + JenkinsUrls.encodeItemName(branch), index.serverUrl)
        return MatchResult.Matched(child, how, certain = true, branch = branch, branchJob = child, pinned = true)
    }

    private fun resolvePr(remote: RemoteInfo, ranked: List<JobIndex.RankedJob>, prNumber: Int): MatchResult {
        val label = "PR-$prNumber"
        // Trap (a): a branch filed as a pull request has no branch job — the PR job itself is
        // what Jenkins builds, and its children carry the runs.
        val hits = childHits(ranked, label)
        if (hits.isNotEmpty()) {
            val (candidate, prJob) = hits.first()
            val rival = hits.drop(1).firstOrNull { it.first.tiesWith(candidate) }?.first
            return MatchResult.Matched(
                prJob,
                "$label job of \"${candidate.job.displayName}\"" + ambiguityNote(rival),
                certain = candidate.tier >= EXACT_NAME_TIER && rival == null,
            )
        }
        val source = prResolver?.sourceBranch(remote, prNumber)
        if (source != null) return resolveBranch(ranked, source, prLabel = label)
        return MatchResult.Unresolved(
            "$label: no PR job in the index, and no configured PR source could name the branch it came from. Pin the job manually.",
        )
    }

    private fun resolveBranch(ranked: List<JobIndex.RankedJob>, branch: String, prLabel: String?): MatchResult {
        val hits = childHits(ranked, branch)
        if (hits.isNotEmpty()) {
            val (candidate, branchJob) = hits.first()
            val rival = hits.drop(1).firstOrNull { it.first.tiesWith(candidate) }?.first
            val lead = prLabel?.let { "$it built from branch \"$branch\"" } ?: "branch \"$branch\""
            return MatchResult.Matched(
                branchJob,
                "$lead — child of \"${candidate.job.displayName}\"" + ambiguityNote(rival),
                certain = candidate.tier >= EXACT_NAME_TIER && rival == null,
                branch = branch,
                branchJob = branchJob,
            )
        }

        val best = ranked.first()
        val lead = prLabel?.let { "$it could not be resolved to an indexed branch job" }
            ?: "branch \"$branch\" has no indexed job"
        if (best.job.isContainer) {
            // A container has no builds of its own: listing them would show an empty "has no
            // builds yet" for a branch that may well have failed builds under a job the index
            // has not seen. Say what is actually missing instead.
            val note = index.truncationNote?.let { " $it" }.orEmpty()
            return MatchResult.Unresolved(
                "$lead under \"${best.job.fullName}\". Jenkins creates a branch job when it scans the " +
                    "repository after the branch is pushed; until then there is nothing to show. If the " +
                    "branch has builds, pin its job in settings.$note",
            )
        }
        // A single plain job named like the repository (one pipeline for every branch) can still be
        // the right place to look - but only as a guess.
        return MatchResult.Matched(
            best.job,
            "$lead; closest match by name is \"${best.job.displayName}\" (${best.reason})",
            certain = false,
            branch = branch,
        )
    }

    /** Container candidates, best first, that hold a child named [childName], with that child. */
    private fun childHits(ranked: List<JobIndex.RankedJob>, childName: String): List<Pair<JobIndex.RankedJob, JobNode>> =
        ranked.filter { it.job.isContainer }
            .mapNotNull { candidate -> index.childNamed(candidate.job, childName)?.let { candidate to it } }

    companion object {
        /** Tiers at or above this are exact-name evidence; below it the matcher is guessing. */
        const val EXACT_NAME_TIER = 80

        /** How a pin is explained, in one place so every pin path says the same thing. */
        private const val PIN_HOW = "pinned by you in Stagecraft's settings"
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
 * compared after [RemoteInfo] normalisation, by repository path (see [RemoteInfo.samePath]); `lastBuiltRevision.branch` may be absent entirely,
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
        remoteMatches = found.any { it.samePath(remote) },
        branchMatches = expectedBranch?.let { expected -> branches.any { recorded -> sameBranch(recorded, expected) } },
        foundRemoteUrls = urls,
        foundBranches = branches,
    )
}

private fun sameBranch(recorded: String, expected: String): Boolean =
    recorded.removePrefix("refs/heads/").trim().equals(expected.removePrefix("refs/heads/").trim(), ignoreCase = true)

private fun JsonObject.stringList(key: String): List<String> =
    arr(key)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
