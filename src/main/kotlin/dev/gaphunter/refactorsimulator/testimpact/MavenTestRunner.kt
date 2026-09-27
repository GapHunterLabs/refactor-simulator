package dev.gaphunter.refactorsimulator.testimpact

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * Runs the tests of a simulation in a copy of a MAVEN project -- the counterpart of [IsolatedTestRunner]'s
 * Gradle path, for the (majority of) Java projects that build with Maven.
 *
 * Same idea: only the affected modules and the modules that depend on them are copied to a temp directory,
 * the simulated files are written over the copy, and the tests run there, so the real project is never touched.
 * Unlike Gradle there is no Tooling API and no daemon to reuse, so it starts a Maven process (`java` + Maven's
 * class-worlds launcher, the same thing `mvn` does) from a Maven installation found by [MavenSandbox.findMavenHome]:
 * an explicit setting, `MAVEN_HOME`/`M2_HOME`, a `mvn` on the PATH, or the Maven bundled with IntelliJ IDEA -- so a
 * project needs no global Maven for this to work. Results come from the Surefire reports, one outcome per test class.
 *
 * Supported: a Maven project whose modules sit inside the project directory (single module, or a root aggregator
 * pom with modules below it, nested ones included). Anything else is reported as such, never guessed at.
 */
class MavenTestRunner(
    private val basePath: String?,
    /** The Maven bundled with IntelliJ IDEA, when its Maven plugin is enabled; a parameter so tests need no IDE. */
    private val bundledMavenHome: () -> Path? = ::ideBundledMavenHome,
) {
    constructor(project: Project) : this(project.basePath)

    fun run(tempDir: Path, moduleSourceRoots: Map<String, Path>, affectedFileOverrides: Map<String, String>): List<TestOutcome> {
        val base = basePath ?: return listOf(unsupported("The project has no directory on disk."))
        val layout = MavenSandbox.layoutFor(base, moduleSourceRoots)
            ?: return listOf(unsupported("A module lies outside the project directory, so it can't be reproduced in a copy."))
        val home = MavenSandbox.findMavenHome(System.getenv(), pathEntries(), bundledMavenHome(), System.getProperty("refactorsimulator.maven.home"))
            ?: return listOf(
                TestOutcome(
                    "(Maven not found)", TestStatus.OTHER,
                    "No Maven installation was found. Set MAVEN_HOME (or REFACTOR_SIMULATOR_MAVEN_HOME), put mvn on the PATH, " +
                        "or enable IntelliJ IDEA's Maven plugin, which bundles one.",
                ),
            )
        val launcher = MavenSandbox.launcherJar(home) ?: return listOf(unsupported("The Maven installation at $home has no launcher."))

        try {
            clear(tempDir)
            if (!copyProject(Paths.get(base), tempDir, layout)) {
                return listOf(unsupported("The root pom.xml declares no <modules>, but the affected code spans several modules."))
            }
            for ((relativePath, content) in MavenSandbox.remapOverrides(affectedFileOverrides, layout)) {
                val target = tempDir.resolve(relativePath)
                Files.createDirectories(target.parent)
                Files.writeString(target, content)
            }
        } catch (e: IOException) {
            return listOf(TestOutcome("(Could not prepare the sandbox)", TestStatus.OTHER, e.message?.take(500)))
        }

        val output = StringBuilder()
        val exit = try {
            execute(MavenSandbox.command(javaExecutable(), home, launcher, tempDir), tempDir, output)
        } catch (e: IOException) {
            return listOf(TestOutcome("(Maven run failed)", TestStatus.OTHER, e.message?.take(500)))
        }

        val outcomes = layout.moduleDirs.values.flatMap { dir -> readReports(tempDir.resolve(dir).resolve("target").resolve("surefire-reports")) }
        return when {
            exit == null -> outcomes + TestOutcome("(Maven run timed out)", TestStatus.OTHER, "Maven didn't finish within $TIMEOUT_SECONDS s.")
            exit != 0 -> outcomes + TestOutcome("(Maven run failed)", TestStatus.OTHER, output.toString().takeLast(500))
            else -> outcomes
        }
    }

    private fun unsupported(reason: String) = TestOutcome("(Maven sandbox not supported)", TestStatus.OTHER, reason)

    private fun readReports(reportsDir: Path): List<TestOutcome> {
        if (!Files.isDirectory(reportsDir)) return emptyList()
        return Files.list(reportsDir).use { stream ->
            stream.filter { it.fileName.toString().let { name -> name.startsWith("TEST-") && name.endsWith(".xml") } }
                .sorted()
                .map { MavenSandbox.parseSurefireReport(Files.readString(it)) }
                .toList()
                .filterNotNull()
        }
    }

    /** null = timed out (the process is killed). */
    private fun execute(command: List<String>, workingDir: Path, output: StringBuilder): Int? {
        val process = ProcessBuilder(command).directory(workingDir.toFile()).redirectErrorStream(true).start()
        val reader = Thread {
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) synchronized(output) { output.append(line).append('\n'); if (output.length > 8_000) output.delete(0, output.length - 4_000) }
            }
        }.also { it.isDaemon = true; it.start() }
        return if (process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            reader.join(2_000)
            process.exitValue()
        } else {
            // Maven forks the test JVM: kill the whole tree, not just the launcher.
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            null
        }
    }

    /** The whole temp dir is rebuilt on every run: there is no daemon to keep warm, and a stale report must never be read. */
    private fun clear(tempDir: Path) {
        if (!Files.isDirectory(tempDir)) return
        Files.list(tempDir).use { children -> children.forEach { deleteRecursively(it) } }
    }

    /** false when the layout needs a trimmed `<modules>` list but the root pom has none. */
    private fun copyProject(base: Path, tempDir: Path, layout: MavenSandbox.Layout): Boolean {
        if (layout.isSingleModule) {
            copyTree(base, tempDir)
            return true
        }
        val rootPom = Files.readString(base.resolve("pom.xml"))
        val trimmed = MavenSandbox.trimModules(rootPom, layout.moduleDirs.values) ?: return false
        Files.writeString(tempDir.resolve("pom.xml"), trimmed)
        val mavenConfig = base.resolve(".mvn")
        if (Files.isDirectory(mavenConfig)) copyTree(mavenConfig, tempDir.resolve(".mvn"))
        for (dir in layout.parentPomDirs) {
            val pom = base.resolve(dir).resolve("pom.xml")
            if (Files.exists(pom)) {
                Files.createDirectories(tempDir.resolve(dir))
                Files.copy(pom, tempDir.resolve(dir).resolve("pom.xml"))
            }
        }
        for (dir in layout.moduleDirs.values) copyTree(base.resolve(dir), tempDir.resolve(dir))
        return true
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != source && dir.fileName.toString() in SKIPPED_DIRECTORIES) return FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(target.resolve(source.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.copy(file, target.resolve(source.relativize(file).toString()))
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteRecursively(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                try { Files.delete(file) } catch (e: IOException) { file.toFile().deleteOnExit() }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                try { Files.deleteIfExists(dir) } catch (e: IOException) { dir.toFile().deleteOnExit() }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun javaExecutable(): String = Paths.get(System.getProperty("java.home"), "bin", if (File.separatorChar == '\\') "java.exe" else "java").toString()

    private fun pathEntries(): List<Path> =
        (System.getenv("PATH") ?: "").split(File.pathSeparatorChar).filter { it.isNotBlank() }.mapNotNull { runCatching { Paths.get(it) }.getOrNull() }

    private companion object {
        const val TIMEOUT_SECONDS = 300L
        val SKIPPED_DIRECTORIES = setOf("target", ".git", ".idea")
    }
}

/**
 * Maven bundled with IntelliJ IDEA's Maven plugin (`<IDE home>/plugins/maven/lib/maven3`), when the IDE ships it.
 * Found by path through [PathManager] on purpose: the plugin-lookup APIs are internal in recent IDEs and compile to a
 * `PluginId.Companion` reference that doesn't exist in 2024.3/2025.1, which verifyPlugin rejected. A missing directory
 * simply means "no bundled Maven" -- [MavenSandbox.launcherJar] checks it before use.
 */
private fun ideBundledMavenHome(): Path? =
    Paths.get(PathManager.getHomePath(), "plugins", "maven", "lib", "maven3").takeIf { Files.isDirectory(it) }
