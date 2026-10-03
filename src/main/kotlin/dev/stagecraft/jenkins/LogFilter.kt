package dev.stagecraft.jenkins

/** The log view's filter modes (§7.2): everything, errors only, warnings only, own package only. */
enum class LogFilterMode {
    ALL,
    ERRORS,
    WARNINGS,
    OWN_PACKAGE,
}

/**
 * Filters a console log for the log view's filter box, and optionally collapses runs of blank lines.
 *
 * A real Jenkins log is roughly 90% framework noise, so `error`, `warn` and `own-package-only` are
 * the difference between a log a person can read and one they cannot (§7.2). [OWN_PACKAGE] keeps
 * lines that mention any of [ownPackageTokens] - class or package names derived from the project -
 * and, with no tokens to match on, keeps everything rather than blanking the view.
 *
 * Plain Kotlin and no IDE imports, so the filter is unit-tested without a server.
 */
object LogFilter {

    private val WARNING = Regex("^(?:WARNING: |\\[WARNING\\] |\\[WARN\\] |w: )|\\bWARNING\\b")

    fun isWarning(line: String): Boolean = WARNING.containsMatchIn(line)

    fun apply(
        text: String,
        mode: LogFilterMode,
        ownPackageTokens: List<String> = emptyList(),
        collapseBlankLines: Boolean = false,
    ): String {
        val out = StringBuilder(text.length)
        var wroteAny = false
        var lastBlank = false
        for (line in text.lineSequence()) {
            val kept = when (mode) {
                LogFilterMode.ALL -> true
                LogFilterMode.ERRORS -> ConsoleStages.isErrorLine(line)
                LogFilterMode.WARNINGS -> isWarning(line)
                LogFilterMode.OWN_PACKAGE ->
                    ownPackageTokens.isEmpty() || ownPackageTokens.any { line.contains(it, ignoreCase = true) }
            }
            if (!kept) continue

            val normalised = if (collapseBlankLines) line.trimEnd() else line
            val blank = normalised.isEmpty()
            if (collapseBlankLines && blank && lastBlank) continue

            if (wroteAny) out.append('\n')
            out.append(normalised)
            wroteAny = true
            lastBlank = blank
        }
        return out.toString()
    }
}
