package dev.gaphunter.refactorsimulator.testimpact

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** What a Gradle run needs: the build's root, the clicked test (file + class), and every simulated file's text. */
data class GradleTestRequest(
    val buildRoot: String,
    val testFilePath: String,
    val testClassName: String,
    val simulatedTexts: Map<String, String>,
)

/**
 * Runs a related test against an isolated copy of the project with the simulated change applied, materialized into
 * a temp directory -- the real project is never touched.
 *
 * - Gradle: the whole build is mirrored ([GradleSandbox]) and Gradle itself says which project owns the test, then
 *   only that project's `test` task runs, filtered to the test class. (Until 2026.2.1 the copy was built from
 *   IntelliJ modules; with Gradle those are per source set -- `src/main`, `src/test` -- so the copy had no build
 *   scripts and every run failed.)
 * - Maven: [MavenTestRunner], from the IntelliJ modules, which for Maven are the Maven modules themselves.
 * - **The temp directory is created once per IDE session/project and reused across invocations, never a fresh
 *   UUID-named dir per simulation.** A fresh dir per run means a cold Gradle daemon every time (~24-30s measured);
 *   the same dir reused lets Gradle recognize the same project and reuse its daemon, and keeps its build outputs
 *   incremental.
 *
 * One [IsolatedTestRunner] instance is meant to be owned by
 * [dev.gaphunter.refactorsimulator.ui.ImpactPanel] (one panel per tool
 * window, one tool window per project) and reused across every
 * simulation in that project, not constructed fresh per simulation --
 * that's what makes the temp-dir reuse actually happen.
 */
class IsolatedTestRunner(private val project: Project) {

    private var sessionTempDir: Path? = null

    /**
     * Runs the related test [gradle] describes (Gradle projects) or the modules in [moduleSourceRoots] with
     * [affectedFileOverrides] applied (Maven projects), in the session's isolated copy. Returns null if the temp dir
     * couldn't be prepared; never throws for a normal test failure (that's a TestOutcome with FAIL, not an exception),
     * and a run that can't start is an outcome named "(...)" that carries the reason.
     */
    fun runRelatedTests(
        moduleSourceRoots: Map<String, Path>,
        affectedFileOverrides: Map<String, String>,
        gradle: GradleTestRequest? = null,
    ): List<TestOutcome>? {
        val tempDir = ensureSessionTempDir() ?: return null

        // A Maven project has no Gradle build to drive: it gets its own runner (same idea, a Maven process instead
        // of the Tooling API). A project with any Gradle build file keeps the Gradle path below.
        val base = project.basePath
        if (base != null && BuildSystemDetector.detect(Paths.get(base)) == BuildSystem.MAVEN) {
            return MavenTestRunner(project).run(tempDir, moduleSourceRoots, affectedFileOverrides)
        }

        val request = gradle ?: return listOf(GradleTestRun.failed("the Gradle build that contains this test couldn't be determined"))
        val relativeTest = GradleSandbox.relativeTo(request.buildRoot, request.testFilePath)
            ?: return listOf(GradleTestRun.failed("${request.testFilePath} is outside the Gradle build at ${request.buildRoot}"))
        val overrides = request.simulatedTexts.mapNotNull { (path, text) ->
            GradleSandbox.relativeTo(request.buildRoot, path)?.let { it to text }
        }.toMap()
        try {
            GradleSandbox.sync(Paths.get(request.buildRoot), tempDir, overrides)
        } catch (e: IOException) {
            return listOf(GradleTestRun.failed("couldn't copy the build into the isolated directory: ${e.message}"))
        }
        thisLogger().info("isolated Gradle run: ${request.testClassName} with ${overrides.size} simulated file(s): " +
            overrides.keys.joinToString { it.substringAfterLast('/') })
        return GradleTestRun.run(tempDir, relativeTest, request.testClassName)
    }

    /** Called from [dev.gaphunter.refactorsimulator.apply.DiscardAction]. */
    fun disposeSessionTempDir() {
        val dir = sessionTempDir ?: return
        defensiveDelete(dir)
        sessionTempDir = null
    }

    private fun ensureSessionTempDir(): Path? {
        sessionTempDir?.let { return it }
        return try {
            val dir = Files.createTempDirectory("refactor-simulator-${project.locationHash}-")
            sessionTempDir = dir
            dir
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Windows-safe defensive delete (plan §1.0 safeguard): retries with
     * short backoff, then deleteOnExit for anything still locked --
     * verified in the spike, where it never actually needed the retry
     * path (0 locked files across every run), but production code keeps
     * it as a real safeguard, not a theoretical one.
     */
    private fun defensiveDelete(root: Path, maxAttempts: Int = 3) {
        val stillLocked = mutableListOf<Path>()
        for (attempt in 1..maxAttempts) {
            stillLocked.clear()
            try {
                Files.walkFileTree(
                    root,
                    object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            try {
                                Files.delete(file)
                            } catch (e: IOException) {
                                stillLocked.add(file)
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                            try {
                                Files.deleteIfExists(dir)
                            } catch (e: IOException) {
                                stillLocked.add(dir)
                            }
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
            } catch (e: IOException) {
                // walkFileTree itself failed (e.g. root already gone) -- nothing left to retry.
            }

            if (stillLocked.isEmpty()) return
            try {
                Thread.sleep(300L * attempt)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }

        stillLocked.forEach { it.toFile().deleteOnExit() }
    }
}
