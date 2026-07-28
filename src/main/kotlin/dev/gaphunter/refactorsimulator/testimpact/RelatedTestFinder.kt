package dev.gaphunter.refactorsimulator.testimpact

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import dev.gaphunter.refactorsimulator.refactor.SimulationResult

/**
 * Lists which test files touch the refactored code, reusing the same
 * reference index findUsages() already computed for [SimulationResult]
 * -- never a second, separate search.
 *
 * Safeguard (plan §1.0): a private/local target scopes the search to its
 * own file instead of the whole project. A private member or local
 * variable's usages are already bounded by the language itself, so a
 * project-wide search wastes CPU for a result the compiler already knows
 * is impossible to have outside that scope.
 */
object RelatedTestFinder {

    private val TEST_FILE_NAME_PATTERN = Regex(""".*Test\.(java|kt)$""")

    fun findRelatedTestFiles(target: PsiElement): List<String> {
        val scope = if (isPrivateOrLocal(target)) {
            GlobalSearchScope.fileScope(target.containingFile)
        } else {
            GlobalSearchScope.projectScope(target.project)
        }

        return ReferencesSearch.search(target, scope)
            .findAll()
            .mapNotNull { it.element.containingFile?.virtualFile?.path }
            .distinct()
            .filter { path -> TEST_FILE_NAME_PATTERN.matches(path) }
    }

    private fun isPrivateOrLocal(element: PsiElement): Boolean {
        val modifierOwner = element as? PsiModifierListOwner ?: return element.parent is com.intellij.psi.PsiLocalVariable
        return modifierOwner.hasModifierProperty(PsiModifier.PRIVATE)
    }
}
