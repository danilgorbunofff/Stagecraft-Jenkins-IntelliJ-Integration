package dev.stagecraft.jenkins

import dev.stagecraft.model.JobKind
import dev.stagecraft.model.JobNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The one on-disk spelling of a [JobNode], shared by every cache so the index and the build-list
 * cache can never drift apart on what a job looks like when written.
 *
 * [jobNodeFromJson] is deliberately forgiving: a cache is an optimisation, so a missing or
 * unrecognised field is a miss (null), never a crash at IDE start.
 */
internal fun jobNodeToJson(job: JobNode): JsonObject = buildJsonObject {
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

internal fun jobNodeFromJson(o: JsonObject): JobNode? {
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
