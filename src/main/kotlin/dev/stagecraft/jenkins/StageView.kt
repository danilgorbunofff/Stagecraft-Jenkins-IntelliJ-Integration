package dev.stagecraft.jenkins

import dev.stagecraft.model.StageRef
import dev.stagecraft.model.StageSource
import kotlinx.serialization.json.JsonObject

/**
 * A build's stages, however they were obtained (§9.3 fallback chain), with the failed stage already
 * chosen so the UI can pre-select it.
 *
 * [failedStageInferred] is the honesty flag: a wfapi status is a fact, an inference from the console
 * is not, and the panel shows it as inferred rather than presenting a guess as the truth.
 */
data class StageView(
    val stages: List<StageRef>,
    val failedStage: String?,
    val failedStageInferred: Boolean,
    val source: StageSource,
    /** A caveat the panel must show, e.g. parallel output that cannot be split without stage view. */
    val note: String?,
) {

    val parallelInterleaved: Boolean get() = stages.any { it.interleaved }

    companion object {

        /** The wfapi path of the chain: real statuses and timings. Null when it names no stages. */
        fun fromWfapi(describe: JsonObject): StageView? {
            val stages = ConsoleStages.fromWfapi(describe)
            if (stages.isEmpty()) return null
            return StageView(
                stages = stages,
                // Stage view lists parallel lanes flat after their parent, and a failed lane fails the
                // parent too: the last failed entry is the innermost one, which is where to look.
                failedStage = stages.lastOrNull { it.status.equals("FAILED", ignoreCase = true) }?.name,
                failedStageInferred = false,
                source = StageSource.WFAPI,
                note = null,
            )
        }

        /** The console path of the chain: exact boundaries, no status, no invented timings. */
        fun fromConsole(parse: ConsoleStages.StageParse): StageView {
            val stages = ConsoleStages.fromConsole(parse)
            val source = when (parse.diagnostics.fallbackMode) {
                "stages" -> StageSource.CONSOLE
                "pipeline-without-stages" -> StageSource.PIPELINE
                else -> StageSource.BUILD
            }
            val inferred = parse.diagnostics.inferredFailedStage?.name
            val note = when {
                stages.any { it.interleaved } -> "parallel output, not separable without stage view"
                source == StageSource.CONSOLE && stages.any { it.durationMillis == null } ->
                    "timings unavailable without stage view"
                else -> null
            }
            return StageView(
                stages = stages,
                failedStage = inferred,
                failedStageInferred = inferred != null,
                source = source,
                note = note,
            )
        }
    }
}
