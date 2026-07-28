package dev.gaphunter.refactorsimulator.impact

/**
 * Deterministic checks only -- no confidence score, no AI, no
 * probabilistic "risk percentage" (Principle 2 of the product spec: "No
 * AI. No probabilistic scores. No heuristics pretending to be
 * certainty."). Every check here maps to a real, verifiable platform
 * fact (PSI parsed, imports resolved, RenameProcessor's own conflict
 * list), never a guess.
 */
data class ValidationReport(val checks: List<ValidationCheck>) {
    val hasErrors: Boolean get() = checks.any { it.status == CheckStatus.ERROR }
    val hasWarnings: Boolean get() = checks.any { it.status == CheckStatus.WARNING }
}

data class ValidationCheck(
    val label: String,
    val status: CheckStatus,
    val detail: String? = null,
)

enum class CheckStatus {
    OK,
    WARNING,
    ERROR,
}
