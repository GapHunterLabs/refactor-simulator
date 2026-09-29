package dev.gaphunter.refactorsimulator.testimpact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The message after "Will run": the reason when the run can't start, the test verdict when it can. */
class TestRunSummaryTest {

    private val test = "OrderPricingEngineTest.java"

    @Test
    fun `a run that couldn't start says so and gives the reason`() {
        val message = TestRunSummary.describe(
            listOf(TestOutcome("(Gradle run failed)", TestStatus.OTHER, "Task 'test' not found in root project")),
            test,
        )

        assertTrue(message, message.startsWith("Couldn't run $test"))
        assertTrue(message, message.contains("Task 'test' not found in root project"))
    }

    @Test
    fun `failing tests are the news even though they also fail the build`() {
        val message = TestRunSummary.describe(
            listOf(
                TestOutcome("OrderPricingEngineTest.appliesTax", TestStatus.PASS),
                TestOutcome("OrderPricingEngineTest.appliesDiscount", TestStatus.FAIL, "expected 513.00 but was 540.00"),
                TestOutcome("(Maven run failed)", TestStatus.OTHER, "BUILD FAILURE"),
            ),
            test,
        )

        assertTrue(message, message.startsWith("1 of 2 tests in $test didn't pass"))
        assertTrue(message, message.contains("✗ OrderPricingEngineTest.appliesDiscount\nexpected 513.00 but was 540.00"))
    }

    @Test
    fun `all passing`() {
        val outcomes = listOf("a", "b", "c").map { TestOutcome("OrderPricingEngineTest.$it", TestStatus.PASS) }

        assertEquals("✓ 3 tests in $test passed against the simulated change.", TestRunSummary.describe(outcomes, test))
    }

    @Test
    fun `nothing ran, and nothing could be prepared`() {
        assertEquals("No test in $test ran. Does it contain any test methods?", TestRunSummary.describe(emptyList(), test))
        assertEquals("Could not prepare the isolated sandbox for this run.", TestRunSummary.describe(null, test))
    }
}
