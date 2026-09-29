package dev.gaphunter.refactorsimulator.actions

import com.intellij.lang.LanguageNamesValidation
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiNamedElement
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import dev.gaphunter.refactorsimulator.refactor.RefactorSimulationRunner
import dev.gaphunter.refactorsimulator.sandbox.SandboxSession
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Rename dialog: current name (read-only), new name (editable). Safeguard
 * (plan §1.0): the new-name field is validated live against
 * LanguageNamesValidation.INSTANCE.forLanguage(...) before OK is enabled
 * -- catching an invalid identifier here means RefactorSimulationRunner
 * never sees one, instead of surfacing a platform-level
 * RefactoringErrorHintException later.
 */
class SimulateRefactorDialog(
    private val project: Project,
    private val target: PsiNamedElement,
) : DialogWrapper(project) {

    private val newNameField = JBTextField(target.name ?: "")

    var result: dev.gaphunter.refactorsimulator.refactor.SimulationResult? = null
        private set

    /**
     * Exposed alongside [result] so the caller (SimulateRefactorAction) can
     * pass the exact same session to ApplyToDiskAction/DiscardAction later
     * -- those need session.originalElement (the real PsiElement, not the
     * sandbox copy) to act on, and creating a second SandboxSession there
     * would be a different sandbox than the one this dialog's simulation
     * actually ran against.
     */
    var session: SandboxSession? = null
        private set

    init {
        title = "Simulate Refactor"
        init()
        newNameField.selectAll()
    }

    // Like the platform's own rename dialog: the name field has the focus with the current name selected, so typing
    // replaces it. Without this, keystrokes went nowhere and OK "renamed" the symbol to its own name.
    override fun getPreferredFocusedComponent(): JComponent = newNameField

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        panel.add(JBLabel("Current name: ${target.name}"))
        panel.add(JBLabel("New name:"))
        panel.add(newNameField)
        return panel
    }

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? =
        nameProblem(project, target, newNameField.text)?.let { com.intellij.openapi.ui.ValidationInfo(it, newNameField) }

    companion object {
        /** Why [newName] can't be simulated for [target], or null when it can. */
        internal fun nameProblem(project: Project, target: PsiNamedElement, newName: String): String? {
            if (newName.isBlank()) return "Name cannot be empty"
            if (newName == target.name) return "Enter a name different from the current one"
            val validator = LanguageNamesValidation.INSTANCE.forLanguage(target.language)
            if (validator.isKeyword(newName, project)) return "'$newName' is a reserved keyword"
            if (!validator.isIdentifier(newName, project)) return "'$newName' is not a valid identifier"
            return null
        }
    }

    override fun doOKAction() {
        val createdSession = SandboxSession.create(project, target)
        session = createdSession
        result = RefactorSimulationRunner.simulateRename(createdSession, newNameField.text)
        super.doOKAction()
    }
}
