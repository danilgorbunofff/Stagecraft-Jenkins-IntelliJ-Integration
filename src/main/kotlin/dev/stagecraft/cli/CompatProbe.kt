package dev.stagecraft.cli

import dev.stagecraft.jenkins.ConsoleLogReader
import dev.stagecraft.jenkins.JenkinsAuth
import dev.stagecraft.jenkins.JenkinsClient
import dev.stagecraft.jenkins.JenkinsCredential
import dev.stagecraft.jenkins.JenkinsException
import dev.stagecraft.jenkins.JenkinsUrls
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
            // Every Jenkins response carries X-Jenkins; its absence means something else answered.
            val version = client.version ?: error("no X-Jenkins header on any response so far")
            client.versionWarning?.let { "warn: $it" } ?: "Jenkins $version"
        }
        check("job-tree") {
            val jobs = client.jobTree()
            "${jobs.size} job(s) in one tree request"
        }
        check("lint") {
            // A known-valid Jenkinsfile: anything but "valid" is a failure of the lint path.
            val result = LintClient(client).validate(VALID_JENKINSFILE)
            if (!result.valid) error("the known-valid sample was rejected: ${result.headline}")
            val crumb = if (credential.requiresCrumb) {
                // §9.2: a password POST must have gone through the crumb flow.
                if (!client.http.auth.crumbCache.isLoaded) error("password auth posted without asking for a crumb")
                if (client.http.auth.crumbCache.isDisabled) ", CSRF off on this server" else ", with crumb"
            } else {
                ", crumbless (API token)"
            }
            "validated$crumb"
        }

        val rawPath = buildUrl?.let(::rawPathFromBuildUrl).orEmpty()
        when {
            buildUrl == null -> out.println("build: SKIP no --build= URL given")
            rawPath.isEmpty() -> {
                failures++
                out.println("build: FAIL no /job/ segment in $buildUrl")
            }
            else -> {
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
        }
    } finally {
        client.http.close()
    }

    out.println(if (failures == 0) "RESULT: PASS" else "RESULT: FAIL ($failures check(s))")
    return if (failures == 0) EXIT_OK else EXIT_FAILURE
}

/**
 * `http://host/jenkins/job/a/job/feature%252Fx/1/` -> `[a, feature%2Fx]`.
 *
 * Anything before the first `job` segment is a context path and is skipped. Each name in a URL is
 * Jenkins' raw `name` encoded once more, so it is decoded once - otherwise `feature%252Fx` would be
 * encoded a third time on the way back out and 404.
 */
internal fun rawPathFromBuildUrl(buildUrl: String): List<String> {
    val path = buildUrl.substringAfter("://", buildUrl).substringAfter('/', "").substringBefore('?').trim('/')
    val segments = path.split('/').filter { it.isNotEmpty() }
    val raw = ArrayList<String>()
    var i = segments.indexOf("job").let { if (it < 0) segments.size else it }
    while (i + 1 < segments.size && segments[i] == "job") {
        raw += JenkinsUrls.decodeSegment(segments[i + 1])
        i += 2
    }
    return raw
}

private fun option(args: Array<String>, prefix: String): String? =
    args.firstOrNull { it.startsWith(prefix) }?.substringAfter('=')?.takeIf { it.isNotBlank() }

private const val DEFAULT_BASE = "http://localhost:18080"

private const val VALID_JENKINSFILE =
    "pipeline {\n  agent any\n  stages {\n    stage('x') {\n      steps {\n        echo 'hi'\n      }\n    }\n  }\n}\n"
private const val EXIT_OK = 0
private const val EXIT_FAILURE = 1
private const val EXIT_USAGE = 2
