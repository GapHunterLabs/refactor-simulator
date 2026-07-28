package dev.gaphunter.refactorsimulator.refactor

/**
 * Pure data model, no platform dependency beyond immutable value types --
 * unit-testable without spinning up a project. What
 * [RefactorSimulationRunner] hands to every downstream consumer
 * (impact analysis, diff presentation, isolated test running): all of
 * them read from this, none of them re-run findUsages() themselves.
 */
data class SimulationResult(
    val kind: RefactorKind,
    val originalName: String,
    val newName: String,
    val affectedFiles: List<AffectedFile>,
    val conflicts: List<Conflict>,
) {
    val totalReferenceCount: Int get() = affectedFiles.sumOf { it.referenceCount }
    val totalImportCount: Int get() = affectedFiles.sumOf { it.importCount }
    val totalChangeCount: Int get() = totalReferenceCount + totalImportCount
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
}

data class AffectedFile(
    val filePath: String,
    val originalText: String,
    val simulatedText: String,
    val referenceCount: Int,
    val importCount: Int,
)

data class Conflict(
    val description: String,
    val filePath: String,
)
