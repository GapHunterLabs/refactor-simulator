package dev.gaphunter.refactorsimulator.refactor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.gaphunter.refactorsimulator.sandbox.SandboxSession

/**
 * Real PSI, real text manipulation -- confirms the exact resulting text
 * [RefactorSimulationRunner.simulateExtractVariable] computes, not just
 * that it "does something." [ExtractVariableTargetTest] already covers
 * selection-to-expression resolution; this covers what happens once a
 * target is resolved.
 */
class RefactorSimulationRunnerExtractVariableTest : BasePlatformTestCase() {

    private fun simulate(fileName: String, source: String, snippet: String, variableName: String = "extracted"): SimulationResult? {
        val file = myFixture.configureByText(fileName, source)
        val start = file.text.indexOf(snippet)
        check(start >= 0) { "snippet not found in source: $snippet" }
        val target = ExtractVariableTarget.resolve(file, start, start + snippet.length)
            ?: error("no expression resolved for snippet: $snippet")
        val session = SandboxSession.create(project, target)
        return RefactorSimulationRunner.simulateExtractVariable(session, variableName)
    }

    fun testJavaExtractionInsertsVarDeclarationBeforeTheStatement() {
        val result = simulate(
            "Acme.java",
            """
            class Acme {
                void total(int a, int b) {
                    System.out.println(a + b);
                }
            }
            """.trimIndent(),
            "a + b",
        )

        assertNotNull(result)
        assertEquals(RefactorKind.EXTRACT_VARIABLE, result!!.kind)
        assertEquals(1, result.affectedFiles.size)
        val simulated = result.affectedFiles.single().simulatedText
        assertEquals(
            """
            class Acme {
                void total(int a, int b) {
                    var extracted = a + b;
                    System.out.println(extracted);
                }
            }
            """.trimIndent(),
            simulated,
        )
    }

    fun testKotlinExtractionUsesValAndNoSemicolon() {
        val result = simulate(
            "Acme.kt",
            """
            fun total(a: Int, b: Int) {
                println(a + b)
            }
            """.trimIndent(),
            "a + b",
        )

        assertNotNull(result)
        val simulated = result!!.affectedFiles.single().simulatedText
        assertEquals(
            """
            fun total(a: Int, b: Int) {
                val extracted = a + b
                println(extracted)
            }
            """.trimIndent(),
            simulated,
        )
    }

    fun testOnlyTheSelectedOccurrenceIsReplaced() {
        val result = simulate(
            "Acme.java",
            """
            class Acme {
                void total(int a, int b) {
                    System.out.println(a + b);
                    System.out.println(a + b);
                }
            }
            """.trimIndent(),
            "a + b",
        )

        val simulated = result!!.affectedFiles.single().simulatedText
        // "extracted" appears twice: the new declaration, and the one replaced use.
        assertEquals(2, Regex("extracted").findAll(simulated).count())
        // "a + b" also appears twice: once as the declaration's own initializer
        // text, once as the second, completely untouched println's argument --
        // never three, which would mean the second occurrence got replaced too.
        assertEquals(2, Regex(Regex.escape("a + b")).findAll(simulated).count())
        assertTrue(simulated.contains("System.out.println(extracted);"))
        assertTrue(simulated.contains("System.out.println(a + b);"))
    }

    fun testOriginalNameCarriesTheExtractedExpressionText() {
        val result = simulate(
            "Acme.java",
            """
            class Acme {
                void total(int a, int b) {
                    System.out.println(a + b);
                }
            }
            """.trimIndent(),
            "a + b",
        )

        assertEquals("a + b", result!!.originalName)
        assertEquals("extracted", result.newName)
    }
}
