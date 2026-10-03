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
 * The location patterns of §9.6, matched against console or `errorStackTrace` text.
 *
 * Five are stack frames (a class and a bare file name) and the rest are compiler/tool errors (an
 * agent-side path) - the most common way builds actually fail: Maven, Gradle/Kotlin, gcc/javac-style
 * `file:line:col: error`, Go, TypeScript and rustc. Matching is deliberately textual: whether a
 * location can be opened is a separate question answered by suffix-matching the path against the
 * project ([dev.stagecraft.service.SuffixSourceResolver]).
 *
 * The text is scanned **line by line**, each pattern only after a cheap substring test says it can
 * match, and lines longer than [MAX_LINE_CHARS] are skipped - a stack frame is never that long, and
 * a regex over a minified-JSON line of hundreds of kilobytes is what used to freeze the EDT. Offsets
 * are still offsets into the whole text.
 */
object StackFrames {

    /** `at pkg.Class.method(File.kt:12)`. The method may contain spaces (Kotlin backtick test names). */
    private val JVM = Regex(
        "(?:^|(?<=\\s))at\\s+(?:[\\w$.\\-]*/+)*([\\w$.]+)\\.([^().\\n]+?)" +
            "\\(([\\w$\\-]+\\.(?:java|kt|kts|groovy|scala)):(\\d+)\\)",
    )
    private val PYTHON = Regex("File \"([^\"]{1,500})\", line (\\d+)")
    private val GO = Regex("^\\s+(\\S+?\\.go):(\\d+)")
    private val NODE = Regex("\\bat (?:[^()\\n]{0,300}? \\()?((?:[A-Za-z]:)?[^\\s():]+\\.(?:[cm]?js|tsx?)):(\\d+):\\d+\\)?")
    private val DOTNET = Regex("\\bin ([^\\n]{1,500}?\\.cs):line (\\d+)")
    private val MAVEN = Regex("^\\[(?:ERROR|WARNING)\\] (\\S+?\\.\\w+):\\[(\\d+),(\\d+)\\]")
    private val GRADLE = Regex("^[ew]: (?:file://)?(\\S+?\\.kts?):(\\d+):(\\d+)")
    private val COMPILER = Regex(
        "^(\\S+?\\.(?:java|c|cc|cpp|h|hpp|go|ts|tsx|js|py|rs)):(\\d+)(?::(\\d+))?: (?:fatal )?(?:error|warning)\\b",
    )
    /** `./main.go:10:5: undefined: x` - the Go compiler prints no "error:" word. */
    private val GO_BUILD = Regex("^(\\.{0,2}/?\\S+?\\.go):(\\d+):(\\d+): \\S")
    /** `src/a.ts(10,5): error TS2322: ...` */
    private val TSC = Regex("^(\\S+?\\.tsx?)\\((\\d+),(\\d+)\\): error TS\\d+")
    /** `  --> src/main.rs:10:5` */
    private val RUSTC = Regex("^\\s*--> (\\S+?\\.rs):(\\d+):(\\d+)")

    /** No real location line is longer; anything longer is data, not a frame. */
    const val MAX_LINE_CHARS = 2_000

    private class Pattern(
        val regex: Regex,
        val kind: FrameKind,
        val hint: (String) -> Boolean,
        val file: Int,
        val line: Int,
        val column: Int?,
        val klass: Int? = null,
        val method: Int? = null,
    )

    private val PATTERNS = listOf(
        Pattern(JVM, FrameKind.JVM, { "at " in it || "at\t" in it }, file = 3, line = 4, column = null, klass = 1, method = 2),
        Pattern(PYTHON, FrameKind.PYTHON, { "File \"" in it }, file = 1, line = 2, column = null),
        Pattern(GO, FrameKind.GO, { ".go:" in it }, file = 1, line = 2, column = null),
        Pattern(NODE, FrameKind.NODE, { "at " in it }, file = 1, line = 2, column = null),
        Pattern(DOTNET, FrameKind.DOTNET, { ".cs:line " in it }, file = 1, line = 2, column = null),
        Pattern(MAVEN, FrameKind.MAVEN, { it.startsWith("[ERROR] ") || it.startsWith("[WARNING] ") }, file = 1, line = 2, column = 3),
        Pattern(GRADLE, FrameKind.GRADLE, { it.startsWith("e: ") || it.startsWith("w: ") }, file = 1, line = 2, column = 3),
        Pattern(COMPILER, FrameKind.COMPILER, { ": error" in it || ": warning" in it || ": fatal" in it }, file = 1, line = 2, column = 3),
        Pattern(GO_BUILD, FrameKind.COMPILER, { ".go:" in it }, file = 1, line = 2, column = 3),
        Pattern(TSC, FrameKind.COMPILER, { "): error TS" in it }, file = 1, line = 2, column = 3),
        Pattern(RUSTC, FrameKind.COMPILER, { "--> " in it }, file = 1, line = 2, column = 3),
    )

    fun find(text: String, limit: Int = Int.MAX_VALUE): List<StackFrame> {
        val kept = ArrayList<StackFrame>()
        var lineStart = 0
        while (lineStart <= text.length && kept.size < limit) {
            val newline = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            if (newline - lineStart in 1..MAX_LINE_CHARS) {
                val line = text.substring(lineStart, newline)
                val found = ArrayList<StackFrame>()
                for (pattern in PATTERNS) {
                    if (pattern.hint(line)) collect(line, lineStart, pattern, found)
                }
                // Keep the earliest match when two patterns overlap the same characters, so a location
                // is underlined once and resolves one way.
                for (frame in found.sortedBy { it.start }) {
                    if (kept.none { it.start < frame.end && frame.start < it.end }) {
                        kept += frame
                        if (kept.size >= limit) break
                    }
                }
            }
            lineStart = newline + 1
        }
        return kept
    }

    private fun collect(line: String, offset: Int, pattern: Pattern, into: MutableList<StackFrame>) {
        for (match in pattern.regex.findAll(line)) {
            val groups = match.groupValues
            val lineNumber = groups.getOrNull(pattern.line)?.toIntOrNull() ?: continue
            val fileName = groups.getOrNull(pattern.file)?.takeIf { it.isNotBlank() } ?: continue
            into += StackFrame(
                kind = pattern.kind,
                file = fileName,
                line = lineNumber,
                column = pattern.column?.let { groups.getOrNull(it)?.toIntOrNull() },
                className = pattern.klass?.let { groups.getOrNull(it) },
                method = pattern.method?.let { groups.getOrNull(it)?.trim() },
                start = offset + match.range.first,
                end = offset + match.range.last + 1,
            )
        }
    }
}

/**
 * Which frame of a failed test's stack trace is the test's own line (§9.6).
 *
 * The first frame of a real JUnit/TestNG/kotlin.test trace is the assertion library
 * (`org.junit.jupiter.api.AssertionFailureBuilder`, `org.opentest4j...`), not the test, so "the first
 * frame" opens nothing. The test's line is the first frame whose class *is* the test class - or one
 * of its nested/lambda classes (`FooTest$Nested`, `FooTest$test$1`) - and, failing that, the first
 * frame outside the well-known test and assertion frameworks.
 */
object TestFrames {

    private val FRAMEWORK_PREFIXES = listOf(
        "org.junit.", "junit.", "org.opentest4j.", "org.assertj.", "org.hamcrest.", "org.testng.",
        "kotlin.test.", "io.kotest.", "org.spockframework.", "java.", "jdk.", "sun.", "kotlin.",
    )

    fun testFrame(testClass: String, frames: List<StackFrame>): StackFrame? {
        val jvm = frames.filter { it.kind == FrameKind.JVM && it.className != null }
        if (testClass.isNotBlank()) {
            jvm.firstOrNull { val c = it.className!!; c == testClass || c.startsWith("$testClass\$") }?.let { return it }
        }
        return jvm.firstOrNull { frame -> FRAMEWORK_PREFIXES.none { frame.className!!.startsWith(it) } }
            ?: frames.firstOrNull()
    }
}
