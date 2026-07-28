package dev.gaphunter.refactorsimulator.apply

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.rename.RenameProcessor
import dev.gaphunter.refactorsimulator.refactor.SimulationResult
import dev.gaphunter.refactorsimulator.sandbox.SandboxSession

/**
 * The only place in the plugin allowed to touch the real project. Runs
 * the real refactor (against [SandboxSession.originalElement], not the
 * sandbox copy), and only after the user clicked "Apply to Disk"
 * explicitly -- never as a side effect of Simulate.
 *
 * Bug found and fixed via manual runIde verification (2026-07-28, see
 * KNOWN_ISSUES.md): this originally wrapped `RenameProcessor(...).run()`
 * in an explicit `WriteCommandAction.runWriteCommandAction { ... }`.
 * `RenameProcessor.run()` (inherited from `BaseRefactoringProcessor`) is
 * the platform's own top-level entry point for running a refactoring --
 * the same method IntelliJ's built-in Rename action calls directly from
 * its own `actionPerformed` -- and it already opens its own write action
 * and command internally. Nesting it inside a second, manually-opened
 * `WriteCommandAction` produced no visible exception (the info dialog
 * still fired unconditionally right after), but the real files on disk
 * were never modified -- confirmed by SHA-256 checksum before/after
 * clicking Apply to Disk, byte-identical every time. `RenameProcessor`
 * is called directly now, on the EDT, exactly like the platform's own
 * Rename action does.
 *
 * Second bug found immediately after fixing the first one, same manual
 * verification pass: with the write-action nesting fixed, the rename
 * genuinely ran and the editor correctly showed the new name live --
 * but a checksum of the file on disk still came back unchanged.
 * `RenameProcessor.run()` only mutates the in-memory `Document` backing
 * each affected `PsiFile`; like every other refactoring in the
 * platform, it relies on the IDE's own save-on-focus-loss/autosave to
 * flush that `Document` to the actual `VirtualFile` on disk eventually
 * -- it does not persist synchronously, and a sandboxed `runIde`
 * session run non-interactively doesn't reliably trigger that autosave
 * on the same timeline a real user's IDE session would. Since this
 * plugin's whole premise is "what you saw in the diff is what actually
 * changed," waiting on autosave isn't good enough here. Fixed by
 * explicitly resolving each `AffectedFile.filePath` back to its
 * `VirtualFile`/`Document` and calling
 * `FileDocumentManager.saveDocument(document)` for each one right after
 * the rename -- saving only the files this refactor touched, not
 * `saveAllDocuments()` (which would also flush unrelated unsaved editor
 * state the user has open for other reasons).
 */
object ApplyToDiskAction {

    fun apply(session: SandboxSession, result: SimulationResult) {
        val target = session.originalElement as? PsiNamedElement
            ?: error("Apply target must be a PsiNamedElement")

        RenameProcessor(
            session.project,
            target,
            result.newName,
            /* isSearchInComments = */ false,
            /* isSearchTextOccurrences = */ false,
        ).run()

        saveAffectedDocuments(result)
        session.dispose()
    }

    private fun saveAffectedDocuments(result: SimulationResult) {
        val fileDocumentManager = FileDocumentManager.getInstance()
        val localFileSystem = LocalFileSystem.getInstance()
        for (affectedFile in result.affectedFiles) {
            val virtualFile = localFileSystem.findFileByPath(affectedFile.filePath) ?: continue
            val document = fileDocumentManager.getDocument(virtualFile) ?: continue
            fileDocumentManager.saveDocument(document)
        }
    }
}
