package dev.gaphunter.refactorsimulator.testimpact

/**
 * Pure data model, decoupled from the real execution mechanism (Gradle
 * Tooling API) so the report-rendering side can be tested without
 * actually running Gradle.
 */
data class TestOutcome(
    val displayName: String,
    val status: TestStatus,
    val truncatedOutput: String? = null,
)

enum class TestStatus {
    PASS,
    FAIL,
    OTHER,
}
