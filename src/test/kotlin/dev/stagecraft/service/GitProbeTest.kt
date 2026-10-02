package dev.stagecraft.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * [GitProbe] exists to answer one question without depending on the Git plugin, so the injected
 * runner stands in for `git` itself. That keeps these tests about how the probe reads git rather
 * than about which git is installed on the machine that runs them.
 */
class GitProbeTest {

    private val root = File("/repo")

    /** Answers the invocations the probe makes; the key is the arguments after `git -C <root>`. */
    private fun answers(vararg pairs: Pair<List<String>, String?>): (List<String>) -> String? {
        val table = pairs.toMap()
        return { command ->
            if (command.size < 3 || command[0] != "git" || command[1] != "-C") {
                fail("unexpected invocation: $command")
            }
            table[command.drop(3)]
        }
    }

    private fun probe(runner: (List<String>) -> String?) = GitProbe(root, runner = runner)

    @Test
    fun `reads the origin remote and the current branch`() {
        val context = probe(
            answers(
                listOf("config", "--get", "remote.origin.url") to "git@github.com:org/repo.git\n",
                listOf("rev-parse", "--abbrev-ref", "HEAD") to "feature/login\n",
            ),
        ).read()

        assertEquals("git@github.com:org/repo.git", context.remoteUrl)
        assertEquals("feature/login", context.branch)
    }

    @Test
    fun `falls back to the first remote when origin is not set`() {
        val context = probe(
            answers(
                listOf("config", "--get", "remote.origin.url") to null,
                listOf("remote") to "upstream\n",
                listOf("remote", "get-url", "upstream") to "https://git.example.com/org/repo.git\n",
                listOf("rev-parse", "--abbrev-ref", "HEAD") to "main\n",
            ),
        ).read()

        assertEquals("https://git.example.com/org/repo.git", context.remoteUrl)
        assertEquals("main", context.branch)
    }

    @Test
    fun `a detached head reports no branch`() {
        val context = probe(
            answers(
                listOf("config", "--get", "remote.origin.url") to "git@github.com:org/repo.git\n",
                listOf("rev-parse", "--abbrev-ref", "HEAD") to "HEAD\n",
            ),
        ).read()

        assertEquals("git@github.com:org/repo.git", context.remoteUrl)
        assertNull(context.branch)
    }

    @Test
    fun `blank answers are no answers`() {
        val context = probe(
            answers(
                listOf("config", "--get", "remote.origin.url") to "   \n",
                listOf("remote") to "\n",
                listOf("rev-parse", "--abbrev-ref", "HEAD") to "",
            ),
        ).read()

        assertNull(context.remoteUrl)
        assertNull(context.branch)
    }

    @Test
    fun `a project without a root never starts git`() {
        val context = GitProbe(null, runner = { fail("git must not run without a project root") }).read()

        assertNull(context.remoteUrl)
        assertNull(context.branch)
    }

    @Test
    fun `a git context becomes a branch context`() {
        val context = GitContext("git@github.com:org/repo.git", "main")

        assertEquals(
            BranchContext("git@github.com:org/repo.git", "main", 42),
            context.asBranchContext(42),
        )
        assertEquals(
            BranchContext("git@github.com:org/repo.git", "main", null),
            context.asBranchContext(),
        )
    }
}
