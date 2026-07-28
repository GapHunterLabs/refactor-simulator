package dev.gaphunter.refactorsimulator.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.project.Project
import dev.gaphunter.refactorsimulator.refactor.AffectedFile
import dev.gaphunter.refactorsimulator.refactor.SimulationResult

/**
 * Deliberately read-only: no method here ever writes anything. The
 * "Original" side is explicitly marked FORCE_READ_ONLY so the platform's
 * own diff viewer refuses edits on it too, not just by convention.
 */
object SimulationDiffPresenter {

    fun showDiff(project: Project, result: SimulationResult) {
        when (result.affectedFiles.size) {
            0 -> return
            1 -> showSingleFileDiff(project, result.affectedFiles.single())
            else -> showMultiFileDiff(project, result)
        }
    }

    private fun showSingleFileDiff(project: Project, file: AffectedFile) {
        DiffManager.getInstance().showDiff(project, buildRequest(file))
    }

    private fun showMultiFileDiff(project: Project, result: SimulationResult) {
        val requests = result.affectedFiles.map { buildRequest(it) }
        val chain = SimpleDiffRequestChain(requests)
        DiffManager.getInstance().showDiff(project, chain, com.intellij.diff.DiffDialogHints.DEFAULT)
    }

    private fun buildRequest(file: AffectedFile): SimpleDiffRequest {
        val factory = DiffContentFactory.getInstance()
        val original: DiffContent = factory.create(file.originalText).also {
            it.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        }
        val simulated: DiffContent = factory.create(file.simulatedText)

        return SimpleDiffRequest(
            "Simulation Diff: ${file.filePath}",
            original,
            simulated,
            "Original (Read-only)",
            "Simulation Result",
        )
    }
}
