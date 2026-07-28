package dev.gaphunter.refactorsimulator.refactor

/**
 * v0.1 scope: RENAME first (RenameProcessor already exposes exactly the
 * findUsages/preview/apply split the sandbox needs), then the two extract
 * variants. MOVE is deliberately excluded -- its impact surface (imports
 * across N unrelated files, module/classpath boundaries) makes the
 * isolated-test-runner risk in [dev.gaphunter.refactorsimulator.testimpact]
 * worse, not just bigger. See KNOWN_ISSUES.md.
 */
enum class RefactorKind {
    RENAME,
    EXTRACT_VARIABLE,
    EXTRACT_FUNCTION,
}
