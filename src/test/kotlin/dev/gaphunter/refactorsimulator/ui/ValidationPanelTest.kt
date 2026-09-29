package dev.gaphunter.refactorsimulator.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Discarding or applying a simulation leaves no stale checks or "Will run" button behind. */
class ValidationPanelTest : BasePlatformTestCase() {

    fun testClearRemovesTheRelatedTestsAndTheirWillRunButton() {
        val panel = ValidationPanel()
        panel.showRelatedTests(listOf("/p/src/test/java/acme/EngineTest.java"), isPro = true)
        assertTrue(panel.shownTexts().toString(), panel.shownTexts().contains("Will run"))

        panel.clear()

        val shown = panel.shownTexts()
        assertFalse(shown.toString(), shown.contains("Will run"))
        assertFalse(shown.toString(), shown.any { it.contains("EngineTest") })
        assertTrue(shown.toString(), shown.any { it.contains("Related Tests") } && shown.any { it.contains("Validation") })
    }
}
