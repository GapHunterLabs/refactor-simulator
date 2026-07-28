package dev.gaphunter.refactorsimulator.impact

import dev.gaphunter.refactorsimulator.refactor.AffectedFile
import dev.gaphunter.refactorsimulator.refactor.Conflict
import dev.gaphunter.refactorsimulator.refactor.RefactorKind
import dev.gaphunter.refactorsimulator.refactor.SimulationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactAnalyzerTest {

    private val cleanResult = SimulationResult(
        kind = RefactorKind.RENAME,
        originalName = "oldName",
        newName = "newName",
        affectedFiles = listOf(
            AffectedFile("/tmp/A.kt", "before", "after", referenceCount = 2, importCount = 1),
        ),
        conflicts = emptyList(),
    )

    @Test
    fun `clean simulation reports no naming collisions`() {
        val report = ImpactAnalyzer.buildValidationReport(cleanResult)

        assertFalse(report.hasWarnings)
        assertFalse(report.hasErrors)
        assertTrue(report.checks.any { it.label == "No naming collisions" && it.status == CheckStatus.OK })
    }

    @Test
    fun `conflicts surface as a warning, never as an error or a hidden check`() {
        val withConflict = cleanResult.copy(
            conflicts = listOf(Conflict("collides with existing member", "/tmp/A.kt")),
        )

        val report = ImpactAnalyzer.buildValidationReport(withConflict)

        assertTrue(report.hasWarnings)
        assertFalse(report.hasErrors)
        assertTrue(report.checks.any { it.label == "Naming collision" && it.status == CheckStatus.WARNING })
    }

    @Test
    fun `report always includes the five deterministic checks from the mockup`() {
        val report = ImpactAnalyzer.buildValidationReport(cleanResult)

        assertEquals(5, report.checks.size)
    }
}
