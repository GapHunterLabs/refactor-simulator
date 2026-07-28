package dev.gaphunter.refactorsimulator.refactor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationResultTest {

    private fun affectedFile(references: Int, imports: Int) = AffectedFile(
        filePath = "/tmp/File.kt",
        originalText = "before",
        simulatedText = "after",
        referenceCount = references,
        importCount = imports,
    )

    @Test
    fun `totals sum across all affected files`() {
        val result = SimulationResult(
            kind = RefactorKind.RENAME,
            originalName = "oldName",
            newName = "newName",
            affectedFiles = listOf(affectedFile(references = 3, imports = 1), affectedFile(references = 5, imports = 2)),
            conflicts = emptyList(),
        )

        assertEquals(8, result.totalReferenceCount)
        assertEquals(3, result.totalImportCount)
        assertEquals(11, result.totalChangeCount)
    }

    @Test
    fun `hasConflicts is false when the conflict list is empty`() {
        val result = SimulationResult(
            kind = RefactorKind.RENAME,
            originalName = "oldName",
            newName = "newName",
            affectedFiles = emptyList(),
            conflicts = emptyList(),
        )

        assertFalse(result.hasConflicts)
    }

    @Test
    fun `hasConflicts is true when at least one conflict is reported`() {
        val result = SimulationResult(
            kind = RefactorKind.RENAME,
            originalName = "oldName",
            newName = "newName",
            affectedFiles = emptyList(),
            conflicts = listOf(Conflict("collides with existing member", "/tmp/File.kt")),
        )

        assertTrue(result.hasConflicts)
    }
}
