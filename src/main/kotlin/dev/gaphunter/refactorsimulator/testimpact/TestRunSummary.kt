package dev.gaphunter.refactorsimulator.testimpact

/**
 * The message shown after "Will run". Before this, a run that failed to even start was reported as "couldn't match
 * one back to <file>" with its status only: the reason Gradle gave was dropped. Now a run that couldn't start says so
 * with its reason, and a run that did start reports how many tests passed or which ones failed.
 */
object TestRunSummary {

    /** An outcome that isn't a test but the run itself ("(Gradle run failed)", "(Maven run timed out)"...). */
    fun isRunFailure(outcome: TestOutcome) = outcome.displayName.startsWith("(")

    fun describe(outcomes: List<TestOutcome>?, testName: String): String {
        if (outcomes == null) return "Could not prepare the isolated sandbox for this run."
        val tests = outcomes.filterNot(::isRunFailure)
        // Failing tests also fail the build: then the tests are the news, not the build.
        val runFailure = outcomes.firstOrNull(::isRunFailure)
        if (runFailure != null && tests.none { it.status == TestStatus.FAIL }) {
            val reason = runFailure.truncatedOutput ?: "No reason was reported."
            return "Couldn't run $testName in the isolated copy ${runFailure.displayName}.\n\n$reason"
        }
        if (tests.isEmpty()) return "No test in $testName ran. Does it contain any test methods?"
        val failed = tests.filter { it.status != TestStatus.PASS }
        if (failed.isEmpty()) {
            return "✓ ${plural(tests.size)} in $testName passed against the simulated change."
        }
        val details = failed.joinToString("\n\n") { outcome ->
            val mark = if (outcome.status == TestStatus.FAIL) "✗" else "⚠"
            "$mark ${outcome.displayName}" + (outcome.truncatedOutput?.let { "\n$it" } ?: "")
        }
        return "${failed.size} of ${plural(tests.size)} in $testName didn't pass against the simulated change.\n\n$details"
    }

    private fun plural(n: Int) = if (n == 1) "1 test" else "$n tests"
}
