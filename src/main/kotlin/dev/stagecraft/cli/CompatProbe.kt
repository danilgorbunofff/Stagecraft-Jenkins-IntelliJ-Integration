package dev.stagecraft.cli

import dev.stagecraft.jenkins.ConsoleLogReader
import dev.stagecraft.jenkins.JenkinsAuth
import dev.stagecraft.jenkins.JenkinsClient
import dev.stagecraft.jenkins.JenkinsCredential
import dev.stagecraft.jenkins.JenkinsException
import dev.stagecraft.jenkins.LintClient
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * §9.2 / §11 Days 13-14: a headless probe over the **exact** API surface Stagecraft uses, so the
 * compatibility matrix is scriptable instead of hand-clicked.
 *
 * One run per (Jenkins version x CSRF x HTTPS x proxy x token/password) cell:
 *
 * ```
 * STAGECRAFT_USER=admin STAGECRAFT_TOKEN=... \
 *   ./gradlew compatProbe --args="--base=http://localhost:18080 --build=http://localhost:18080/job/multibranch-demo/job/main/1/"
 * ```
 *
 * Every check prints `PASS`, `FAIL` or `SKIP` (a 404 from an optional endpoint is a `SKIP`, not a
 * failure). Exit code 0 only when nothing failed. Credentials come from the environment, never the
 * command line, so a token cannot end up in a shell history or a repository.
 */
fun main(args: Array<String>) {
    val out = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
    val err = PrintStream(FileOutputStream(FileDescriptor.err), true, "UTF-8")
    exitProcess(run(args, System.getenv(), out, err))
}

private fun run(args: Array<String>, env: Map<String, String>, out: PrintStream, err: PrintStream): Int {
    val base = option(args, "--base=") ?: env["STAGECRAFT_URL"]?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE
    val buildUrl = option(args, "--build=") ?: env["STAGECRAFT_BUILD_URL"]?.takeIf { it.isNotBlank() }

    val user = env["STAGECRAFT_USER"]?.takeIf { it.isNotBlank() }
    val token = env["STAGECRAFT_TOKEN"]?.takeIf { it.isNotBlank() }
    val password = env["STAGECRAFT_PASSWORD"]?.takeIf { it.isNotBlank() }
    val credential = when {
        user == null -> null
        token != null -> JenkinsCredential.ApiToken(user, token)
        password != null -> JenkinsCredential.Password(user, password)
        else -> null
    }
    if (credential == null) {
        err.println("stagecraft: set STAGECRAFT_USER and STAGECRAFT_TOKEN (or STAGECRAFT_PASSWORD).")
        return EXIT_USAGE
    }

    val client = JenkinsClient(base, JenkinsAuth(credential))
    var failures = 0

    fun check(name: String, block: () -> String) {
        val verdict = try {
            "PASS ${block()}"
        } catch (e: JenkinsException) {
            failures++
            "FAIL ${e.message}"
        } catch (e: Exception) {
            failures++
            "FAIL ${e.message ?: e.javaClass.simpleName}"
        }
        out.println("$name: $verdict")
    }

    fun optional(name: String, block: () -> String?) {
        val verdict = try {
            block()?.let { "PASS $it" } ?: "SKIP not present on this server (acceptable)"
        } catch (e: JenkinsException) {
            failures++
            "FAIL ${e.message}"
        } catch (e: Exception) {
            failures++
            "FAIL ${e.message ?: e.javaClass.simpleName}"
        }
        out.println("$name: $verdict")
    }

    try {
        check("identity") {
            val me = client.verifyCredentials()
            "authenticated as ${me.id} on ${client.serverBaseUrl}"
        }
        check("version") {
            val version = client.version ?: return@check "no X-Jenkins header"
            client.versionWarning?.let { "warn: $it" } ?: "Jenkins $version"
        }
        check("job-tree") {
            val jobs = client.jobTree()
            "${jobs.size} job(s) in one tree request"
        }
        check("lint") {
            val result = LintClient(client).validate("pipeline { agent any; stages { stage('x') { steps { echo 'hi' } } } }")
            if (result.valid) "validated" else "linter answered: ${result.headline}"
        }

        if (buildUrl == null) {
            out.println("build: SKIP no --build= URL given")
        } else {
            val rawPath = rawPathFromBuildUrl(buildUrl)
            check("builds") {
                val builds = client.builds(rawPath)
                "${builds.size} build(s) of ${rawPath.joinToString("/")}"
            }
            check("console") {
                val log = client.withProgressiveText(buildUrl) { reader, _ ->
                    ConsoleLogReader(headCapChars = 1_000_000, tailCapChars = 100_000).read(reader)
                }
                "${log.totalLines} line(s), firstError=${log.firstErrorLine ?: "none"}"
            }
            optional("stage-view") {
                client.wfapiDescribe(buildUrl)?.let { "wfapi/describe present" }
            }
            optional("test-report") {
                client.testReport(buildUrl)?.let { "testReport present (${it.totalCount} tests)" }
            }
        }
    } finally {
        client.http.close()
    }

    out.println(if (failures == 0) "RESULT: PASS" else "RESULT: FAIL ($failures check(s))")
    return if (failures == 0) EXIT_OK else EXIT_FAILURE
}

/** `http://host/job/a/job/b/1/` -> `[a, b]`; the trailing build number is dropped. */
private fun rawPathFromBuildUrl(buildUrl: String): List<String> {
    val path = buildUrl.substringAfter("://", "").substringAfter('/', "").trim('/')
    val segments = path.split('/').filter { it.isNotEmpty() }
    val raw = ArrayList<String>()
    var i = 0
    while (i + 1 < segments.size && segments[i] == "job") {
        raw += segments[i + 1]
        i += 2
    }
    return raw
}

private fun option(args: Array<String>, prefix: String): String? =
    args.firstOrNull { it.startsWith(prefix) }?.substringAfter('=')?.takeIf { it.isNotBlank() }

private const val DEFAULT_BASE = "http://localhost:18080"
private const val EXIT_OK = 0
private const val EXIT_FAILURE = 1
private const val EXIT_USAGE = 2
