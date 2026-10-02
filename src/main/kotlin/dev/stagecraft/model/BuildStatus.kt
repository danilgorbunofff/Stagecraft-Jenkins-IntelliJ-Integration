package dev.stagecraft.model

/**
 * Jenkins reports job state as a colour string and build state as a `result` string, and the two
 * disagree about names (`grey` vs `NOT_BUILT`, `blue` vs `SUCCESS`). Both are folded into this one
 * enum so that no caller ever compares raw strings, and so a running build is one case rather than
 * a `result == null` special case in every branch of the UI.
 */
enum class BuildStatus {
    RUNNING,
    SUCCESS,
    UNSTABLE,
    FAILURE,
    ABORTED,
    NOT_BUILT,
    DISABLED,
    UNKNOWN,
    ;

    companion object {
        /** From a job's `color` field. Jenkins appends `_anime` to a job that is building right now. */
        fun fromColour(colour: String?): BuildStatus {
            val value = colour?.trim()?.lowercase().orEmpty()
            if (value.isEmpty()) return UNKNOWN
            if (value.endsWith("_anime")) return RUNNING
            return when (value) {
                "blue" -> SUCCESS
                "red" -> FAILURE
                "yellow" -> UNSTABLE
                "grey", "notbuilt", "nobuilt" -> NOT_BUILT
                "aborted" -> ABORTED
                "disabled" -> DISABLED
                else -> UNKNOWN
            }
        }

        /**
         * From a build's `result` field and its `building` flag. A build that has not finished has
         * `result: null` (Day-0 re-check R9), which is not the same thing as `NOT_BUILT`.
         */
        fun fromResult(result: String?, building: Boolean): BuildStatus {
            if (building) return RUNNING
            return when (result?.trim()?.uppercase()) {
                "SUCCESS" -> SUCCESS
                "UNSTABLE" -> UNSTABLE
                "FAILURE" -> FAILURE
                "ABORTED" -> ABORTED
                "NOT_BUILT" -> NOT_BUILT
                else -> UNKNOWN
            }
        }
    }
}
