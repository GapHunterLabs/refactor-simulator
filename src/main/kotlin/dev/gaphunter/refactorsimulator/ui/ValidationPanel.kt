package dev.gaphunter.refactorsimulator.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import dev.gaphunter.refactorsimulator.impact.CheckStatus
import dev.gaphunter.refactorsimulator.impact.ValidationReport
import java.awt.GridLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Validation Report (left column) + Related Tests (right column, Pro
 * gate) side by side, per the mockup. Related Tests is always visible --
 * never hidden entirely, per the gate design in the plan (§3.2): showing
 * what's behind the paywall is part of the upsell.
 */
class ValidationPanel : JBPanel<ValidationPanel>(GridLayout(1, 2, 12, 0)) {

    private val validationColumn = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val relatedTestsColumn = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    init {
        add(validationColumn)
        add(relatedTestsColumn)
        showEmpty()
    }

    fun showValidationReport(report: ValidationReport) {
        validationColumn.removeAll()
        validationColumn.add(wrappingLabel("Validation"))
        for (check in report.checks) {
            validationColumn.add(wrappingLabel("${statusIcon(check.status)} ${check.label}"))
        }
        validationColumn.revalidate()
        validationColumn.repaint()
    }

    /**
     * relatedTestNames stays empty-safe on purpose: v0.1's
     * IsolatedTestRunner may not be reactivated for every install (Pro
     * gate off until future/v0.2-refactor-simulator-pro is integrated),
     * so this panel must render sensibly with just the free-tier list of
     * test names RelatedTestFinder already computed for free.
     *
     * relatedTestNames entries are absolute file paths (that's what
     * RelatedTestFinder returns) -- shown here as just the file name, with
     * the full path as a tooltip, so a long path never forces the column
     * wider than the tool window or gets silently truncated.
     */
    fun showRelatedTests(relatedTestNames: List<String>, isPro: Boolean) {
        relatedTestsColumn.removeAll()
        relatedTestsColumn.add(wrappingLabel("Related Tests" + if (!isPro) " (Pro)" else ""))
        for (path in relatedTestNames) {
            val fileName = path.substringAfterLast('/').substringAfterLast('\\')
            val row = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(wrappingLabel("✓ $fileName").apply { toolTipText = path })
                add(JButton("Will run").apply { isEnabled = isPro })
            }
            relatedTestsColumn.add(row)
        }
        if (!isPro) {
            relatedTestsColumn.add(
                wrappingLabel("🔒 Run related tests is available in Pro version.").apply {
                    foreground = JBColor.GRAY
                },
            )
        }
        relatedTestsColumn.revalidate()
        relatedTestsColumn.repaint()
    }

    private fun showEmpty() {
        validationColumn.add(wrappingLabel("Validation"))
        relatedTestsColumn.add(wrappingLabel("Related Tests"))
    }

    /**
     * Plain JBLabel truncates long text silently instead of wrapping --
     * wrapping it in <html> makes Swing's label renderer treat the text
     * as flow-able HTML, which wraps at the component's actual width
     * instead of clipping at a fixed pixel count. Needed here because
     * tool windows start narrow and check/test labels can be long.
     */
    private fun wrappingLabel(text: String): JBLabel = JBLabel("<html>${text.replace("&", "&amp;").replace("<", "&lt;")}</html>")

    private fun statusIcon(status: CheckStatus): String = when (status) {
        CheckStatus.OK -> "✓"
        CheckStatus.WARNING -> "⚠"
        CheckStatus.ERROR -> "✗"
    }
}
