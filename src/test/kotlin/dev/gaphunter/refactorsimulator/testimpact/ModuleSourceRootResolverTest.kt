package dev.gaphunter.refactorsimulator.testimpact

import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.TempDirTestFixture
import dev.gaphunter.refactorsimulator.refactor.AffectedFile
import dev.gaphunter.refactorsimulator.refactor.RefactorKind
import dev.gaphunter.refactorsimulator.refactor.SimulationResult
import org.jetbrains.jps.model.java.JavaSourceRootType

/**
 * [ModuleSourceRootResolver] resolves [SimulationResult.affectedFiles]
 * paths through [com.intellij.openapi.vfs.LocalFileSystem] -- the same
 * VFS [dev.gaphunter.refactorsimulator.apply.ApplyToDiskAction] uses in
 * production, since `AffectedFile.filePath` always comes from a real
 * `VirtualFile.getPath()` on disk. `BasePlatformTestCase`'s *default*
 * fixture project is a light, in-memory project whose files
 * `LocalFileSystem` can never find -- overriding
 * `createTempDirTestFixture()` to return a real disk-backed
 * `TempDirTestFixtureImpl` (via `IdeaTestFixtureFactory`, same
 * mechanism `PsiTestUtil.addSourceRoot` composes with) makes the
 * fixture match what a real project actually looks like, instead of
 * routing around a VFS mismatch that would never happen for a real
 * user.
 *
 * A genuine multi-module dependent-closure walk (the BFS in
 * [ModuleSourceRootResolver]'s private withDependents) would need a
 * *second* module wired in, which nothing else in this catalog's test
 * suite has set up yet -- left as a known gap rather than faked.
 */
class ModuleSourceRootResolverTest : BasePlatformTestCase() {

    override fun createTempDirTestFixture(): TempDirTestFixture =
        IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    private fun affectedFileResult(relativePath: String, content: String, simulatedContent: String): SimulationResult {
        val dir = myFixture.tempDirFixture.findOrCreateDir("src")
        PsiTestUtil.addSourceRoot(myFixture.module, dir, JavaSourceRootType.SOURCE)
        myFixture.tempDirFixture.createFile("src/$relativePath", content)
        val virtualFile = myFixture.tempDirFixture.getFile("src/$relativePath")!!
        return SimulationResult(
            kind = RefactorKind.RENAME,
            originalName = "Acme",
            newName = "Beta",
            affectedFiles = listOf(
                AffectedFile(
                    filePath = virtualFile.path,
                    originalText = content,
                    simulatedText = simulatedContent,
                    referenceCount = 0,
                    importCount = 0,
                ),
            ),
            conflicts = emptyList(),
        )
    }

    fun testResolvesTheSingleModuleOwningTheAffectedFile() {
        val result = affectedFileResult("Acme.java", "class Acme {}", "class Beta {}")

        val roots = ModuleSourceRootResolver.resolveModuleSourceRoots(result, project)

        assertEquals(1, roots.size)
        assertTrue(roots.containsKey(module.name))
    }

    fun testBuildsOverridesRelativeToTheModuleRoot() {
        val result = affectedFileResult("Acme.java", "class Acme {}", "class Beta {}")
        val roots = ModuleSourceRootResolver.resolveModuleSourceRoots(result, project)

        val overrides = ModuleSourceRootResolver.buildOverrides(result, project, roots)

        assertEquals(1, overrides.size)
        val (relativePath, content) = overrides.entries.single()
        assertTrue("expected override key to start with the module name, was: $relativePath", relativePath.startsWith("${module.name}/"))
        assertEquals("class Beta {}", content)
    }

    fun testReturnsEmptyMapsForNoAffectedFiles() {
        val result = SimulationResult(
            kind = RefactorKind.RENAME,
            originalName = "Acme",
            newName = "Beta",
            affectedFiles = emptyList(),
            conflicts = emptyList(),
        )

        val roots = ModuleSourceRootResolver.resolveModuleSourceRoots(result, project)

        assertTrue(roots.isEmpty())
        assertTrue(ModuleSourceRootResolver.buildOverrides(result, project, roots).isEmpty())
    }
}
