package dev.stagecraft.service

import dev.stagecraft.jenkins.int
import dev.stagecraft.jenkins.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The project's git remote and branch from the last start, so the first paint does not have to wait
 * for `git` to answer (§9.7: first paint cache-warm < 200 ms).
 *
 * The hand measurement of Day 5-6 found git on the critical path at **1210 ms** under IDE-startup
 * load (250-430 ms from a plain shell) - far over the budget, and not something the build-list cache
 * alone could fix, because the matcher cannot run until it knows the branch. Painting from the last
 * known branch and refining when git answers is the §15.4 #3 rule applied to the one input the disk
 * cannot otherwise supply.
 *
 * It is a hint, not a fact: a project that switched branches between two IDE starts shows the old
 * branch's cached rows, labelled with their age, for the moment it takes the refresh to replace
 * them.
 */
object BranchContextCache {

    private const val FORMAT_VERSION = 1

    fun cacheFile(cacheDir: File): File = File(cacheDir, "stagecraft/branch-context.json")

    fun save(cacheDir: File, ctx: BranchContext) {
        val file = cacheFile(cacheDir)
        val parent = file.parentFile
        if (!parent.isDirectory) parent.mkdirs()
        val tmp = File.createTempFile(file.name, ".tmp", parent)
        try {
            tmp.writeText(toJson(ctx).toString())
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }

    /** Null on absence, corruption, unknown format or a context that names nothing - a miss. */
    fun load(cacheDir: File): BranchContext? {
        val file = cacheFile(cacheDir)
        if (!file.isFile) return null
        val root = try {
            Json.parseToJsonElement(file.readText())
        } catch (_: Exception) {
            return null
        } as? JsonObject ?: return null
        if (root.int("formatVersion") != FORMAT_VERSION) return null
        val remote = root.str("remoteUrl")
        val branch = root.str("branch")
        if (remote.isNullOrBlank() && branch.isNullOrBlank()) return null
        return BranchContext(remote, branch, root.int("prNumber"))
    }

    private fun toJson(ctx: BranchContext): JsonObject = buildJsonObject {
        put("formatVersion", FORMAT_VERSION)
        put("remoteUrl", ctx.remoteUrl)
        put("branch", ctx.branch)
        put("prNumber", ctx.prNumber)
    }
}
