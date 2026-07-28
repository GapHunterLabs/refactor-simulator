package dev.gaphunter.refactorsimulator.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

const val TOOL_WINDOW_ID = "Refactor Simulator"

/** anchor="right" per the mockup -- a detail/inspection panel next to the editor. */
class ImpactToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ImpactPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        /**
         * Activates the tool window (creating its content the first time)
         * and returns its ImpactPanel, so SimulateRefactorAction has
         * somewhere to push a SimulationResult after the dialog closes.
         */
        fun showPanel(project: Project): ImpactPanel? {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return null
            toolWindow.show()
            return toolWindow.contentManager.contents.firstOrNull()?.component as? ImpactPanel
        }
    }
}
