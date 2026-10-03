package dev.stagecraft.jenkins

/** Which language/tool produced a location, so the UI can decide how to resolve it. */
enum class FrameKind {
    JVM,
    PYTHON,
    GO,
    NODE,
    DOTNET,
    MAVEN,
    GRADLE,
    COMPILER,
}

/**
 * A location found in a console or a stack trace (§9.6).
 *
 * [file] is what the text carried - a bare name for a stack frame, an agent-side path for a compiler
 * error. [className] is only set for JVM frames, where the class (not the file name) is what makes
 * `OrderService.java:214` unambiguous. [start]/[end] are offsets into the text, so the editor can
 * underline exactly the matched range.
 */
data class StackFrame(
    val kind: FrameKind,
    val file: String,
    val line: Int,
    val column: Int?,
    val className: String?,
    val method: String?,
    val start: Int,
    val end: Int,
) {

    /**
     * The path to suffix-match against the project's files. For a JVM frame the class's package is
     * prepended (`com/company/OrderServiceTest.java`), so the class - not just the common file name -
     * picks the file. For everything else the text's own path is used.
     */
    val pathHint: String
        get() {
            val name = className ?: return file
            val packageName = name.substringBeforeLast('.', "")
            return if (packageName.isEmpty()) file else packageName.replace('.', '/') + "/" + file
        }
}

/**
 * The eight location patterns of §9.6, matched against console or `errorStackTrace` text.
 *
 * Five are stack frames (a class and a bare file name) and three are compiler/tool errors (an
 * agent-side path) - the most common way builds actually fail. Matching is deliberately textual:
 * whether a location can be opened is a separate question answered by suffix-matching the path
 * against the project ([dev.stagecraft.service.SuffixSourceResolver]).
 */
object StackFrames {

    private val JVM = Regex(
        "(?:^|\\s)at\\s+(?:[\\w$.\\-]*/+)*([\\w$.]+)\\.([\\w$<>\\-]+)" +
            "\\(([\\w$\\-]+\\.(?:java|kt|groovy|scala)):(\\d+)\\)",
    )
    private val PYTHON = Regex("File \"(.+?)\", line (\\d+)")
    private val GO = Regex("^\\s+(\\S+?\\.go):(\\d+)", RegexOption.MULTILINE)
    private val NODE = Regex("\\bat (?:.+? \\()?((?:[A-Za-z]:)?[^\\s():]+\\.(?:[cm]?js|tsx?)):(\\d+):\\d+\\)?")
    private val DOTNET = Regex("\\bin (.+?\\.cs):line (\\d+)")
    private val MAVEN = Regex("^\\[(?:ERROR|WARNING)\\] (\\S+?\\.\\w+):\\[(\\d+),(\\d+)\\]", RegexOption.MULTILINE)
    private val GRADLE = Regex("^[ew]: (?:file://)?(\\S+?\\.kts?):(\\d+):(\\d+)", RegexOption.MULTILINE)
    private val COMPILER = Regex(
        "^(\\S+?\\.(?:java|c|cc|cpp|h|hpp|go|ts|tsx|js|py|rs)):(\\d+)(?::(\\d+))?: (?:fatal )?(?:error|warning)\\b",
        RegexOption.MULTILINE,
    )

    fun find(text: String, limit: Int = Int.MAX_VALUE): List<StackFrame> {
        val frames = ArrayList<StackFrame>()
        // Stack frames: class(1), method(2), file(3), line(4).
        collect(text, JVM, FrameKind.JVM, file = 3, line = 4, column = null, klass = 1, method = 2, into = frames)
        // File/line frames: file(1), line(2).
        collect(text, PYTHON, FrameKind.PYTHON, file = 1, line = 2, column = null, klass = null, method = null, into = frames)
        collect(text, GO, FrameKind.GO, file = 1, line = 2, column = null, klass = null, method = null, into = frames)
        collect(text, NODE, FrameKind.NODE, file = 1, line = 2, column = null, klass = null, method = null, into = frames)
        collect(text, DOTNET, FrameKind.DOTNET, file = 1, line = 2, column = null, klass = null, method = null, into = frames)
        // Compiler/tool errors: file(1), line(2), column(3).
        collect(text, MAVEN, FrameKind.MAVEN, file = 1, line = 2, column = 3, klass = null, method = null, into = frames)
        collect(text, GRADLE, FrameKind.GRADLE, file = 1, line = 2, column = 3, klass = null, method = null, into = frames)
        collect(text, COMPILER, FrameKind.COMPILER, file = 1, line = 2, column = 3, klass = null, method = null, into = frames)

        // Keep the earliest match when two patterns overlap the same characters, so a location is
        // underlined once and resolves one way.
        val sorted = frames.sortedBy { it.start }
        val kept = ArrayList<StackFrame>(sorted.size)
        for (frame in sorted) {
            if (kept.none { it.start < frame.end && frame.start < it.end }) {
                kept += frame
                if (kept.size >= limit) break
            }
        }
        return kept
    }

    private fun collect(
        text: String,
        regex: Regex,
        kind: FrameKind,
        file: Int,
        line: Int,
        column: Int?,
        klass: Int?,
        method: Int?,
        into: MutableList<StackFrame>,
    ) {
        for (match in regex.findAll(text)) {
            val groups = match.groupValues
            val lineNumber = groups.getOrNull(line)?.toIntOrNull() ?: continue
            val fileName = groups.getOrNull(file)?.takeIf { it.isNotBlank() } ?: continue
            into += StackFrame(
                kind = kind,
                file = fileName,
                line = lineNumber,
                column = column?.let { groups.getOrNull(it)?.toIntOrNull() },
                className = klass?.let { groups.getOrNull(it) },
                method = method?.let { groups.getOrNull(it) },
                start = match.range.first,
                end = match.range.last + 1,
            )
        }
    }
}
