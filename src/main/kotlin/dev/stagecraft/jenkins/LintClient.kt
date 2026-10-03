package dev.stagecraft.jenkins

/**
 * The result of validating a Jenkinsfile against the server's own linter (§7.4).
 *
 * [message] is the server's text verbatim, kept whole so the UI can show it formatted and copyable.
 * [valid] is derived from the one string the endpoint returns for success; anything else is an error
 * report.
 */
data class LintResult(val valid: Boolean, val message: String) {

    /** The first non-blank line, for a headline; the rest are details. */
    val headline: String
        get() = message.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    val details: List<String>
        get() = message.lineSequence()
            .dropWhile { it.isBlank() }
            .drop(1)
            .filter { it.isNotBlank() }
            .map { it.trimEnd() }
            .toList()
}

/**
 * `POST /pipeline-model-converter/validate` — the server's **real** linter, with its real plugins and
 * shared libraries (§7.4).
 *
 * The whole point is that it lints the **editor buffer**, not a saved file, which is the incumbent's
 * most-quoted defect. The POST carries the Jenkinsfile in the `jenkinsfile` field; crumb handling is
 * [JenkinsClient.postForm]'s job, so a password-auth server gets the crumb and session cookie and a
 * token-auth server sends no crumb at all (§9.2).
 */
class LintClient(private val client: JenkinsClient) {

    fun validate(jenkinsfile: String): LintResult {
        val text = client.postForm(PATH, mapOf(FIELD to jenkinsfile))
        return LintResult(valid = text.trimStart().startsWith(SUCCESS_PREFIX), message = text)
    }

    companion object {
        const val PATH = "pipeline-model-converter/validate"
        const val FIELD = "jenkinsfile"
        const val SUCCESS_PREFIX = "Jenkinsfile successfully validated"
    }
}
