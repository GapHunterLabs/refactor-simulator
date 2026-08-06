package dev.gaphunter.refactorsimulator.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * No configurable rules yet -- Simulate/Diff/Apply/Discard aren't
 * optional, and licensing (Run Related Tests) is activated through
 * JetBrains's own Register dialog, not a settings toggle here. This
 * page exists so the applicationConfigurable extension point + Settings
 * > Tools entry are already correct, instead of adding them later
 * alongside real options.
 */
class RefactorSimulatorConfigurable : Configurable {

    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Refactor Simulator"

    override fun createComponent(): JComponent {
        val newPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel("Refactor Simulator has no configurable options yet."))
        }
        panel = newPanel
        return newPanel
    }

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    override fun reset() = Unit
}
