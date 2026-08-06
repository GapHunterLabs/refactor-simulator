package dev.gaphunter.refactorsimulator.testimpact

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.gaphunter.refactorsimulator.refactor.SimulationResult
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves what [IsolatedTestRunner.runRelatedTests] needs to build an
 * isolated copy of only the modules a simulation actually touches: the
 * owning module of every affected file, plus every module that
 * (transitively) depends on one of those -- a test in a downstream
 * module can fail from a change in a module it depends on, even if the
 * test file itself was never in [SimulationResult.affectedFiles].
 *
 * Deliberately does NOT try to resolve modules for related test files
 * outside this closure: [RelatedTestFinder] already scoped its search to
 * project/file scope via PSI references, so a test file it returns is
 * necessarily reachable from the affected code -- its own module is
 * already included by the dependent-closure walk below.
 */
object ModuleSourceRootResolver {

    /**
     * Module name -> the root [IsolatedTestRunner] should copy for it,
     * as an absolute [Path]. Prefers the module's content root (the
     * whole module directory, matching what a real Gradle subproject
     * looks like on disk) and falls back to its first source root when
     * there is no content root at all -- true for this catalog's own
     * light test fixtures, where [com.intellij.testFramework.PsiTestUtil.addSourceRoot]
     * marks a directory as a source root without a content entry ever
     * being added above it. A module with neither is skipped rather
     * than crashing -- [IsolatedTestRunner] can't copy a directory that
     * doesn't exist.
     */
    fun resolveModuleSourceRoots(result: SimulationResult, project: Project): Map<String, Path> {
        val seedModules = result.affectedFiles
            .mapNotNull { findVirtualFile(it.filePath) }
            .mapNotNull { ModuleUtilCore.findModuleForFile(it, project) }
            .toSet()

        val closure = withDependents(seedModules, project)

        return closure.associate { module ->
            val rootManager = ModuleRootManager.getInstance(module)
            module.name to rootManager.contentRoots.ifEmpty { rootManager.sourceRoots }
        }.filterValues { it.isNotEmpty() }
            .mapValues { (_, roots) -> Paths.get(roots.first().path) }
    }

    /**
     * [affectedFileOverrides] keys are relative to the temp dir
     * [IsolatedTestRunner] builds -- `moduleName/pathWithinModuleRoot` --
     * so a copied module's overridden file lands exactly where
     * [IsolatedTestRunner.copyModules] put the rest of that module.
     * A file whose module isn't in [moduleSourceRoots] (module had no
     * content root, see above) is skipped: there is nowhere in the temp
     * dir to write it.
     */
    fun buildOverrides(result: SimulationResult, project: Project, moduleSourceRoots: Map<String, Path>): Map<String, String> {
        val overrides = mutableMapOf<String, String>()
        for (affected in result.affectedFiles) {
            val virtualFile = findVirtualFile(affected.filePath) ?: continue
            val module = ModuleUtilCore.findModuleForFile(virtualFile, project) ?: continue
            val moduleRoot = moduleSourceRoots[module.name] ?: continue
            // String-based, not Path.relativize(): the module root and the
            // affected file's path can come from two different NIO
            // FileSystemProvider instances in this catalog's test
            // environment (IntelliJ Platform's own multi-routing FS vs the
            // JDK default), which Path.relativize refuses to mix
            // ("'other' is different type of Path") even though both
            // resolve to the same real location on disk.
            val moduleRootPath = moduleRoot.toString().replace('\\', '/').trimEnd('/')
            val affectedPath = affected.filePath.replace('\\', '/')
            val relativeWithinModule = affectedPath.removePrefix(moduleRootPath).trimStart('/')
            overrides["${module.name}/$relativeWithinModule"] = affected.simulatedText
        }
        return overrides
    }

    /**
     * [SimulationResult.affectedFiles] stores `VirtualFile.getPath()`
     * (no protocol prefix) -- in a real IDE that's always a
     * [LocalFileSystem] path (confirmed by
     * [dev.gaphunter.refactorsimulator.apply.ApplyToDiskAction], the
     * other real consumer of this same field), which this tries first.
     */
    private fun findVirtualFile(path: String): VirtualFile? = LocalFileSystem.getInstance().findFileByPath(path)

    /**
     * BFS over [Module.getModuleDependencies] (direct dependents are
     * queried in the other direction -- there is no
     * "getDependentModules()" on the platform, so this walks every
     * module in the project and keeps the ones whose own dependency list
     * includes something already in the closure). Fixed point: stops
     * once a full pass adds nothing new.
     */
    private fun withDependents(seeds: Set<Module>, project: Project): Set<Module> {
        val allModules = com.intellij.openapi.module.ModuleManager.getInstance(project).modules
        val closure = seeds.toMutableSet()
        var grew = true
        while (grew) {
            grew = false
            for (candidate in allModules) {
                if (candidate in closure) continue
                val dependencies = ModuleRootManager.getInstance(candidate).dependencies
                if (dependencies.any { it in closure }) {
                    closure.add(candidate)
                    grew = true
                }
            }
        }
        return closure
    }
}
