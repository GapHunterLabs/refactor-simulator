package dev.gaphunter.refactorsimulator.diff

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.contents.DocumentContent
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.gaphunter.refactorsimulator.refactor.AffectedFile

/** Each diff request shows the original on the left and the simulated text on the right, and they differ. */
class SimulationDiffPresenterTest : BasePlatformTestCase() {

    fun testTheRequestCarriesBothTextsAndTheyDiffer() {
        val original = "class T {\n    void t() {\n        engine.calcTotal(1);\n    }\n}\n"
        val simulated = original.replace("calcTotal", "totalDue")
        val request = SimulationDiffPresenter.buildRequest(AffectedFile("/p/T.java", original, simulated, 1, 0))

        val (left, right) = request.contents.map { (it as DocumentContent).document.text }
        assertEquals(original, left)
        assertEquals(simulated, right)
        val changes = ComparisonManager.getInstance()
            .compareLines(left, right, ComparisonPolicy.DEFAULT, EmptyProgressIndicator())
        assertEquals(1, changes.size)
    }
}
