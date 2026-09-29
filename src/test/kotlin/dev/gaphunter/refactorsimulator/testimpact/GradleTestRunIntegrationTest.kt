package dev.gaphunter.refactorsimulator.testimpact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Runs REAL Gradle (the demo's own wrapper) on a mirror of the demo project -- two Gradle projects, Java and Kotlin,
 * which IntelliJ imports as one module per source set: exactly the shape the first implementation broke on.
 * Skipped unless `REFACTOR_SIMULATOR_GRADLE_IT` is set, so a normal `./gradlew test` never starts a Gradle daemon or
 * downloads anything.
 */
class GradleTestRunIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val demo = File("demo").toPath()
    private val engine = "order-core/src/main/java/com/acmecorp/orders/core/OrderPricingEngine.java"
    private val engineTest = "order-core/src/test/java/com/acmecorp/orders/core/OrderPricingEngineTest.java"
    private val testClass = "com.acmecorp.orders.core.OrderPricingEngineTest"

    private fun needGradle() =
        assumeTrue("set REFACTOR_SIMULATOR_GRADLE_IT=1 to run against real Gradle", System.getenv("REFACTOR_SIMULATOR_GRADLE_IT") != null)

    private fun renamed(relative: String) =
        demo.resolve(relative).toFile().readText().replace("calcTotalWithTaxAndDiscount", "totalDue")

    @Test
    fun `a rename applied to the declaration and its callers passes the related tests`() {
        needGradle()
        val mirror = tmp.newFolder("mirror").toPath()
        GradleSandbox.sync(demo, mirror, mapOf(engine to renamed(engine), engineTest to renamed(engineTest)))

        val outcomes = GradleTestRun.run(mirror, engineTest, testClass)

        assertTrue(outcomes.toString(), outcomes.isNotEmpty() && outcomes.none(TestRunSummary::isRunFailure))
        assertTrue(outcomes.toString(), outcomes.all { it.status == TestStatus.PASS })
        assertTrue(outcomes.toString(), outcomes.all { it.displayName.startsWith("OrderPricingEngineTest.") })
    }

    @Test
    fun `a rename that misses its callers is reported with the compiler's reason`() {
        needGradle()
        val mirror = tmp.newFolder("mirror").toPath()
        GradleSandbox.sync(demo, mirror, mapOf(engine to renamed(engine)))

        val outcomes = GradleTestRun.run(mirror, engineTest, testClass)

        assertEquals(outcomes.toString(), 1, outcomes.size)
        assertTrue(outcomes.toString(), TestRunSummary.isRunFailure(outcomes.single()))
        assertTrue(outcomes.toString(), outcomes.single().truncatedOutput!!.contains("calcTotalWithTaxAndDiscount"))
    }
}
