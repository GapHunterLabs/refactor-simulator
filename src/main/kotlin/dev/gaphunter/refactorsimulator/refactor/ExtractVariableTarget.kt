package dev.gaphunter.refactorsimulator.refactor

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Resolves an editor selection to the expression it exactly covers --
 * never expands to a bigger enclosing expression when the selection
 * doesn't line up with one, matching this plugin's own "never guess"
 * discipline (same principle `SimulateRefactorAction`'s caret-based
 * rename-target resolution already follows). Works for both Java
 * ([PsiExpression]) and Kotlin ([KtExpression]) without any
 * language-specific branching, since both hierarchies extend a plain
 * [PsiElement] the same way.
 *
 * Whitespace at either end of the raw selection is trimmed first --
 * a user dragging a selection almost always catches a leading/trailing
 * space or two, and that alone shouldn't turn a perfectly good
 * selection into "no expression found here."
 */
object ExtractVariableTarget {
    fun resolve(file: PsiFile, selectionStart: Int, selectionEnd: Int): PsiElement? {
        val text = file.text
        var start = selectionStart
        var end = selectionEnd
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end) return null

        var candidate: PsiElement? = file.findElementAt(start) ?: return null
        while (candidate != null) {
            val range = candidate.textRange
            if (range != null && range.startOffset == start && range.endOffset == end &&
                (candidate is PsiExpression || candidate is KtExpression)
            ) {
                return candidate
            }
            // Never a match beyond the file itself -- some fixtures' containing
            // PsiDirectory (the file's own PSI parent) has no text range at all.
            if (candidate is PsiFile) return null
            candidate = candidate.parent
        }
        return null
    }
}
