package dev.gaphunter.refactorsimulator.refactor

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.gaphunter.refactorsimulator.sandbox.SandboxSession

/**
 * The rename preview must show the code as it would be after the rename:
 * the declaration renamed in its own file, and every call site renamed in
 * every other file (Java and Kotlin callers alike). The diff and the
 * isolated test run both work from these texts.
 */
class RefactorSimulationRunnerRenameTest : BasePlatformTestCase() {

    private fun simulateRenameOfCalcTotal(newName: String = "totalDue"): SimulationResult {
        myFixture.addFileToProject(
            "com/acme/Engine.java",
            """
            package com.acme;

            public class Engine {
                public int calcTotal(int a) {
                    return a * 2;
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/acme/Service.java",
            """
            package com.acme;

            class Service {
                int twice(Engine engine) {
                    return engine.calcTotal(1) + engine.calcTotal(2);
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/acme/EngineTest.java",
            """
            package com.acme;

            class EngineTest {
                void checksTheTotal() {
                    Engine engine = new Engine();
                    if (engine.calcTotal(3) != 6) throw new AssertionError();
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/acme/Checkout.kt",
            """
            package com.acme

            class Checkout(private val engine: Engine) {
                fun total(): Int = engine.calcTotal(4)
            }
            """.trimIndent(),
        )
        val engineClass = JavaPsiFacade.getInstance(project).findClass("com.acme.Engine", GlobalSearchScope.allScope(project))
            ?: error("Engine not found")
        val method: PsiMethod = engineClass.findMethodsByName("calcTotal", false).single()
        val session = SandboxSession.create(project, method)
        return RefactorSimulationRunner.simulateRename(session, newName) ?: error("simulation declined (dumb mode?)")
    }

    private fun SimulationResult.fileNamed(name: String): AffectedFile =
        affectedFiles.singleOrNull { it.filePath.substringAfterLast('/') == name }
            ?: error("$name is not among the affected files: ${affectedFiles.map { it.filePath.substringAfterLast('/') }}")

    fun testTheDeclarationItselfIsRenamedInThePreview() {
        val engine = simulateRenameOfCalcTotal().fileNamed("Engine.java")

        assertTrue(engine.simulatedText, engine.simulatedText.contains("public int totalDue(int a)"))
        assertFalse(engine.simulatedText, engine.simulatedText.contains("calcTotal"))
    }

    fun testEveryCallSiteIsRenamedInEveryFile() {
        val result = simulateRenameOfCalcTotal()

        for (name in listOf("Service.java", "EngineTest.java", "Checkout.kt")) {
            val file = result.fileNamed(name)
            assertFalse("$name still calls calcTotal:\n${file.simulatedText}", file.simulatedText.contains("calcTotal"))
            assertTrue("$name doesn't call totalDue:\n${file.simulatedText}", file.simulatedText.contains("totalDue("))
        }
    }

    fun testNoAffectedFileIsShownUnchanged() {
        val result = simulateRenameOfCalcTotal()

        for (file in result.affectedFiles) {
            assertFalse("${file.filePath} is listed as affected but its preview is unchanged", file.simulatedText == file.originalText)
        }
    }

    fun testTheShippedDemoProjectRenamesEveryReference() {
        val demo = java.io.File("demo")
        val sources = mapOf(
            "com/acmecorp/orders/core/OrderPricingEngine.java" to "order-core/src/main/java/com/acmecorp/orders/core/OrderPricingEngine.java",
            "com/acmecorp/orders/core/OrderPricingEngineTest.java" to "order-core/src/test/java/com/acmecorp/orders/core/OrderPricingEngineTest.java",
            "com/acmecorp/orders/api/OrderCheckoutService.kt" to "order-api/src/main/kotlin/com/acmecorp/orders/api/OrderCheckoutService.kt",
        )
        for ((target, source) in sources) {
            // JUnit isn't on this fixture's classpath: its imports stay unresolved, which doesn't matter for the
            // references being renamed (they resolve to OrderPricingEngine, which is here).
            myFixture.addFileToProject(target, demo.resolve(source).readText())
        }
        val engineClass = JavaPsiFacade.getInstance(project)
            .findClass("com.acmecorp.orders.core.OrderPricingEngine", GlobalSearchScope.allScope(project))
            ?: error("OrderPricingEngine not found")
        val method = engineClass.findMethodsByName("calcTotalWithTaxAndDiscount", false).single()
        val result = RefactorSimulationRunner.simulateRename(SandboxSession.create(project, method), "totalDue")
            ?: error("simulation declined")

        for (file in result.affectedFiles) {
            val name = file.filePath.substringAfterLast('/')
            assertFalse("$name still has the old name:\n${file.simulatedText}", file.simulatedText.contains("calcTotalWithTaxAndDiscount"))
        }
        assertEquals(3, result.affectedFiles.sumOf { it.referenceCount })
    }

    fun testReferenceCountsOnlyCallSitesNotTheDeclaration() {
        val result = simulateRenameOfCalcTotal()

        assertEquals(4, result.totalReferenceCount)
        assertEquals(0, result.fileNamed("Engine.java").referenceCount)
        assertEquals(1, result.fileNamed("Engine.java").declarationCount)
        assertEquals("4 call sites + the declaration", 5, result.totalChangeCount)
        assertFalse(result.conflicts.toString(), result.hasConflicts)
    }

    fun testARangeWhoseTextIsNotTheOldNameIsReportedNotGuessed() {
        val text = "engine.calcTotal(1); engine.total;"
        val good = com.intellij.openapi.util.TextRange(7, 16)     // "calcTotal"
        val other = com.intellij.openapi.util.TextRange(28, 33)   // "total": a property-style access, not the name

        val (renamed, skipped) = RefactorSimulationRunner.applyRenameToText(text, listOf(good, other), "calcTotal", "totalDue")

        assertEquals("engine.totalDue(1); engine.total;", renamed)
        assertEquals(1, skipped)
    }
}
