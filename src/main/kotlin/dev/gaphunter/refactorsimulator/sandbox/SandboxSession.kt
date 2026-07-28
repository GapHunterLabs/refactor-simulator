package dev.gaphunter.refactorsimulator.sandbox

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Groups the in-memory PSI copy with the metadata a simulation needs to
 * travel through the pipeline (original file, target element, language).
 *
 * Safeguard (see the plan's §1.0 table): [sandboxFile]'s backing
 * [Document] must never be the same instance as the real file's document,
 * or a WriteCommandAction run against the sandbox could register an entry
 * in the real project's UndoManager -- a "Discard" would then leave a
 * phantom Ctrl+Z entry behind. [verifyDocumentIsolation] asserts this at
 * session-creation time (backed by a real test, not just a comment) so a
 * future change can't silently reintroduce shared state.
 */
class SandboxSession private constructor(
    val project: Project,
    val originalFile: PsiFile,
    val originalElement: PsiElement,
    val sandboxFile: PsiFile,
) {

    companion object {
        fun create(project: Project, originalElement: PsiElement): SandboxSession {
            val originalFile = originalElement.containingFile
                ?: error("Cannot simulate a refactor on an element with no containing file")

            val sandboxFile = SandboxFileFactory.createInMemoryCopy(project, originalFile)
            val session = SandboxSession(project, originalFile, originalElement, sandboxFile)
            session.verifyDocumentIsolation()
            return session
        }
    }

    /**
     * Fails fast (rather than silently risking a leaked undo entry) if the
     * sandbox's document turns out to be the same object as the real
     * file's document -- see the class doc comment.
     */
    private fun verifyDocumentIsolation() {
        val realDocument: Document? = PsiDocumentManager.getInstance(project).getDocument(originalFile)
        val sandboxDocument: Document? = PsiDocumentManager.getInstance(project).getDocument(sandboxFile)
        check(sandboxDocument !== realDocument) {
            "Sandbox document must never be the same instance as the real file's document"
        }
    }

    /**
     * The sandbox document, exposed for tests to confirm isolation
     * end-to-end (e.g. that it's never equal to the real file's document,
     * and that it's never bound to any open editor -- an undo-manager
     * entry can only be registered for a document that has a live editor
     * or is otherwise attached to the IDE's document/VFS machinery, which
     * a non-eventSystemEnabled PsiFile's document never is).
     */
    internal fun sandboxDocument(): Document? = PsiDocumentManager.getInstance(project).getDocument(sandboxFile)

    fun dispose() {
        // No-op for now: the sandbox PsiFile is not eventSystemEnabled, so
        // it holds no VFS/document listeners to release. Kept as an
        // explicit method (rather than relying on GC) so DiscardAction has
        // a single, auditable place to call when Discard fires.
    }
}
