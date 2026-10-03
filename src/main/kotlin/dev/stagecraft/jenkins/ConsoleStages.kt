package dev.stagecraft.jenkins

import dev.stagecraft.model.StageRef
import dev.stagecraft.model.StageSource
import kotlinx.serialization.json.JsonObject

/**
 * Stagecraft: segment a Jenkins console log into stages using only the console text.
 *
 * Jenkins logs interleaved stage output with `[Pipeline]`-prefixed control lines
 * (``[Pipeline] stage``, ``[Pipeline] { (Name) }``, ``[Pipeline] parallel`` ...). This module parses
 * those lines with a small stack machine and yields stages with line ranges, nesting info, and
 * diagnostics - deliberately without any extra HTTP calls.
 *
 * Two families of logs produce "no stages" outcomes, and we keep them distinct:
 *
 *  * a pipeline log where no ``stage`` block was ever opened - `fallbackMode`
 *    ``pipeline-without-stages``;
 *  * a log from a non-pipeline (e.g. free-style) job - `fallbackMode` ``plain-log``.
 *
 * Line numbers are 1-based and inclusive on both ends, matching what the UI will show.
 *
 * This is the direct port of `tools/parse_stages.py`; the two must stay in lockstep.
 */
object ConsoleStages {

    // ---------------------------------------------------------------- fixtures regexes

    private val ANSI = Regex("\u001B\\[[0-9;]*m")
    private val NAMED_OPEN = Regex("^\\[Pipeline\\] \\{\\s*\\((.*)\\)\\s*$")
    private val BLOCK_OPEN = Regex("^\\[Pipeline\\] \\{$")
    private val BLOCK_CLOSE = Regex("^\\[Pipeline\\] \\}$")
    private val STAGE_CLOSE = Regex("^\\[Pipeline\\] // stage$")
    private val PARALLEL_OPEN = Regex("^\\[Pipeline\\] parallel$")
    private val PARALLEL_CLOSE = Regex("^\\[Pipeline\\] // parallel$")
    private val END = Regex("^\\[Pipeline\\] End of Pipeline$")
    private val SKIPPED = Regex("^Stage \"(.+)\" skipped due to (.+)$")
    private val FINISHED = Regex("^Finished: ([A-Z_]+)$")
    private val ERROR_LINE = Regex(
        "^(?:ERROR: |FATAL: |\\[ERROR\\] |FAILURE: |BUILD FAILED\\b|Exception in thread )" +
            "|^Build step '.+' marked build as failure$",
    )

    /**
     * The one error predicate, shared by the parser and the log panel so both agree on what "the
     * first error" is. The log view scrolls to the line this returns true for, and the parser
     * infers the failed stage from the same line - two predicates would drift.
     */
    fun isErrorLine(line: String): Boolean = ERROR_LINE.containsMatchIn(line)

    private const val MARKER = "[Pipeline] "
    private const val BRANCH_PREFIX = "Branch: "
    private const val SYNTHETIC_PREFIX = "Declarative: "

    /** The synthetic stage that holds the `always { }` block of declarative pipelines. */
    const val POST_ACTIONS = "Declarative: Post Actions"

    // ---------------------------------------------------------------- public shapes

    /** One parsed stage. `firstLine`/`lastLine` are 1-based and inclusive. */
    data class ConsoleStage(
        val name: String,
        val firstLine: Int,
        val lastLine: Int,
        val parent: String?,
        val depth: Int,
        val synthetic: Boolean,
        val interleaved: Boolean,
        val skipped: String?,
        val open: Boolean,
        val branch: String?,
        val laneBinding: String?,
        val parentUncertain: Boolean,
    ) {
        val logLines: Int get() = lastLine - firstLine + 1
    }

    /** A closed `parallel { }` region and the branch labels we saw opening it. */
    data class ParallelRegion(val firstLine: Int, val lastLine: Int, val branches: List<String>)

    /** Why we believe a build failed, and how confident that guess is. */
    data class FailedStage(val name: String, val basis: String)

    data class Diagnostics(
        val stackLeftAtEnd: Int,
        val endMarkerSeen: Boolean,
        val isPipelineLog: Boolean,
        val fallbackMode: String,
        val result: String?,
        val firstErrorLine: Int?,
        val firstErrorStage: String?,
        val inferredFailedStage: FailedStage?,
        val parallelRegions: List<ParallelRegion>,
        val unattributedStages: List<String>,
        val strayStageClose: Int,
    )

    data class StageParse(val stages: List<ConsoleStage>, val diagnostics: Diagnostics)

    // ---------------------------------------------------------------- parser

    fun parse(text: String): StageParse = parse(splitLines(text).asSequence())

    /**
     * Parse a console streamed line by line. The parser keeps only its frame stack and the stages,
     * so a log of any size parses in bounded memory - the stage view must not fail on a large log
     * just because the log panel can hold only its head and tail.
     */
    fun parse(reader: java.io.Reader): StageParse =
        java.io.BufferedReader(reader).use { parse(it.lineSequence()) }

    fun parse(lines: Sequence<String>): StageParse {
        val stages = mutableListOf<MutableStage>()
        val stack = ArrayDeque<Frame>()
        val regions = ArrayDeque<Region>()
        val closedRegions = mutableListOf<Region>()

        var isPipeline = false
        var endLine: Int? = null
        var result: String? = null
        var firstError: Int? = null
        var strayStageClose = 0
        var lastPopped: String? = null

        var lineCount = 0
        for ((index, raw) in lines.withIndex()) {
            val i = index + 1
            lineCount = i
            val line = ANSI.replace(raw, "").removeSuffix("\r")

            if (line.startsWith(MARKER)) isPipeline = true
            if (firstError == null && isErrorLine(line)) firstError = i

            val namedOpen = NAMED_OPEN.matchEntire(line)
            if (namedOpen != null) {
                val name = namedOpen.groupValues[1]
                if (name.startsWith(BRANCH_PREFIX)) {
                    val label = name.removePrefix(BRANCH_PREFIX)
                    stack.addLast(BranchFrame(label, i))
                    if (regions.isNotEmpty()) regions.last().branches.add(label)
                    continue
                }
                val (owner, placement) = placeStage(stack, regions, name)
                val stage = MutableStage(
                    name = name,
                    first = i,
                    last = null,
                    parentName = owner?.name,
                    depth = (owner?.depth ?: -1) + 1,
                    synthetic = name.startsWith(SYNTHETIC_PREFIX),
                    interleaved = regions.isNotEmpty(),
                )
                stage.branch = placement.branch
                stage.laneBinding = placement.laneBinding
                stage.parentUncertain = placement.parentUncertain
                stages.add(stage)
                stack.addLast(StageFrame(stage))
                if (regions.isNotEmpty()) regions.last().stages.add(stage)
                continue
            }

            if (BLOCK_OPEN.matchEntire(line) != null) {
                stack.addLast(BlockFrame(i))
                continue
            }

            val blockClose = BLOCK_CLOSE.matchEntire(line)
            if (blockClose != null) {
                if (stack.isNotEmpty()) {
                    val frame = stack.removeLast()
                    lastPopped = frame.kind
                    if (frame is StageFrame && regions.isEmpty()) frame.stage.last = i
                }
                continue
            }

            if (STAGE_CLOSE.matchEntire(line) != null) {
                if (lastPopped != "stage" && regions.isEmpty()) strayStageClose++
                lastPopped = null
                continue
            }

            if (PARALLEL_OPEN.matchEntire(line) != null) {
                regions.addLast(Region(first = i, base = stack.size))
                continue
            }

            if (PARALLEL_CLOSE.matchEntire(line) != null && regions.isNotEmpty()) {
                val region = regions.removeLast()
                while (stack.size > region.base) stack.removeLast()
                for (stage in region.stages) {
                    stage.first = region.first
                    stage.last = i
                }
                region.lastLine = i
                closedRegions.add(region)
                continue
            }

            val skipped = SKIPPED.matchEntire(line)
            if (skipped != null) {
                val name = skipped.groupValues[1]
                for (stage in stages.asReversed()) {
                    if (stage.name == name) {
                        stage.skipped = skipped.groupValues[2]
                        break
                    }
                }
                continue
            }

            if (END.matchEntire(line) != null) {
                endLine = i
                continue
            }

            val finished = FINISHED.matchEntire(line)
            if (finished != null) result = finished.groupValues[1]
        }

        val totalLines = lineCount
        for (stage in stages) {
            if (stage.last == null) {
                stage.last = totalLines
                stage.open = true
            }
        }
        stages.sortBy { it.first }

        val diagnostics = Diagnostics(
            stackLeftAtEnd = stack.size,
            endMarkerSeen = endLine != null,
            isPipelineLog = isPipeline,
            fallbackMode = when {
                stages.isNotEmpty() -> "stages"
                isPipeline -> "pipeline-without-stages"
                else -> "plain-log"
            },
            result = result,
            firstErrorLine = firstError,
            firstErrorStage = innermostStageAt(stages, firstError),
            inferredFailedStage = inferFailedStage(stages, result, firstError),
            parallelRegions = closedRegions.map { ParallelRegion(it.first, it.lastLine!!, it.branches.toList()) },
            unattributedStages = stages.filter { it.parentUncertain }.map { it.name },
            strayStageClose = strayStageClose,
        )
        return StageParse(stages.map { it.toStage() }, diagnostics)
    }

    // ---------------------------------------------------------------- mappers (§9.3 fallback chain)

    /** wfapi `describe` → the canonical stage list used by the UI (chain step 1). */
    fun fromWfapi(describe: JsonObject): List<StageRef> =
        describe.objects("stages").map { stage ->
            StageRef(
                name = stage.str("name") ?: "",
                status = stage.str("status"),
                durationMillis = stage.long("durationMillis"),
                errorMessage = stage.obj("error")?.str("message"),
                firstLine = null,
                lastLine = null,
                source = StageSource.WFAPI,
            )
        }

    /** Console parse → the canonical stage list (chain steps 2-4). */
    fun fromConsole(parse: StageParse): List<StageRef> {
        val d = parse.diagnostics
        return when (d.fallbackMode) {
            "stages" -> parse.stages.map { stage ->
                StageRef(
                    name = stage.name,
                    status = null,
                    durationMillis = null,
                    errorMessage = null,
                    firstLine = stage.firstLine,
                    lastLine = stage.lastLine,
                    synthetic = stage.synthetic,
                    interleaved = stage.interleaved,
                    skippedReason = stage.skipped,
                    source = StageSource.CONSOLE,
                )
            }
            "pipeline-without-stages" -> listOf(StageRef(name = "Pipeline", source = StageSource.PIPELINE))
            else -> listOf(StageRef(name = "Build", source = StageSource.BUILD))
        }
    }

    // ---------------------------------------------------------------- internals

    private class MutableStage(
        val name: String,
        var first: Int,
        var last: Int?,
        val parentName: String?,
        val depth: Int,
        val synthetic: Boolean,
        val interleaved: Boolean,
    ) {
        var skipped: String? = null
        var branch: String? = null
        var laneBinding: String? = null
        var parentUncertain: Boolean = false
        var open: Boolean = false

        fun toStage() = ConsoleStage(
            name = name,
            firstLine = first,
            lastLine = last!!,
            parent = parentName,
            depth = depth,
            synthetic = synthetic,
            interleaved = interleaved,
            skipped = skipped,
            open = open,
            branch = branch,
            laneBinding = laneBinding,
            parentUncertain = parentUncertain,
        )
    }

    private sealed interface Frame {
        val kind: String
    }

    private class StageFrame(val stage: MutableStage) : Frame {
        override val kind: String get() = "stage"
    }

    private class BranchFrame(val label: String, val first: Int) : Frame {
        override val kind: String get() = "branch"
    }

    private class BlockFrame(val first: Int) : Frame {
        override val kind: String get() = "block"
    }

    private class Region(val first: Int, val base: Int) {
        val stages = mutableListOf<MutableStage>()
        val branches = mutableListOf<String>()
        val claimed = mutableSetOf<String>()
        var lastLine: Int? = null
    }

    private data class Placement(val branch: String?, val laneBinding: String?, val parentUncertain: Boolean)

    /** Python `splitlines()` semantics for LF / CR / CRLF, minus the trailing-empty-line quirk. */
    private fun splitLines(text: String): List<String> = buildList {
        var start = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\n' -> {
                    add(text.substring(start, i)); i++; start = i
                }
                '\r' -> {
                    add(text.substring(start, i)); i++
                    if (i < text.length && text[i] == '\n') i++
                    start = i
                }
                else -> i++
            }
        }
        if (start < text.length) add(text.substring(start))
    }

    private fun nearestStage(frames: List<Frame>): MutableStage? {
        for (i in frames.indices.reversed()) {
            val frame = frames[i]
            if (frame is StageFrame) return frame.stage
        }
        return null
    }

    /**
     * Where does a stage with no enclosing block belong? Order matters (§9.3):
     *  1. the stage claims its own name if a branch with that name is still unclaimed
     *     (binds by name, certain);
     *  2. otherwise it takes the first unclaimed branch label if all open lanes already have
     *     branches (positional binding, uncertain);
     *  3. otherwise it nest inside the only open lane (certain);
     *  4. otherwise it is the parallel stage itself, or a sibling we cannot pin down (uncertain).
     */
    private fun placeStage(stack: List<Frame>, regions: List<Region>, name: String): Pair<MutableStage?, Placement> {
        if (regions.isEmpty()) {
            return nearestStage(stack) to Placement(null, null, false)
        }
        val region = regions.last()
        val owner = nearestStage(stack.take(region.base))
        val unclaimed = region.branches.filter { it !in region.claimed }
        val openLanes = stack.drop(region.base).filterIsInstance<StageFrame>().map { it.stage }
        if (name in unclaimed) {
            region.claimed.add(name)
            return owner to Placement(name, "name", false)
        }
        if (unclaimed.isNotEmpty() && openLanes.all { it.branch != null }) {
            val label = unclaimed.first()
            region.claimed.add(label)
            return owner to Placement(label, "positional", true)
        }
        if (openLanes.size == 1 && openLanes[0].branch != null) {
            return openLanes[0] to Placement(openLanes[0].branch, null, false)
        }
        return owner to Placement(null, null, openLanes.isNotEmpty() || region.branches.isNotEmpty())
    }

    private fun innermostStageAt(stages: List<MutableStage>, line: Int?): String? {
        if (line == null) return null
        var best: MutableStage? = null
        for (stage in stages) {
            val last = stage.last ?: continue
            if (!stage.interleaved && stage.first <= line && last >= line) {
                if (best == null || stage.depth > best!!.depth) best = stage
            }
        }
        return best?.name
    }

    private fun inferFailedStage(
        stages: List<MutableStage>,
        result: String?,
        firstError: Int?,
    ): FailedStage? {
        if (result != "FAILURE") return null
        val inside = innermostStageAt(stages, firstError)
        if (inside != null && inside != POST_ACTIONS) {
            return FailedStage(inside, "first-error-inside-stage")
        }
        val candidates = stages.filter { !it.synthetic && it.skipped == null }
        if (candidates.isEmpty()) return null
        var last = candidates.first()
        for (stage in candidates) if (stage.first > last.first) last = stage
        var basis = "last-executed-stage"
        if (last.interleaved) basis += ", parallel-ambiguous"
        return FailedStage(last.name, basis)
    }
}
