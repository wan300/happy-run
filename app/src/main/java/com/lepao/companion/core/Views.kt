package com.lepao.companion.core

/** Result of the read-only pre-run inspection shown on the run card. */
data class ReadinessView(
    val ok: Boolean,
    val blockers: List<String>,
    val warnings: List<String>,
    val rule: TimeRule?,
    val plan: PlannedRun?,
    val rules: RunRules?,
) {
    companion object {
        fun blocked(vararg reasons: String) = ReadinessView(
            ok = false,
            blockers = reasons.toList(),
            warnings = emptyList(),
            rule = null,
            plan = null,
            rules = null,
        )
    }
}

/** Result of a user-visible action such as starting or re-verifying a run. */
data class OperationResult(
    val ok: Boolean,
    val message: String,
    val state: RunState? = null,
)
