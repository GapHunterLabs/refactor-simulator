package dev.gaphunter.refactorsimulator.refactor

import com.intellij.psi.PsiExpression
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Real PSI (Java and Kotlin), not a hand-built tree -- confirms
 * [ExtractVariableTarget] resolves an editor selection to the exact
 * expression it covers, and that it correctly refuses a selection that
 * doesn't line up with one, rather than guessing a bigger enclosing
 * expression.
 */
class ExtractVariableTargetTest : BasePlatformTestCase() {

    fun testResolvesAJavaBinaryExpressionSelectedExactly() {
        val file = myFixture.configureByText(
            "Acme.java",
            """
            class Acme {
                int total(int a, int b) {
                    return a + b;
                }
            }
            """.trimIndent(),
        )
        val start = file.text.indexOf("a + b")
        val end = start + "a + b".length

        val target = ExtractVariableTarget.resolve(file, start, end)
        assertTrue(target is PsiExpression)
        assertEquals("a + b", target!!.text)
    }

    fun testTrimsLeadingAndTrailingWhitespaceFromTheSelection() {
        val file = myFixture.configureByText(
            "Acme.java",
            """
            class Acme {
                int total(int a, int b) {
                    int sum = a + b ;
                    return sum;
                }
            }
            """.trimIndent(),
        )
        val innerStart = file.text.indexOf("a + b")
        // One char before "a" is a real space (after "= "); "a + b " (with the
        // deliberate extra space before ";") gives a real trailing space too --
        // both ends exercise actual whitespace trimming, not an adjacent token.
        val start = innerStart - 1
        val end = innerStart + "a + b ".length

        val target = ExtractVariableTarget.resolve(file, start, end)
        assertEquals("a + b", target?.text)
    }

    fun testReturnsNullWhenTheSelectionDoesNotLineUpWithAnyExpression() {
        val file = myFixture.configureByText(
            "Acme.java",
            """
            class Acme {
                int total(int a, int b) {
                    return a + b;
                }
            }
            """.trimIndent(),
        )
        // "+ b" alone is not a complete expression.
        val start = file.text.indexOf("+ b")
        val end = start + "+ b".length

        assertNull(ExtractVariableTarget.resolve(file, start, end))
    }

    fun testResolvesAKotlinBinaryExpressionSelectedExactly() {
        val file = myFixture.configureByText(
            "Acme.kt",
            """
            fun total(a: Int, b: Int): Int {
                return a + b
            }
            """.trimIndent(),
        )
        val start = file.text.indexOf("a + b")
        val end = start + "a + b".length

        val target = ExtractVariableTarget.resolve(file, start, end)
        assertTrue(target is KtExpression)
        assertEquals("a + b", target!!.text)
    }

    fun testResolvesTheSmallestMatchingExpressionNotAWiderAncestor() {
        val file = myFixture.configureByText(
            "Acme.java",
            """
            class Acme {
                int total(int a, int b, int c) {
                    return a + b * c;
                }
            }
            """.trimIndent(),
        )
        val start = file.text.indexOf("b * c")
        val end = start + "b * c".length

        val target = ExtractVariableTarget.resolve(file, start, end)
        assertEquals("b * c", target?.text)
    }
}
