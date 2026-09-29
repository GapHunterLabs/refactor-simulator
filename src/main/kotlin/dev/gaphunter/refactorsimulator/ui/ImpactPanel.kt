package dev.gaphunter.refactorsimulator.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import dev.gaphunter.refactorsimulator.apply.ApplyToDiskAction
import dev.gaphunter.refactorsimulator.apply.DiscardAction
import dev.gaphunter.refactorsimulator.diff.SimulationDiffPresenter
import dev.gaphunter.refactorsimulator.impact.ImpactAnalyzer
import dev.gaphunter.refactorsimulator.licensing.RefactorSimulatorLicense
import dev.gaphunter.refactorsimulator.refactor.SimulationResult
import dev.gaphunter.refactorsimulator.sandbox.SandboxSession
import dev.gaphunter.refactorsimulator.testimpact.GradleTestRequest
import dev.gaphunter.refactorsimulator.testimpact.IsolatedTestRunner
import dev.gaphunter.refactorsimulator.testimpact.ModuleSourceRootResolver
import dev.gaphunter.refactorsimulator.testimpact.TestRunSummary
import java.awt.BorderLayout
import java.awt.GridLayout
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Layout per the user's mockup: status banner, then the 4-card Impact
 * Summary row (Files affected / Total changes / Imports / References),
 * then Show Diff / Apply to Disk / Discard at the bottom.
 * ValidationPanel (Validation Report + Related Tests) is a separate
 * component added alongside this one -- see ImpactToolWindowFactory.
 */
class ImpactPanel(private val project: Project) : JBPanel<ImpactPanel>(BorderLayout()) {

    private val statusLabel = JBLabel(" ")
    private val summaryRow = JPanel(GridLayout(1, 4, 8, 0))
    val validationPanel = ValidationPanel()
    private val showDiffButton = JButton("Show Diff")
    private val applyButton = JButton("Apply to Disk")
    private val discardButton = JButton("Discard")

    // Bug found via manual runIde verification (2026-07-28, see
    // KNOWN_ISSUES.md): the three buttons were created and enabled/disabled
    // but never wired to an ActionListener at all -- clicking any of them
    // did nothing, silently. currentSession/currentResult hold what
    // showSimulationResult() last received so the listeners (added in
    // init, once) have something to act on.
    private var currentSession: SandboxSession? = null
    private var currentResult: SimulationResult? = null
    private val testRunner = IsolatedTestRunner(project)

    init {
        val content = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        content.add(statusLabel)
        content.add(summaryRow)
        content.add(validationPanel)
        add(content, BorderLayout.NORTH)

        val buttonRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(showDiffButton)
            add(applyButton)
            add(discardButton)
        }
        add(buttonRow, BorderLayout.SOUTH)

        showDiffButton.addActionListener {
            currentResult?.let { SimulationDiffPresenter.showDiff(project, it) }
        }
        applyButton.addActionListener {
            val session = currentSession
            val result = currentResult
            if (session != null && result != null) {
                ApplyToDiskAction.apply(session, result)
                Messages.showInfoMessage(project, "Refactor applied to disk.", "Refactor Simulator")
                reset()
            }
        }
        discardButton.addActionListener {
            currentSession?.let { DiscardAction.discard(it, testRunner) }
            reset()
        }

        validationPanel.willRunListener = WillRunListener { testFilePath -> onWillRun(testFilePath) }

        setButtonsEnabled(false)
    }

    /**
     * Gated on the real license, not the [dev.gaphunter.refactorsimulator.settings.RefactorSimulatorSettings]
     * stub -- that field only controls whether "Will run" is enabled at
     * all (see [showSimulationResult]); this is the second check right
     * before actually spending the ~1s+ of a Gradle run, in case the
     * license was revoked between render and click.
     */
    private fun onWillRun(testFilePath: String) {
        // No simulation on screen (discarded or applied): nothing to run -- and no reason to ask for a license.
        val result = currentResult ?: return
        if (RefactorSimulatorLicense.isLicensed() != true) {
            RefactorSimulatorLicense.requestLicense("Running related tests in an isolated sandbox is part of Refactor Simulator Pro.")
            return
        }

        object : Task.Backgroundable(project, "Running related test", true) {
            override fun run(indicator: ProgressIndicator) {
                // Module/file lookups and PSI reads need read access; the run itself happens outside it.
                val (moduleSourceRoots, overrides, gradle) = ApplicationManager.getApplication().runReadAction(
                    Computable<Triple<Map<String, Path>, Map<String, String>, GradleTestRequest?>> {
                        val roots = ModuleSourceRootResolver.resolveModuleSourceRoots(result, project)
                        Triple(roots, ModuleSourceRootResolver.buildOverrides(result, project, roots), gradleRequest(result, testFilePath))
                    },
                )
                val outcomes = testRunner.runRelatedTests(moduleSourceRoots, overrides, gradle)
                val testName = testFilePath.substringAfterLast('/').substringAfterLast('\\')

                val summary = TestRunSummary.describe(outcomes, testName)
                thisLogger().info("related test result: " + summary.lineSequence().first())
                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(project, summary, "Refactor Simulator — Related Test Result")
                }
            }
        }.queue()
    }

    /**
     * The Gradle side of a run: the root of the Gradle build that owns the test (as IntelliJ linked it), the test's
     * class name for `--tests`, and the simulated text of every affected file. Null when the test file is gone.
     */
    private fun gradleRequest(result: SimulationResult, testFilePath: String): GradleTestRequest? {
        val file = LocalFileSystem.getInstance().findFileByPath(testFilePath.replace('\\', '/')) ?: return null
        val module = ModuleUtilCore.findModuleForFile(file, project)
        val buildRoot = module?.let { ExternalSystemApiUtil.getExternalRootProjectPath(it) } ?: project.basePath ?: return null
        val owner = PsiManager.getInstance(project).findFile(file) as? PsiClassOwner
        val className = owner?.classes?.firstOrNull()?.qualifiedName
            ?: listOfNotNull(owner?.packageName?.takeIf { it.isNotEmpty() }, file.nameWithoutExtension).joinToString(".")
        return GradleTestRequest(buildRoot, file.path, className, result.affectedFiles.associate { it.filePath to it.simulatedText })
    }

    fun showSimulationResult(session: SandboxSession, result: SimulationResult, relatedTestNames: List<String>) {
        currentSession = session
        currentResult = result
        val statusText = if (result.hasConflicts) {
            "⚠ Simulation completed with ${result.conflicts.size} conflict(s)."
        } else {
            "✓ Simulation completed. No changes have been written to disk."
        }
        // <html> wrap: a plain JBLabel truncates this at the tool window's
        // current width instead of wrapping to a second line -- this text is
        // long enough that a narrow tool window (its default state) clips it.
        statusLabel.text = "<html>$statusText</html>"
        statusLabel.foreground = if (result.hasConflicts) JBColor.ORANGE else JBColor.GREEN

        summaryRow.removeAll()
        summaryRow.add(summaryCard("Files affected", result.affectedFiles.size.toString()))
        summaryRow.add(summaryCard("Total changes", result.totalChangeCount.toString()))
        summaryRow.add(summaryCard("Imports", result.totalImportCount.toString()))
        summaryRow.add(summaryCard("References", result.totalReferenceCount.toString()))
        summaryRow.revalidate()
        summaryRow.repaint()

        validationPanel.showValidationReport(ImpactAnalyzer.buildValidationReport(result))
        validationPanel.showRelatedTests(relatedTestNames, RefactorSimulatorLicense.isLicensed() == true)

        setButtonsEnabled(true)
    }

    fun reset() {
        currentSession = null
        currentResult = null
        statusLabel.text = " "
        summaryRow.removeAll()
        summaryRow.revalidate()
        summaryRow.repaint()
        validationPanel.clear()
        setButtonsEnabled(false)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        showDiffButton.isEnabled = enabled
        applyButton.isEnabled = enabled
        discardButton.isEnabled = enabled
    }

    private fun summaryCard(label: String, value: String): JPanel {
        // Center-aligned, wrapping labels: without this, a 4-column
        // GridLayout can render unevenly (e.g. "References" wider than
        // "Imports") once a real value forces one card's content wider
        // than an empty placeholder would have been.
        val valueLabel = JBLabel(value, javax.swing.SwingConstants.CENTER)
        val nameLabel = JBLabel("<html><div style='text-align:center'>$label</div></html>", javax.swing.SwingConstants.CENTER)
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEtchedBorder()
            valueLabel.alignmentX = CENTER_ALIGNMENT
            nameLabel.alignmentX = CENTER_ALIGNMENT
            add(valueLabel)
            add(nameLabel)
        }
    }
}
