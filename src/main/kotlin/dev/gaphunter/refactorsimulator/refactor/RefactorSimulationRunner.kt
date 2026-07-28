package dev.gaphunter.refactorsimulator.refactor

import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import dev.gaphunter.refactorsimulator.sandbox.SandboxSession

/**
 * Finds every real reference to the rename target and computes what the
 * post-rename text would look like per file -- never calls
 * `performRefactoring()` or touches the real project. v0.1 covers RENAME
 * only; EXTRACT_VARIABLE/EXTRACT_FUNCTION follow the same shape once
 * RENAME is verified end-to-end.
 *
 * Bug found and fixed via `verifyPlugin` (2026-07-28, see
 * KNOWN_ISSUES.md): this originally drove `RenameProcessor.findUsages()`
 * (inherited from `BaseRefactoringProcessor`), which is annotated
 * `@ApiStatus.OverrideOnly` -- confirmed directly in the platform's own
 * bytecode (`javap` against `BaseRefactoringProcessor.class` from a real
 * IDE distribution). The first attempted fix -- a subclass calling
 * `findUsages()` from inside its own override, expecting that to satisfy
 * the contract's documented intent -- did NOT work: `verifyPlugin` still
 * flagged the exact same violation against every target IDE, now citing
 * the subclass as the caller. Confirmed (via `WebSearch`, JetBrains
 * platform forum) that the Plugin Verifier does pure bytecode call-site
 * detection with no inheritance-relationship analysis -- calling
 * `super.foo()` on an `OverrideOnly` method from within a genuine
 * override triggers the same violation as calling it externally; there
 * is no sanctioned workaround for this annotation, only "don't call it."
 *
 * Actual fix: dropped `RenameProcessor` entirely for usage detection.
 * `ReferencesSearch.search(element)` (confirmed via `javap` to carry no
 * `@ApiStatus.*` annotations at all, on the class or the method) is the
 * platform's genuinely public, general-purpose "find every reference to
 * this element" API -- exactly what a simulation needs, without any of
 * `RenameProcessor`'s rename-specific execution machinery. Each
 * `PsiReference` found is wrapped in a `UsageInfo(PsiReference)` (a
 * plain public constructor, also confirmed annotation-free), which keeps
 * every downstream consumer (`getSegment()`, `getFile()`, grouping,
 * `applyRenameToText()`, `RenameUtil.addConflictDescriptions()`)
 * completely unchanged -- only the source of usages changed, not their
 * shape.
 */
object RefactorSimulationRunner {

    /**
     * Null means the caller should not offer simulation right now --
     * findUsages() during Dumb Mode can return partial/stale results or
     * throw IndexNotReadyException, and a silently-incomplete impact
     * report is worse than declining the action (see plan §1.0).
     */
    fun simulateRename(session: SandboxSession, newName: String): SimulationResult? {
        if (DumbService.isDumb(session.project)) {
            return null
        }

        val target = session.originalElement as? PsiNamedElement
            ?: error("Rename target must be a PsiNamedElement")

        val usages = ReferencesSearch.search(target).findAll()
            .map { reference -> UsageInfo(reference) }
            .toTypedArray()
        val conflicts = detectConflicts(usages)

        val usagesByFile = usages.filter { it.file != null }.groupBy { it.file!! }
        val affectedFiles = usagesByFile.map { (file, fileUsages) ->
            AffectedFile(
                filePath = file.virtualFile?.path ?: file.name,
                originalText = file.text,
                simulatedText = applyRenameToText(file.text, fileUsages, newName),
                referenceCount = fileUsages.size,
                importCount = 0,
            )
        }

        return SimulationResult(
            kind = RefactorKind.RENAME,
            originalName = target.name ?: "",
            newName = newName,
            affectedFiles = affectedFiles,
            conflicts = conflicts,
        )
    }

    /**
     * Computes the post-rename text for one file, for the diff/Impact
     * Summary only -- never touches the real PSI tree or Document.
     *
     * Bug found and fixed via manual runIde verification (2026-07-28, see
     * KNOWN_ISSUES.md): the first implementation left `simulatedText =
     * file.text` as a placeholder and never came back to finish it, so
     * every simulation's diff showed identical before/after text. The
     * naive fix (RenameUtil.doRename against the real UsageInfo array)
     * would have violated the plugin's core "never touch the real project
     * until Apply" principle -- doRename operates on the actual PsiElement
     * tree the UsageInfo objects point into, not a copy.
     *
     * Instead: UsageInfo.getSegment() exposes the exact absolute text
     * range each usage occupies in its file (confirmed against platform
     * source -- it's what the platform's own preview/diff already relies
     * on), so this replaces each of those ranges with newName directly on
     * the plain file text string, in reverse offset order (so replacing
     * a later range never invalidates the offsets of an earlier one still
     * to be processed). This is precise -- it only touches ranges
     * ReferencesSearch already identified as real references, never a
     * naive string.replace that could also hit comments/unrelated text
     * containing the same substring -- and it never constructs or mutates
     * a second PSI tree.
     */
    private fun applyRenameToText(originalText: String, fileUsages: List<UsageInfo>, newName: String): String {
        val ranges = fileUsages.mapNotNull { it.segment }
            .sortedByDescending { it.startOffset }

        val builder = StringBuilder(originalText)
        for (range in ranges) {
            if (range.startOffset in 0..builder.length && range.endOffset in range.startOffset..builder.length) {
                builder.replace(range.startOffset, range.endOffset, newName)
            }
        }
        return builder.toString()
    }

    /**
     * `RenameUtil.addConflictDescriptions` (confirmed public static, no
     * `@ApiStatus.*` annotation, from platform source) computes the same
     * conflict data the platform's own rename preview dialog would show
     * -- naming collisions, visibility problems, etc. -- from a plain
     * `UsageInfo[]`, without ever needing `RenameProcessor` or showing
     * any dialog itself. Works unchanged whether the `UsageInfo[]` came
     * from `RenameProcessor.findUsages()` (the original, now-removed
     * approach) or from wrapping `ReferencesSearch` results (the current
     * one) -- it only inspects the `UsageInfo` data, not its origin.
     */
    private fun detectConflicts(usages: Array<UsageInfo>): List<Conflict> {
        val conflicts = MultiMap<PsiElement, String>()
        RenameUtil.addConflictDescriptions(usages, conflicts)
        if (conflicts.isEmpty) return emptyList()

        return conflicts.entrySet().flatMap { (element, messages) ->
            messages.map { message ->
                Conflict(
                    description = message,
                    filePath = element.containingFile?.virtualFile?.path ?: "(unknown)",
                )
            }
        }
    }
}
