package dev.stagecraft.service

import dev.stagecraft.jenkins.int
import dev.stagecraft.jenkins.jobNodeFromJson
import dev.stagecraft.jenkins.jobNodeToJson
import dev.stagecraft.jenkins.long
import dev.stagecraft.jenkins.str
import dev.stagecraft.model.BuildRef
import dev.stagecraft.model.BuildStatus
import dev.stagecraft.model.JobNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * One branch's build list as the disk cache holds it: the rows, the job they belong to, how the job
 * was named, and when it was fetched.
 *
 * [how] and [versionWarning] are cached with the rows on purpose - they are part of what the tool
 * window paints, and re-deriving them would mean re-running the matcher and the version check for a
 * first paint that is supposed to touch nothing but the disk.
 */
data class CachedBuildList(
    val serverUrl: String,
    val key: String,
    val job: JobNode,
    val builds: List<BuildRef>,
    val how: String,
    val versionWarning: String?,
    val fetchedAtMillis: Long,
)

/**
 * The branch build-list cache (§9.7: "branch build list cache-warm < 500 ms"; §15.4 #3: "populate
 * from the cache first, refresh in the background, and show the age of the data").
 *
 * It exists so `snapshotForFirstPaint` can paint real rows on IDE start instead of a spinner. The
 * index cache tells the matcher *which* job this branch is; this one remembers what that job's last
 * build list looked like, so the window is never empty while the refresh runs.
 *
 * Plain Kotlin and files only - no IntelliJ imports - so it is covered by the headless suite. One
 * file per (server, job, branch) keeps writes independent and the file small enough to read in
 * well under the 200 ms first-paint budget.
 */
object BuildListCache {

    const val FORMAT_VERSION = 1

    /**
     * The identity a cached list is filed under. Server, the resolved job's raw path (which already
     * carries the branch for a multibranch child) and the branch/PR the row set was scoped to, so
     * two branches of one plain job cannot show each other's builds.
     */
    fun key(serverUrl: String, jobRawPath: List<String>, branch: String?, prNumber: Int?): String =
        listOf(
            serverUrl.trimEnd('/'),
            jobRawPath.joinToString("/"),
            branch.orEmpty(),
            prNumber?.toString().orEmpty(),
        ).joinToString("|")

    /** `{cacheDir}/stagecraft/builds-{sha1(key)}.json` - one per branch, never shared. */
    fun cacheFile(cacheDir: File, key: String): File =
        File(cacheDir, "stagecraft/builds-${sha1Hex(key)}.json")

    /**
     * Write through a temporary file and an atomic rename, so a reader - or a crash halfway through
     * - only ever sees the old list or the new one, never half of one.
     */
    fun save(cacheDir: File, entry: CachedBuildList) {
        val file = cacheFile(cacheDir, entry.key)
        val parent = file.parentFile
        if (!parent.isDirectory) parent.mkdirs()
        val tmp = File.createTempFile(file.name, ".tmp", parent)
        try {
            tmp.writeText(entry.toJson().toString())
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }

    /** Null on absence, corruption, unknown format or a different server/branch - a miss, not a crash. */
    fun load(cacheDir: File, serverUrl: String, key: String): CachedBuildList? {
        val file = cacheFile(cacheDir, key)
        if (!file.isFile) return null
        val root = try {
            Json.parseToJsonElement(file.readText())
        } catch (_: Exception) {
            return null
        } as? JsonObject ?: return null
        if (root.int("formatVersion") != FORMAT_VERSION) return null
        val storedServer = root.str("serverUrl") ?: return null
        if (!storedServer.equals(serverUrl.trimEnd('/'), ignoreCase = true)) return null
        val storedKey = root.str("key") ?: return null
        if (storedKey != key) return null
        val job = (root["job"] as? JsonObject)?.let(::jobNodeFromJson) ?: return null
        val builds = root["builds"] as? JsonArray
            ?: return null
        val rows = builds.mapNotNull { (it as? JsonObject)?.let(::buildFromJson) }
        return CachedBuildList(
            serverUrl = storedServer,
            key = storedKey,
            job = job,
            builds = rows,
            how = root.str("how") ?: "",
            versionWarning = root.str("versionWarning"),
            fetchedAtMillis = root.long("fetchedAtMillis") ?: 0L,
        )
    }

    /** How old the cached data is, clamped at zero so a clock skew never reports a negative age. */
    fun ageMillis(entry: CachedBuildList, nowMillis: Long): Long =
        (nowMillis - entry.fetchedAtMillis).coerceAtLeast(0L)

    private fun buildFromJson(o: JsonObject): BuildRef? {
        val number = o.int("number") ?: return null
        val jobFullName = o.str("jobFullName") ?: return null
        val rawPath = (o["jobRawPath"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?: return null
        val status = o.str("status")?.let { name -> runCatching { BuildStatus.valueOf(name) }.getOrNull() }
            ?: BuildStatus.UNKNOWN
        return BuildRef(
            jobFullName = jobFullName,
            jobRawPath = rawPath,
            number = number,
            url = o.str("url") ?: "",
            status = status,
            timestampMillis = o.long("timestampMillis") ?: 0L,
            durationMillis = o.long("durationMillis") ?: 0L,
        )
    }

    private fun CachedBuildList.toJson(): JsonObject = buildJsonObject {
        put("formatVersion", FORMAT_VERSION)
        put("serverUrl", serverUrl.trimEnd('/'))
        put("key", key)
        put("fetchedAtMillis", fetchedAtMillis)
        put("how", how)
        put("versionWarning", versionWarning)
        put("job", jobNodeToJson(job))
        put("builds", buildJsonArray {
            for (build in builds) add(buildJson(build))
        })
    }

    private fun buildJson(build: BuildRef): JsonObject = buildJsonObject {
        put("jobFullName", build.jobFullName)
        put("jobRawPath", JsonArray(build.jobRawPath.map { JsonPrimitive(it) }))
        put("number", build.number)
        put("url", build.url)
        put("status", build.status.name)
        put("timestampMillis", build.timestampMillis)
        put("durationMillis", build.durationMillis)
    }

    private fun sha1Hex(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
