package dev.stagecraft.service

import java.io.File
import java.util.concurrent.TimeUnit

/** What the project's git checkout says about where the code came from. */
data class GitContext(
    val remoteUrl: String?,
    val branch: String?,
) {

    fun asBranchContext(prNumber: Int? = null): BranchContext =
        BranchContext(remoteUrl, branch, prNumber)
}

/**
 * Reads the project's git remote and branch by asking `git` itself.
 *
 * The alternative is a dependency on the Git plugin (`git4idea`), which would tie Stagecraft to a
 * plugin that is not installed in every IDE and to its internal API. Three short read-only `git`
 * calls answer the one question Stagecraft has, degrade to "no remote" when git is not on the
 * PATH, and keep the plugin free of a dependency it does not need.
 *
 * [read] starts processes, so it must not run on the EDT (§7.3 rule 2); every call is bounded by
 * [timeoutMillis] so a wedged git cannot wedge a load either.
 */
class GitProbe(
    private val projectRoot: File?,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val runner: (List<String>) -> String? = { command -> runGit(command, timeoutMillis) },
) {

    fun read(): GitContext {
        val root = projectRoot ?: return GitContext(null, null)
        val origin = git(root, "config", "--get", "remote.origin.url")?.takeIf { it.isNotBlank() }
        val remote = origin ?: firstRemote(root)
        val branch = git(root, "rev-parse", "--abbrev-ref", "HEAD")
            // A detached HEAD answers "HEAD", which would name a Jenkins branch that does not
            // exist. Admitting the branch is unknown is better than inventing one.
            ?.takeIf { it.isNotBlank() && it != DETACHED_HEAD }
        return GitContext(remote?.trim()?.takeIf { it.isNotBlank() }, branch)
    }

    /** A checkout whose only remote is `upstream` still has a remote worth matching. */
    private fun firstRemote(root: File): String? =
        git(root, "remote")
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.let { name -> git(root, "remote", "get-url", name.trim()) }

    private fun git(root: File, vararg args: String): String? =
        runner(listOf("git", "-C", root.path) + args)?.trim()

    companion object {

        /** Long enough for a cold git on a network drive, short enough never to hold up a load. */
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L

        private const val DETACHED_HEAD = "HEAD"

        private fun runGit(command: List<String>, timeoutMillis: Long): String? {
            val process = try {
                ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (e: Exception) {
                // No git installed is a fact about the machine, not a failure of the load.
                return null
            }
            return try {
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    null
                } else if (process.exitValue() != 0) {
                    // `git config --get` exits 1 for a key that is not set.
                    null
                } else {
                    process.inputStream.bufferedReader().use { it.readText() }
                }
            } catch (e: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
                null
            } finally {
                process.destroy()
            }
        }
    }
}
