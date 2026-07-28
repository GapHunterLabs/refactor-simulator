package dev.gaphunter.refactorsimulator.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import dev.gaphunter.refactorsimulator.testimpact.RelatedTestFinder
import dev.gaphunter.refactorsimulator.ui.ImpactToolWindowFactory

/**
 * Editor context-menu + Refactor-menu entry point.
 *
 * Bug found and fixed via manual runIde verification (2026-07-28, see
 * KNOWN_ISSUES.md): the original implementation resolved the rename
 * target from `CommonDataKeys.PSI_ELEMENT`, then walked up with
 * `PsiTreeUtil.getParentOfType(..., strict = false)`. That data key does
 * not reliably reflect "the element under the text caret" -- clicking on
 * `calcSubtotal` resolved to the method's `BigDecimal` return-type
 * reference instead (both are PsiNamedElement, and getParentOfType with
 * strict=false starts the walk from whatever PSI_ELEMENT actually
 * returned, not necessarily the identifier the user right-clicked on).
 * The correct pattern (confirmed against IntelliJ Platform SDK guidance)
 * is `psiFile.findElementAt(editor.caretModel.offset)` -- the real caret
 * position, resolved through the file's own PSI, never through a data
 * key whose resolution depends on ambiguous UI context (breadcrumbs,
 * tree selection, etc. can also populate PSI_ELEMENT).
 *
 * Safeguard (plan §1.0): update() disables the action while the project
 * is in Dumb Mode -- findUsages() during indexing can return partial
 * results or throw IndexNotReadyException, and a silently-incomplete
 * impact report is worse than declining the action for a few seconds.
 * The check itself (DumbService.isDumb) is cheap -- no heavy PSI walk
 * happens in update(), which runs on the EDT frequently.
 */
class SimulateRefactorAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val target = findRenameTargetAtCaret(e)
        e.presentation.isEnabledAndVisible =
            project != null && !DumbService.isDumb(project) && target != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = findRenameTargetAtCaret(e) ?: return

        val dialog = SimulateRefactorDialog(project, target)
        if (!dialog.showAndGet()) return

        val session = dialog.session ?: return
        val result = dialog.result ?: return
        val relatedTests = RelatedTestFinder.findRelatedTestFiles(target)
        ImpactToolWindowFactory.showPanel(project)?.showSimulationResult(session, result, relatedTests)
    }

    private fun findRenameTargetAtCaret(e: AnActionEvent): PsiNamedElement? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        return elementAtCaret(editor, psiFile)
    }

    private fun elementAtCaret(editor: Editor, psiFile: PsiFile): PsiNamedElement? {
        val offset = editor.caretModel.offset
        val leaf = psiFile.findElementAt(offset) ?: return null

        // Prefer the nearest PsiNameIdentifierOwner whose own name
        // identifier token is the leaf under the caret -- this is what
        // distinguishes "caret is on the method's own name" from "caret
        // happens to be inside the method's body/return type/parameters,
        // which also resolve to some enclosing PsiNamedElement." Without
        // this check, getParentOfType(strict=false) would return the
        // first named ancestor regardless of which part of it the caret
        // is actually on.
        var candidate: PsiElement? = leaf
        while (candidate != null) {
            if (candidate is PsiNameIdentifierOwner && candidate.nameIdentifier == leaf) {
                return candidate
            }
            candidate = candidate.parent
        }

        // Caret isn't on a name identifier specifically (e.g. it's on a
        // reference like a return type or a call target) -- fall back to
        // the nearest named element, same as before, since Rename can
        // still act on it (e.g. renaming BigDecimal from a return-type
        // reference is a legitimate, if different, rename target).
        return PsiTreeUtil.getParentOfType(leaf, PsiNamedElement::class.java, /* strict = */ false)
    }
}
