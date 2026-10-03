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

    /**
     * The length of the common suffix cut back to whole path segments, or null when not even the
     * file name matches.
     *
     * `/Users/me/svc/src/Foo.java` and the agent's `/var/jenkins/ws/svc/src/Foo.java` share
     * `/svc/src/Foo.java`; the walk stops at the first differing character (`e` vs `s`), and what
     * counts is the last `/` it passed - or a path that ran out entirely. Checking the boundary at
     * the mismatch itself, as this once did, can never succeed (the two characters differ, so they
     * cannot both be `/`) and accepted only whole-path matches: no agent path ever got a link.
     */
    private fun matchLength(projectPath: String, hint: String): Int? {
        val a = projectPath.replace('\\', '/')
        var i = a.length - 1
        var j = hint.length - 1
        var length = 0
        var lastBoundary = -1
        while (i >= 0 && j >= 0 && a[i] == hint[j]) {
            length++
            if (a[i] == '/') lastBoundary = length
            i--
            j--
        }
        // One side ran out: the match ends on a boundary by definition.
        if (i < 0 || j < 0) return length.takeIf { it > 0 }
        // Otherwise only the part up to (and including) the last separator matched whole segments.
        return lastBoundary.takeIf { it > 0 }
    }
}
