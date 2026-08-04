package dev.gaphunter.refactorsimulator.actions

import com.intellij.lang.LanguageNamesValidation
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import dev.gaphunter.refactorsimulator.refactor.RefactorSimulationRunner
import dev.gaphunter.refactorsimulator.refactor.SimulationResult
import dev.gaphunter.refactorsimulator.sandbox.SandboxSession
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

private const val PREVIEW_MAX_LENGTH = 60

/**
 * Extract Variable dialog: the selected expression (read-only preview,
 * truncated so a multi-line selection doesn't blow up the dialog
 * layout) and the new variable name (editable, validated live the same
 * way [SimulateRefactorDialog] validates a new name).
 */
class SimulateExtractVariableDialog(
    private val project: Project,
    private val target: PsiElement,
) : DialogWrapper(project) {

    private val variableNameField = JBTextField("extracted")

    var result: SimulationResult? = null
        private set

    var session: SandboxSession? = null
        private set

    init {
        title = "Simulate Extract Variable"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        panel.add(JBLabel("Expression: ${previewOf(target.text)}"))
        panel.add(JBLabel("New variable name:"))
        panel.add(variableNameField)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = variableNameField

    override fun doValidate(): ValidationInfo? {
        val name = variableNameField.text
        if (name.isBlank()) {
            return ValidationInfo("Name cannot be empty", variableNameField)
        }

        val validator = LanguageNamesValidation.INSTANCE.forLanguage(target.language)
        if (validator.isKeyword(name, project)) {
            return ValidationInfo("'$name' is a reserved keyword", variableNameField)
        }
        if (!validator.isIdentifier(name, project)) {
            return ValidationInfo("'$name' is not a valid identifier", variableNameField)
        }
        return null
    }

    override fun doOKAction() {
        val createdSession = SandboxSession.create(project, target)
        session = createdSession
        result = RefactorSimulationRunner.simulateExtractVariable(createdSession, variableNameField.text)
        super.doOKAction()
    }

    private fun previewOf(text: String): String {
        val singleLine = text.replace('\n', ' ').replace('\r', ' ')
        return if (singleLine.length > PREVIEW_MAX_LENGTH) singleLine.take(PREVIEW_MAX_LENGTH) + "…" else singleLine
    }
}
