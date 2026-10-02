package dev.stagecraft.jenkins

import dev.stagecraft.model.JobKind
import dev.stagecraft.model.JobNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.io.File
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

    data class RankedJob(val job: JobNode, val tier: Int, val reason: String)

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
     * Ties inside a tier break by shallower [JobNode.depth], then name.
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
            if (tier > 0) candidates += RankedJob(job, tier, reasonFor(tier, remote, job))
        }
        candidates.sortWith(
            compareByDescending<RankedJob> { it.tier }
                .thenBy { it.job.depth }
                .thenBy { it.job.displayName.lowercase() },
        )
        return candidates
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
            val all = client.jobTree()
            val truncated = all.size > maxJobs
            return JobIndex(
                jobs = if (truncated) all.take(maxJobs) else all,
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

        fun save(cacheDir: File, index: JobIndex) {
            val file = cacheFile(cacheDir, index.serverUrl)
            val parent = file.parentFile
            if (!parent.isDirectory) parent.mkdirs()
            val tmp = File(parent, file.name + ".tmp")
            tmp.writeText(index.toJson().toString())
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IllegalStateException("could not move the job index into place at $file")
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
            val jobs = root.arr("jobs")?.mapNotNull { (it as? JsonObject)?.let(::jobFromJson) } ?: return null
            val pins = LinkedHashMap<String, JobNode>()
            for ((key, value) in root.obj("pins") ?: JsonObject(emptyMap())) {
                val job = (value as? JsonObject)?.let(::jobFromJson) ?: return null
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

        private fun jobFromJson(o: JsonObject): JobNode? {
            val name = o.str("name") ?: return null
            val fullName = o.str("fullName") ?: return null
            val rawPath = o.arr("rawPath")
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?: return null
            if (rawPath.isEmpty()) return null
            val kind = o.str("kind")?.let { parsed -> runCatching { JobKind.valueOf(parsed) }.getOrNull() }
                ?: JobKind.OTHER
            return JobNode(
                name = name,
                displayName = o.str("displayName") ?: name,
                fullName = fullName,
                rawPath = rawPath,
                className = o.str("className"),
                kind = kind,
                url = o.str("url") ?: "",
                depth = o.int("depth") ?: rawPath.size,
                colour = o.str("colour"),
            )
        }

        private fun JobIndex.toJson(): JsonObject = buildJsonObject {
            put("formatVersion", 1)
            put("serverUrl", serverUrl.trimEnd('/'))
            put("fetchedAtMillis", fetchedAtMillis)
            put("totalSeen", totalSeen)
            put("truncated", truncated)
            put("pins", buildJsonObject {
                for ((key, job) in pins) put(key, jobJson(job))
            })
            put("jobs", buildJsonArray {
                for (job in jobs) add(jobJson(job))
            })
        }

        private fun jobJson(job: JobNode): JsonObject = buildJsonObject {
            put("name", job.name)
            put("displayName", job.displayName)
            put("fullName", job.fullName)
            put("rawPath", JsonArray(job.rawPath.map { JsonPrimitive(it) }))
            put("className", job.className)
            put("kind", job.kind.name)
            put("url", job.url)
            put("depth", job.depth)
            put("colour", job.colour)
        }

        private fun sha1Hex(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
