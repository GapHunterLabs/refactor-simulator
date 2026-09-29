package dev.gaphunter.refactorsimulator.actions

import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** What the rename dialog accepts before it simulates anything. */
class SimulateRefactorDialogNameTest : BasePlatformTestCase() {

    private fun method() = (myFixture.configureByText("Engine.java", "class Engine { int calcTotal() { return 1; } }") as PsiJavaFile)
        .classes.single().findMethodsByName("calcTotal", false).single()

    fun testTheCurrentNameIsRejected() {
        assertEquals("Enter a name different from the current one", SimulateRefactorDialog.nameProblem(project, method(), "calcTotal"))
    }

    fun testEmptyKeywordAndInvalidNamesAreRejected() {
        val m = method()
        assertEquals("Name cannot be empty", SimulateRefactorDialog.nameProblem(project, m, " "))
        assertEquals("'class' is a reserved keyword", SimulateRefactorDialog.nameProblem(project, m, "class"))
        assertEquals("'1total' is not a valid identifier", SimulateRefactorDialog.nameProblem(project, m, "1total"))
    }

    fun testANewValidNameIsAccepted() {
        assertNull(SimulateRefactorDialog.nameProblem(project, method(), "totalDue"))
    }
}
