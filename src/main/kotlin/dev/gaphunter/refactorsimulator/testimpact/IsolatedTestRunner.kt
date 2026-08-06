package dev.gaphunter.refactorsimulator.testimpact

import com.intellij.openapi.project.Project
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.ResultHandler
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.test.TestFailureResult
import org.gradle.tooling.events.test.TestFinishEvent
import org.gradle.tooling.events.test.TestSuccessResult
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs the tests [RelatedTestFinder] identified, against a copy of only
 * the minimal module subset (the affected module + modules that depend
 * on it), materialized into a temp directory.
 *
 * Design confirmed by a disposable spike (2026-07-28, see the plan and
 * KNOWN_ISSUES.md for full numbers) before this class was written:
 * - Gradle Tooling API (not ProjectTaskManager -- never needed it) can
 *   run `test` against an arbitrary temp directory.
 * - **The temp directory must be created once per IDE session/project
 *   and reused across invocations, never a fresh UUID-named dir per
 *   simulation.** A fresh dir per run means a cold Gradle daemon every
 *   time (~24-30s measured); the same dir reused lets Gradle recognize
 *   the same project and reuse its daemon (~0.6-1.1s measured after the
 *   first run). This is the single highest-leverage design decision in
 *   this class -- get it wrong and the feature feels broken even though
 *   it "works."
 * - Partial-module copy (excluding unrelated modules, with a trimmed
 *   settings.gradle.kts generated for the copy) resolves without
 *   classpath errors -- verified against a 3-module project where two
 *   modules had a real dependency edge and the third was deliberately
 *   unrelated.
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
     * Runs [testFilePaths] against [moduleSourceRoots] (module dir name
     * -> absolute path in the real project) with [affectedFileOverrides]
     * (absolute temp-dir-relative path -> simulated file content)
     * applied on top of the copy. Returns null if the temp dir couldn't
     * be prepared; never throws for a normal test failure (that's a
     * TestOutcome with FAIL, not an exception).
     */
    fun runRelatedTests(
        moduleSourceRoots: Map<String, Path>,
        affectedFileOverrides: Map<String, String>,
    ): List<TestOutcome>? {
        val tempDir = ensureSessionTempDir() ?: return null

        copyModules(moduleSourceRoots, tempDir)
        writeTrimmedSettings(tempDir, moduleSourceRoots.keys)
        applyOverrides(tempDir, affectedFileOverrides)

        return runGradleTest(tempDir)
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

    private fun copyModules(moduleSourceRoots: Map<String, Path>, tempDir: Path) {
        for ((moduleName, sourcePath) in moduleSourceRoots) {
            val targetPath = tempDir.resolve(moduleName)
            if (Files.exists(targetPath)) {
                defensiveDelete(targetPath)
            }
            copyDirectory(sourcePath, targetPath)
        }
    }

    private fun writeTrimmedSettings(tempDir: Path, moduleNames: Set<String>) {
        val includeLine = moduleNames.joinToString(", ") { "\"$it\"" }
        Files.writeString(
            tempDir.resolve("settings.gradle.kts"),
            "rootProject.name = \"refactor-simulator-sandbox\"\ninclude($includeLine)\n",
        )
        // The root build.gradle.kts (shared plugin config for all
        // subprojects) is copied verbatim by the caller alongside
        // moduleSourceRoots -- IsolatedTestRunner only owns the parts
        // that differ per simulation (settings.gradle.kts, overrides).
    }

    private fun applyOverrides(tempDir: Path, overrides: Map<String, String>) {
        for ((relativePath, content) in overrides) {
            val target = tempDir.resolve(relativePath)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
    }

    private fun runGradleTest(tempDir: Path): List<TestOutcome> {
        val outcomes = mutableListOf<TestOutcome>()
        val latch = CountDownLatch(1)
        var failureMessage: String? = null

        val connector = GradleConnector.newConnector().forProjectDirectory(tempDir.toFile())
        try {
            connector.connect().use { connection: ProjectConnection ->
                connection.newBuild()
                    .forTasks("test")
                    .addProgressListener(
                        { event: ProgressEvent ->
                            if (event is TestFinishEvent) {
                                val status = when (event.result) {
                                    is TestSuccessResult -> TestStatus.PASS
                                    is TestFailureResult -> TestStatus.FAIL
                                    else -> TestStatus.OTHER
                                }
                                outcomes += TestOutcome(event.descriptor.displayName, status)
                            }
                        },
                        OperationType.TEST,
                    )
                    .run(object : ResultHandler<Void> {
                        override fun onComplete(result: Void?) = latch.countDown()
                        override fun onFailure(failure: org.gradle.tooling.GradleConnectionException) {
                            failureMessage = failure.message
                            latch.countDown()
                        }
                    })

                latch.await(60, TimeUnit.SECONDS)
            }
        } finally {
            connector.disconnect()
        }

        failureMessage?.let {
            outcomes += TestOutcome("(Gradle run failed)", TestStatus.OTHER, truncatedOutput = it.take(500))
        }
        return outcomes
    }

    private fun copyDirectory(source: Path, target: Path) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val rel = source.relativize(dir).toString()
                    if (rel == ".gradle" || rel.startsWith(".gradle${File.separator}") ||
                        rel == "build" || rel.startsWith("build${File.separator}")
                    ) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    Files.createDirectories(target.resolve(source.relativize(dir)))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.copy(file, target.resolve(source.relativize(file)))
                    return FileVisitResult.CONTINUE
                }
            },
        )
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
