package dev.stagecraft.service

import dev.stagecraft.jenkins.StackFrame

/** A file in the project a frame resolved to, and the line to open. */
data class SourceLocation(val path: String, val line: Int, val column: Int?)

/** Turns a [StackFrame] into a project file, or `null` when no link should be offered. */
interface SourceResolver {
    fun resolve(frame: StackFrame): SourceLocation?
}

/**
 * §9.6: resolve a frame against the project by **suffix**, never by absolute path.
 *
 * The Jenkins agent's workspace path (`/var/jenkins/workspace/my-service/src/main/...`) has nothing
 * to do with the developer's checkout (`src/main/java/...`). So the longest suffix that matches a
 * project file on a path-segment boundary wins, and a tie means **no link** - a wrong-file link is a
 * bug report. A frame whose file is not in the project resolves to null, and the UI renders it as
 * plain text.
 *
 * Plain Kotlin over a list of paths, so the matching is unit-tested without an IDE; the IDE adapter
 * supplies the paths from the project's file index.
 */
class SuffixSourceResolver(private val projectPaths: List<String>) : SourceResolver {

    override fun resolve(frame: StackFrame): SourceLocation? {
        val hint = frame.pathHint.replace('\\', '/')
        var best: String? = null
        var bestLength = -1
        var tied = false
        for (path in projectPaths) {
            val length = matchLength(path, hint) ?: continue
            when {
                length > bestLength -> {
                    best = path
                    bestLength = length
                    tied = false
                }
                length == bestLength -> tied = true
            }
        }
        if (best == null || tied) return null
        return SourceLocation(best, frame.line, frame.column)
    }

    /** The length of the common suffix, or null when it does not start on a segment boundary. */
    private fun matchLength(projectPath: String, hint: String): Int? {
        val a = projectPath.replace('\\', '/')
        var i = a.length - 1
        var j = hint.length - 1
        var length = 0
        while (i >= 0 && j >= 0 && a[i] == hint[j]) {
            length++
            i--
            j--
        }
        if (length == 0) return null
        val boundaryA = i < 0 || a[i] == '/'
        val boundaryB = j < 0 || hint[j] == '/'
        return if (boundaryA && boundaryB) length else null
    }
}
