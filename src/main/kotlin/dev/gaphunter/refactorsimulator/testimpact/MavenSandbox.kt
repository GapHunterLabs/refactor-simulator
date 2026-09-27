package dev.gaphunter.refactorsimulator.testimpact

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Which build tool owns the project the isolated test run has to drive. */
enum class BuildSystem { GRADLE, MAVEN, UNKNOWN }

object BuildSystemDetector {
    /** Gradle wins when both are present (a Gradle build that also keeps a pom for publishing is still a Gradle build). */
    fun detect(basePath: Path): BuildSystem {
        val hasGradle = listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts").any { Files.exists(basePath.resolve(it)) }
        if (hasGradle) return BuildSystem.GRADLE
        return if (Files.exists(basePath.resolve("pom.xml"))) BuildSystem.MAVEN else BuildSystem.UNKNOWN
    }
}

/**
 * The parts of running related tests in a copy of a MAVEN project that need no Maven and no IDE, so each is
 * tested on its own: where each module sits, how the root pom's `<modules>` list is trimmed to the copy, how
 * the module-name keyed overrides are re-keyed by directory, and how a Surefire report becomes a [TestOutcome].
 *
 * Why not just reuse the Gradle layout: a Maven module inherits from its parent pom through a RELATIVE path
 * (`../pom.xml`), so the copy has to keep the real directory layout and carry the parent poms with it.
 */
object MavenSandbox {

    /** The directories (relative to the project root, "" = the root itself) that make up the copy, plus the poms to carry along. */
    data class Layout(
        /** Module directory relative to the project root, keyed by the IDE module name. "" means the module IS the project root. */
        val moduleDirs: Map<String, String>,
        /** Directories whose `pom.xml` (only) must be copied because a module inherits from it: every ancestor of a nested module. */
        val parentPomDirs: Set<String>,
    ) {
        /** The whole project directory is one module: copy it as is, no `<modules>` list to trim. */
        val isSingleModule: Boolean get() = moduleDirs.values.any { it.isEmpty() }
    }

    /** null when a module lies outside the project directory: it can't be reproduced under a copy of the project. */
    fun layoutFor(basePath: String, moduleRoots: Map<String, Path>): Layout? {
        val moduleDirs = mutableMapOf<String, String>()
        for ((name, root) in moduleRoots) moduleDirs[name] = relativeDir(basePath, root.toString()) ?: return null
        val ancestors = mutableSetOf<String>()
        for (dir in moduleDirs.values) {
            var parent = dir.substringBeforeLast('/', "")
            while (dir.contains('/') && parent.isNotEmpty() && ancestors.add(parent)) parent = parent.substringBeforeLast('/', "")
        }
        return Layout(moduleDirs, ancestors)
    }

    /** String-based on purpose: two NIO providers can't be mixed in Path.relativize (see ModuleSourceRootResolver). */
    fun relativeDir(basePath: String, path: String): String? {
        val base = basePath.replace('\\', '/').trimEnd('/')
        val target = path.replace('\\', '/').trimEnd('/')
        if (target == base) return ""
        if (!target.startsWith("$base/")) return null
        return target.removePrefix("$base/")
    }

    /**
     * The root pom with its `<modules>` reduced to [dirs]. Null when the root pom declares no `<modules>`
     * (it is not an aggregator, so there is nothing to trim and the caller should not have asked).
     */
    fun trimModules(rootPom: String, dirs: Collection<String>): String? {
        val block = Regex("""<modules>.*?</modules>""", RegexOption.DOT_MATCHES_ALL)
        if (!block.containsMatchIn(rootPom)) return null
        val entries = dirs.filter { it.isNotEmpty() }.joinToString("\n") { "        <module>$it</module>" }
        return rootPom.replaceFirst(block, "<modules>\n$entries\n    </modules>")
    }

    /**
     * The overrides [ModuleSourceRootResolver.buildOverrides] made are keyed `moduleName/pathInModule`; in a Maven
     * copy the module lives at its own relative directory, so the key becomes `dir/pathInModule`
     * (or just `pathInModule` for a module that is the project root).
     */
    fun remapOverrides(overrides: Map<String, String>, layout: Layout): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for ((key, text) in overrides) {
            val moduleName = layout.moduleDirs.keys.filter { key.startsWith("$it/") }.maxByOrNull { it.length } ?: continue
            val inModule = key.removePrefix("$moduleName/")
            val dir = layout.moduleDirs.getValue(moduleName)
            out[if (dir.isEmpty()) inModule else "$dir/$inModule"] = text
        }
        return out
    }

    /**
     * One [TestOutcome] per Surefire report (one per test class), named by the class so the panel can match it to
     * the test file: FAIL if any test failed or errored, PASS if some ran and none did, OTHER if everything was skipped.
     */
    fun parseSurefireReport(xml: String): TestOutcome? {
        val document = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            return null
        }
        val suite = document.documentElement
        if (suite.tagName != "testsuite") return null
        val name = suite.getAttribute("name").ifEmpty { return null }
        val tests = suite.getAttribute("tests").toIntOrNull() ?: 0
        val skipped = suite.getAttribute("skipped").toIntOrNull() ?: 0
        val failed = (suite.getAttribute("failures").toIntOrNull() ?: 0) + (suite.getAttribute("errors").toIntOrNull() ?: 0)
        return when {
            failed > 0 -> TestOutcome(name, TestStatus.FAIL, firstFailure(suite))
            tests > 0 && skipped < tests -> TestOutcome(name, TestStatus.PASS)
            else -> TestOutcome(name, TestStatus.OTHER)
        }
    }

    private fun firstFailure(suite: Element): String? {
        for (tag in listOf("failure", "error")) {
            val node = suite.getElementsByTagName(tag).item(0) as? Element ?: continue
            val testcase = node.parentNode as? Element
            val where = testcase?.getAttribute("name")?.takeIf { it.isNotEmpty() }?.let { "$it: " } ?: ""
            val message = node.getAttribute("message").ifEmpty { node.textContent.trim().lineSequence().firstOrNull().orEmpty() }
            return (where + message).take(500)
        }
        return null
    }

    /**
     * Where to find a Maven installation, in order: an explicit override (system property or environment variable
     * `REFACTOR_SIMULATOR_MAVEN_HOME`), `MAVEN_HOME`/`M2_HOME`, a `mvn` on the PATH, then the Maven bundled with
     * IntelliJ IDEA. Each candidate must contain the class-worlds launcher that starts Maven.
     */
    fun findMavenHome(env: Map<String, String?>, path: List<Path>, bundled: Path?, override: String? = null): Path? {
        val candidates = mutableListOf<Path>()
        // .add(), not +=: a Path is itself an Iterable<Path>, so `+=` on a MutableList<Path> is ambiguous.
        listOfNotNull(override, env["REFACTOR_SIMULATOR_MAVEN_HOME"], env["MAVEN_HOME"], env["M2_HOME"]).forEach { candidates.add(Paths.get(it)) }
        for (dir in path) {
            if (Files.exists(dir.resolve("mvn")) || Files.exists(dir.resolve("mvn.cmd"))) candidates.add(dir.parent ?: continue)
        }
        bundled?.let { candidates.add(it) }
        return candidates.firstOrNull { launcherJar(it) != null }
    }

    /** `<home>/boot/plexus-classworlds-*.jar`, the jar whose main class starts Maven. */
    fun launcherJar(mavenHome: Path): Path? {
        val boot = mavenHome.resolve("boot")
        if (!Files.isDirectory(boot)) return null
        return Files.list(boot).use { stream ->
            stream.filter { it.fileName.toString().startsWith("plexus-classworlds") && it.fileName.toString().endsWith(".jar") }.findFirst().orElse(null)
        }
    }

    /** The command that runs the tests of the copy without a global `mvn`: java + class-worlds launcher, as `mvn` itself does. */
    fun command(javaExecutable: String, mavenHome: Path, launcher: Path, sandbox: Path): List<String> = listOf(
        javaExecutable,
        "-Dclassworlds.conf=" + mavenHome.resolve("bin").resolve("m2.conf"),
        "-Dmaven.home=$mavenHome",
        "-Dmaven.multiModuleProjectDirectory=$sandbox",
        "-Dfile.encoding=UTF-8",
        "-cp", launcher.toString(),
        "org.codehaus.plexus.classworlds.launcher.Launcher",
        // -B: no interactive prompts. failure.ignore: a failing TEST must not fail the build, so its report is read
        // like any other; a compile error still fails it, and is reported as such.
        "-B", "-q", "test", "-Dmaven.test.failure.ignore=true", "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false",
    )
}
