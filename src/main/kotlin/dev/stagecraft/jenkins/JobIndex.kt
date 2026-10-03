package dev.stagecraft.jenkins

import dev.stagecraft.model.JobKind
import dev.stagecraft.model.JobNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * A frozen snapshot of the job tree, kept on disk so the panel can open on a full index while the
 * refresh runs in the background (§9.4 step 4: "the cache is the product").
 *
 * The snapshot carries three honest flags instead of pretending completeness: [truncated] when the
 * [DEFAULT_MAX_JOBS] cap cut the tree down, [totalSeen] with how many there really were, and
 * [fetchedAtMillis] so the UI can say how stale it is.
 *
 * Pins are part of the snapshot and the one thing a refresh may never drop (§9.4 step 5: manual
 * pinning always wins). [fetch] therefore accepts the previous index and carries its pins over.
 */
class JobIndex private constructor(
    val jobs: List<JobNode>,
    val totalSeen: Int,
    val truncated: Boolean,
    val fetchedAtMillis: Long,
    val serverUrl: String,
    private val pins: Map<String, JobNode>,
) {

    /**
     * One candidate. [underOrg] is true when one of the job's ancestors is named like the remote's
     * organisation - the shape an organisation folder gives every repository it scans - and it is
     * what tells `alpha-org/api` from `zeta-org/api` when both are named exactly like the repository.
     */
    data class RankedJob(val job: JobNode, val tier: Int, val reason: String, val underOrg: Boolean = false) {

        /** Two candidates the name evidence cannot tell apart. */
        fun tiesWith(other: RankedJob): Boolean = tier == other.tier && underOrg == other.underOrg
    }

    /**
     * Every job that could plausibly be [remote], best first (§9.4 step 2).
     *
     * Tiers grade the evidence, and the matcher passes the grade on to the user:
     *
     *  * 100 — a multibranch job named exactly like the repository (the Jenkins convention).
     *  * 80  — any job named exactly like the repository.
     *  * 60  — the job name contains the repository name.
     *  * 50  — the job name contains the organisation name (a guess, shown as one).
     *
     * Inside a tier, a job filed under a folder named like the organisation comes first; then ties
     * break by shallower [JobNode.depth], then name.
     */
    fun rank(remote: RemoteInfo): List<RankedJob> {
        val candidates = ArrayList<RankedJob>()
        for (job in jobs) {
            val name = job.displayName
            val tier = when {
                job.kind == JobKind.MULTIBRANCH && name.equals(remote.repo, ignoreCase = true) -> TIER_MULTIBRANCH_NAME
                name.equals(remote.repo, ignoreCase = true) -> TIER_NAME
                name.contains(remote.repo, ignoreCase = true) -> TIER_CONTAINS_REPO
                remote.org.isNotEmpty() && remote.org != remote.repo && name.contains(remote.org, ignoreCase = true) ->
                    TIER_CONTAINS_ORG
                else -> 0
            }
            if (tier > 0) candidates += RankedJob(job, tier, reasonFor(tier, remote, job), isUnderOrg(job, remote))
        }
        candidates.sortWith(
            compareByDescending<RankedJob> { it.tier }
                .thenByDescending { it.underOrg }
                .thenBy { it.job.depth }
                .thenBy { it.job.displayName.lowercase() },
        )
        return candidates
    }

    private fun isUnderOrg(job: JobNode, remote: RemoteInfo): Boolean {
        if (remote.org.isEmpty()) return false
        return job.rawPath.dropLast(1).any { JenkinsUrls.decodeSegment(it).equals(remote.org, ignoreCase = true) }
    }

    /**
     * What to tell the user when the index is not the whole tree, or null when it is. Either the
     * [DEFAULT_MAX_JOBS] cap cut it, or the folder tree went deeper than the walk was allowed to
     * follow.
     */
    val truncationNote: String?
        get() = when {
            !truncated -> null
            totalSeen > jobs.size ->
                "The index holds ${jobs.size} of $totalSeen jobs; pin your job manually for an exact match."
            else ->
                "The index holds ${jobs.size} jobs, but the folder tree goes deeper than Stagecraft followed; " +
                    "pin your job manually for an exact match."
        }

    private fun reasonFor(tier: Int, remote: RemoteInfo, job: JobNode): String = when (tier) {
        TIER_MULTIBRANCH_NAME -> "the multibranch job \"${job.displayName}\" is named like the repository"
        TIER_NAME -> "a job is named exactly like the repository"
        TIER_CONTAINS_REPO -> "the job name contains \"${remote.repo}\""
        else -> "the job name contains \"${remote.org}\""
    }

    /** The immediate child of [parent] whose decoded name matches, or null. */
    fun childNamed(parent: JobNode, name: String): JobNode? =
        jobs.firstOrNull {
            it.rawPath.size == parent.rawPath.size + 1 &&
                it.rawPath.subList(0, parent.rawPath.size) == parent.rawPath &&
                it.displayName.equals(name, ignoreCase = true)
        }

    /**
     * The job the user pinned for [key], or null. A pin is a user decision: it survives refreshes
     * and outranks every name tier in the matcher.
     */
    fun pinFor(key: String): JobNode? = pins[key.lowercase()]

    val pinnedKeys: Set<String> get() = pins.keys

    fun withPin(key: String, job: JobNode): JobIndex =
        JobIndex(jobs, totalSeen, truncated, fetchedAtMillis, serverUrl, pins + (key.lowercase() to job))

    fun withoutPin(key: String): JobIndex =
        JobIndex(jobs, totalSeen, truncated, fetchedAtMillis, serverUrl, pins - key.lowercase())

    companion object {

        /** §9.4 step 4: cap the payload and say so honestly rather than hide the rest. */
        const val DEFAULT_MAX_JOBS = 2000

        const val TIER_MULTIBRANCH_NAME = 100
        const val TIER_NAME = 80
        const val TIER_CONTAINS_REPO = 60
        const val TIER_CONTAINS_ORG = 50

        /** For tests and tooling: an index over jobs we already hold. */
        fun of(
            jobs: List<JobNode>,
            truncated: Boolean = false,
            totalSeen: Int = jobs.size,
            serverUrl: String = "",
            fetchedAtMillis: Long = 0,
            pins: Map<String, JobNode> = emptyMap(),
        ): JobIndex = JobIndex(jobs, totalSeen, truncated, fetchedAtMillis, serverUrl, pins)

        fun fetch(
            client: JenkinsClient,
            maxJobs: Int = DEFAULT_MAX_JOBS,
            nowMillis: Long = System.currentTimeMillis(),
            previous: JobIndex? = null,
        ): JobIndex {
            val tree = client.jobTreeDeep(maxJobs)
            val all = tree.jobs
            val truncated = all.size > maxJobs || !tree.complete
            return JobIndex(
                jobs = if (all.size > maxJobs) all.take(maxJobs) else all,
                totalSeen = all.size,
                truncated = truncated,
                fetchedAtMillis = nowMillis,
                serverUrl = client.serverBaseUrl.trimEnd('/'),
                pins = previous?.pins ?: emptyMap(),
            )
        }

        /** Load the cached index for [client]'s server, or fetch a fresh one and cache it. */
        fun loadOrFetch(
            client: JenkinsClient,
            cacheDir: File,
            maxJobs: Int = DEFAULT_MAX_JOBS,
            nowMillis: Long = System.currentTimeMillis(),
            previous: JobIndex? = null,
        ): JobIndex {
            load(cacheDir, client.serverBaseUrl)?.let { return it }
            val fresh = fetch(client, maxJobs, nowMillis, previous)
            save(cacheDir, fresh)
            return fresh
        }

        /** `{cacheDir}/stagecraft/index-{sha1(serverUrl)}.json` — per server, per §9.4 step 4. */
        fun cacheFile(cacheDir: File, serverUrl: String): File =
            File(cacheDir, "stagecraft/index-${sha1Hex(serverUrl.trimEnd('/'))}.json")

        /**
         * Write through a temporary file and an atomic rename, so a reader - or a crash halfway
         * through - only ever sees the old index or the new one, never half of one.
         */
        fun save(cacheDir: File, index: JobIndex) {
            val file = cacheFile(cacheDir, index.serverUrl)
            val parent = file.parentFile
            if (!parent.isDirectory) parent.mkdirs()
            val tmp = File.createTempFile(file.name, ".tmp", parent)
            try {
                tmp.writeText(index.toJson().toString())
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                tmp.delete()
            }
        }

        /** Null on absence, corruption, unknown format or a different server — a miss, not a crash. */
        fun load(cacheDir: File, serverUrl: String): JobIndex? {
            val file = cacheFile(cacheDir, serverUrl)
            if (!file.isFile) return null
            val root = try {
                Json.parseToJsonElement(file.readText())
            } catch (_: Exception) {
                return null
            } as? JsonObject ?: return null
            if (root.int("formatVersion") != 1) return null
            val storedServer = root.str("serverUrl") ?: return null
            if (!storedServer.equals(serverUrl.trimEnd('/'), ignoreCase = true)) return null
            val jobs = root.arr("jobs")?.mapNotNull { (it as? JsonObject)?.let(::jobNodeFromJson) } ?: return null
            val pins = LinkedHashMap<String, JobNode>()
            for ((key, value) in root.obj("pins") ?: JsonObject(emptyMap())) {
                val job = (value as? JsonObject)?.let(::jobNodeFromJson) ?: return null
                pins[key.lowercase()] = job
            }
            return JobIndex(
                jobs = jobs,
                totalSeen = root.int("totalSeen") ?: jobs.size,
                truncated = root.bool("truncated") ?: false,
                fetchedAtMillis = root.long("fetchedAtMillis") ?: 0,
                serverUrl = storedServer,
                pins = pins,
            )
        }

        private fun JobIndex.toJson(): JsonObject = buildJsonObject {
            put("formatVersion", 1)
            put("serverUrl", serverUrl.trimEnd('/'))
            put("fetchedAtMillis", fetchedAtMillis)
            put("totalSeen", totalSeen)
            put("truncated", truncated)
            put("pins", buildJsonObject {
                for ((key, job) in pins) put(key, jobNodeToJson(job))
            })
            put("jobs", buildJsonArray {
                for (job in jobs) add(jobNodeToJson(job))
            })
        }

        private fun sha1Hex(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * A [JobNode] built from a name alone, for a pin that names a job the index does not hold.
 *
 * A pin is a user decision (§9.4 step 5), so an empty index, a job created after the last refresh
 * or a tree cut off by [JobIndex.DEFAULT_MAX_JOBS] must not be able to drop it — but a name is all
 * we have. [name] is read in Jenkins' own path spelling, exactly as Jenkins reports `name` and as
 * its full name shows it: `/` separates levels, a branch's own `/` is already `%2F`
 * (`svc/feature%2FORD-214`), and every other character - a space, an accent - is kept as it is.
 * Each level is therefore used verbatim; [JenkinsUrls.jobPath] does the one URL encoding. Running
 * the levels through a decode/encode round trip instead would turn `My Folder` into `My%20Folder`
 * and the URL into a 404.
 *
 * [JobNode.kind] stays [JobKind.OTHER]: nothing in a name says whether a job is a multibranch
 * container or a branch inside one, and guessing is the dishonesty the matcher exists to avoid.
 * The caller that knows more (the matcher, once it has a branch) builds the child path instead.
 */
fun jobFromName(name: String, serverUrl: String = ""): JobNode =
    jobFromRawPath(name.trim().trim('/').split('/').map { it.trim() }.filter { it.isNotEmpty() }, serverUrl, name.trim())

/** A [JobNode] for a raw path (Jenkins' `name` per level, root first) the index does not hold. */
fun jobFromRawPath(rawPath: List<String>, serverUrl: String = "", fallbackName: String = ""): JobNode {
    val decoded = rawPath.map { JenkinsUrls.decodeSegment(it) }
    val base = if (serverUrl.isBlank()) "" else serverUrl.trimEnd('/') + "/"
    return JobNode(
        name = rawPath.lastOrNull() ?: fallbackName,
        displayName = decoded.lastOrNull() ?: fallbackName,
        fullName = decoded.joinToString("/"),
        rawPath = rawPath,
        className = null,
        kind = JobKind.OTHER,
        url = JenkinsUrls.jobUrl(base, rawPath),
        depth = rawPath.size,
    )
}
