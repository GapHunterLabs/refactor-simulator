package dev.gaphunter.refactorsimulator.sandbox

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager

/**
 * Builds an in-memory PSI copy of a real file, never touching the
 * project's VirtualFile or Document. `eventSystemEnabled = false` keeps
 * the platform's own file/document listeners from ever seeing this copy,
 * so nothing about the simulation is visible to the rest of the IDE
 * until [dev.gaphunter.refactorsimulator.apply.ApplyToDiskAction] runs.
 *
 * Line-ending normalization matters here: PsiFileFactory.createFileFromText
 * treats the text as-is, and a stray '\r' can desync offsets between the
 * sandbox copy and Find Usages results computed against the real file
 * (which the platform always normalizes to '\n' internally).
 */
object SandboxFileFactory {

    fun createInMemoryCopy(project: Project, originalFile: PsiFile): PsiFile {
        val normalizedText = originalFile.text.replace("\r\n", "\n").replace("\r", "\n")

        return PsiFileFactory.getInstance(project)
            .createFileFromText(
                originalFile.name,
                originalFile.language,
                normalizedText,
                /* eventSystemEnabled = */ false,
                /* markAsCopy = */ true,
            )
    }

    fun psiManagerFor(project: Project): PsiManager = PsiManager.getInstance(project)
}
