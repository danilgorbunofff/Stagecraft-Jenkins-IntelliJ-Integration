package dev.stagecraft.cli

import dev.stagecraft.jenkins.JenkinsAuth
import dev.stagecraft.jenkins.JenkinsClient
import dev.stagecraft.jenkins.JenkinsCredential
import dev.stagecraft.jenkins.JenkinsException
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * §11 Days 1-2 exit criterion, the half that needs no IDE:
 *
 * > a plain Kotlin `main()` prints the real console of the real build.
 *
 * ```
 * STAGECRAFT_USER=admin STAGECRAFT_TOKEN=... \
 *   ./gradlew consoleDump -PbuildUrl=http://localhost:18080/job/multibranch-demo/job/main/1/
 * ```
 *
 * Credentials are read from the environment on purpose: a token must never be written into the
 * repository, and a tool that cannot be run without one is a tool nobody runs (§15.4).
 *
 * stdout carries the console and nothing else, so it can be redirected to a file; progress and
 * diagnostics go to stderr. Exit codes are distinct per failure so a script can tell "wrong token"
 * from "no such job" without parsing English.
 */
fun main(args: Array<String>) {
    // Windows consoles default to a legacy code page, which turns the `unicode-lines` fixture into
    // question marks. Write UTF-8 explicitly so the dump is byte-comparable with /consoleText.
    val out = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
    val err = PrintStream(FileOutputStream(FileDescriptor.err), true, "UTF-8")
    exitProcess(run(args, System.getenv(), out, err))
}

private fun run(args: Array<String>, env: Map<String, String>, out: PrintStream, err: PrintStream): Int {
    val options = try {
        parseArgs(args, env)
    } catch (e: IllegalArgumentException) {
        err.println("stagecraft: ${e.message}")
        err.println(usage())
        return EXIT_USAGE
    }

    if (options.help) {
        out.println(usage())
        return EXIT_OK
    }

    val credential = options.credential
    if (credential == null) {
        err.println("stagecraft: no credentials in the environment.")
        err.println("Set STAGECRAFT_USER together with STAGECRAFT_TOKEN (or STAGECRAFT_PASSWORD).")
        return EXIT_USAGE
    }

    val buildUrl = options.buildUrl
    if (buildUrl == null) {
        err.println("stagecraft: no build URL. Pass one as the first argument, or set STAGECRAFT_BUILD_URL.")
        err.println(usage())
        return EXIT_USAGE
    }

    val client = JenkinsClient(options.baseUrl, JenkinsAuth(credential))
    try {
        val me = client.verifyCredentials()
        err.println("stagecraft: authenticated to ${client.serverBaseUrl} as ${me.id}")
        client.versionWarning?.let { err.println("stagecraft: WARNING - $it") }

        var offset = options.start
        var totalBytes = 0L
        var chunks = 0
        while (true) {
            val chunk = client.progressiveText(buildUrl, offset)
            if (chunk.resetDetected) {
                // Day-0 re-check R1: an offset past the end of the log makes Jenkins resend the
                // whole log from the start. Say so, rather than let the repeat pass for new output.
                err.println(
                    "stagecraft: WARNING - the cursor moved backwards (asked for $offset, server said " +
                        "${chunk.nextOffset}); Jenkins resent the log from the start, and it is printed again below.",
                )
            }
            out.print(chunk.text)
            out.flush()
            totalBytes += chunk.rawByteCount
            chunks++
            if (options.json) {
                err.println(
                    "stagecraft: chunk $chunks - ${chunk.rawByteCount} bytes, cursor $offset -> " +
                        "${chunk.nextOffset}, moreData=${chunk.moreData}",
                )
            }
            // `X-More-Data` is absent, not `false`, once a build has finished (Day-0 re-check R1).
            if (!chunk.moreData) break
            offset = chunk.nextOffset
            // A running build: wait before asking again rather than hammer the server in a loop.
            Thread.sleep(options.intervalMillis)
        }
        err.println("stagecraft: done - $totalBytes bytes in $chunks request(s)")
        return EXIT_OK
    } catch (e: JenkinsException.Unauthorized) {
        err.println("stagecraft: ${e.message}")
        return EXIT_AUTH
    } catch (e: JenkinsException.Forbidden) {
        err.println("stagecraft: ${e.message}")
        return EXIT_FORBIDDEN
    } catch (e: JenkinsException.NotFound) {
        err.println("stagecraft: ${e.message}")
        return EXIT_NOT_FOUND
    } catch (e: JenkinsException.Transport) {
        err.println("stagecraft: ${e.message}")
        return EXIT_TRANSPORT
    } catch (e: JenkinsException) {
        err.println("stagecraft: ${e.message}")
        return EXIT_FAILURE
    } catch (e: InterruptedException) {
        err.println("stagecraft: interrupted")
        return EXIT_FAILURE
    } finally {
        client.http.close()
    }
}

private const val EXIT_OK = 0
private const val EXIT_FAILURE = 1
private const val EXIT_USAGE = 2
private const val EXIT_AUTH = 3
private const val EXIT_FORBIDDEN = 4
private const val EXIT_NOT_FOUND = 5
private const val EXIT_TRANSPORT = 6

private const val DEFAULT_BASE_URL = "http://localhost:18080"

/** Pause between polls of a running build. */
private const val DEFAULT_INTERVAL_MILLIS = 2_000L

private class Options(
    val baseUrl: String,
    val buildUrl: String?,
    val credential: JenkinsCredential?,
    val start: Long,
    val intervalMillis: Long,
    val json: Boolean,
    val help: Boolean,
)

private fun parseArgs(args: Array<String>, env: Map<String, String>): Options {
    var baseUrl = env["STAGECRAFT_URL"]?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
    var buildUrl = env["STAGECRAFT_BUILD_URL"]?.takeIf { it.isNotBlank() }
    var start = 0L
    var interval = DEFAULT_INTERVAL_MILLIS
    var json = false
    var help = false

    for (arg in args) {
        when {
            arg == "--help" || arg == "-h" -> help = true
            arg == "--json" -> json = true
            arg.startsWith("--url=") -> buildUrl = arg.substringAfter('=')
            arg.startsWith("--base=") -> baseUrl = arg.substringAfter('=')
            arg.startsWith("--interval=") -> {
                val value = arg.substringAfter('=')
                interval = value.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("--interval must be a positive number of milliseconds, not '$value'")
            }
            arg.startsWith("--start=") -> {
                val value = arg.substringAfter('=')
                start = value.toLongOrNull() ?: throw IllegalArgumentException("--start must be a number, not '$value'")
                if (start < 0) throw IllegalArgumentException("--start must not be negative")
            }
            arg.startsWith("--") -> throw IllegalArgumentException("unknown option '$arg'")
            buildUrl == null -> buildUrl = arg
            else -> throw IllegalArgumentException("more than one build URL was given ('$buildUrl' and '$arg')")
        }
    }

    val user = env["STAGECRAFT_USER"]?.takeIf { it.isNotBlank() }
    val token = env["STAGECRAFT_TOKEN"]?.takeIf { it.isNotBlank() }
    val password = env["STAGECRAFT_PASSWORD"]?.takeIf { it.isNotBlank() }
    val credential = when {
        user == null -> null
        token != null -> JenkinsCredential.ApiToken(user, token)
        password != null -> JenkinsCredential.Password(user, password)
        else -> null
    }

    return Options(baseUrl, buildUrl, credential, start, interval, json, help)
}

private fun usage(): String = """
    stagecraft - print a Jenkins build's console

    Usage: consoleDump [buildUrl] [--url=<buildUrl>] [--base=<baseUrl>] [--start=N] [--interval=ms] [--json]

    Environment:
      STAGECRAFT_USER        Jenkins user name                       (required)
      STAGECRAFT_TOKEN       Jenkins API token                      (preferred)
      STAGECRAFT_PASSWORD    Jenkins password, if no token exists
      STAGECRAFT_URL         server base URL          (default $DEFAULT_BASE_URL)
      STAGECRAFT_BUILD_URL   build URL, if not given as an argument

    stdout is the console and nothing else. Progress and warnings go to stderr.
""".trimIndent()
