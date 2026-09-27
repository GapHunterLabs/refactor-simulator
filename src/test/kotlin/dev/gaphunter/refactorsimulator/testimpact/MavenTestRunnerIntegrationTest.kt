package dev.gaphunter.refactorsimulator.testimpact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs REAL Maven against real tiny projects: a single module, and a multi-module reactor in which the module
 * being tested depends on the one that was changed. Skipped unless a Maven installation is named in the
 * environment variable `REFACTOR_SIMULATOR_MAVEN_HOME` (IntelliJ IDEA's own `plugins/maven/lib/maven3` works), so
 * a normal `./gradlew test` never needs Maven or the network -- Maven downloads Surefire and JUnit on first use.
 */
class MavenTestRunnerIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mavenHome = System.getenv("REFACTOR_SIMULATOR_MAVEN_HOME")

    private fun needMaven() = assumeTrue("set REFACTOR_SIMULATOR_MAVEN_HOME to run against real Maven", mavenHome != null)

    private fun write(root: Path, relative: String, text: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text.trimIndent() + "\n")
    }

    private val junitDependency = """
        <dependencies>
            <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency>
        </dependencies>
    """

    private fun calc(operator: String) = """
        package acme;
        public class Calc { public int apply(int a, int b) { return a $operator b; } }
    """

    private val calcTest = """
        package acme;
        import org.junit.Test;
        import static org.junit.Assert.assertEquals;
        public class CalcTest {
            @Test public void adds() { assertEquals(4, new Calc().apply(2, 2)); }
        }
    """

    private fun singleModuleProject(): Path {
        val root = tmp.newFolder("single").toPath()
        write(root, "pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>acme</groupId><artifactId>calc</artifactId><version>1.0</version>
                <properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                $junitDependency
            </project>
        """)
        write(root, "src/main/java/acme/Calc.java", calc("+"))
        write(root, "src/test/java/acme/CalcTest.java", calcTest)
        return root
    }

    private fun runner(project: Path) = MavenTestRunner(project.toString(), bundledMavenHome = { null })

    private var sandboxes = 0

    private fun sandbox() = tmp.newFolder("sandbox${sandboxes++}").toPath()

    @Test
    fun aPassingTestIsReportedAsPass() {
        needMaven()
        val project = singleModuleProject()
        val outcomes = runner(project).run(sandbox(), mapOf("calc" to project), emptyMap())
        assertEquals(listOf(TestOutcome("acme.CalcTest", TestStatus.PASS)), outcomes)
    }

    @Test
    fun aSimulatedChangeThatBreaksTheTestIsReportedAsFailAndTheRealProjectIsUntouched() {
        needMaven()
        val project = singleModuleProject()
        val before = Files.readString(project.resolve("src/main/java/acme/Calc.java"))

        val outcomes = runner(project).run(sandbox(), mapOf("calc" to project), mapOf("calc/src/main/java/acme/Calc.java" to calc("-")))

        val outcome = outcomes.single { it.displayName == "acme.CalcTest" }
        assertEquals(TestStatus.FAIL, outcome.status)
        assertNotNull(outcome.truncatedOutput)
        assertTrue(outcome.truncatedOutput!!, "adds" in outcome.truncatedOutput!!)
        assertEquals("the real project was never written to", before, Files.readString(project.resolve("src/main/java/acme/Calc.java")))
        assertTrue("no target/ was created in the real project", !Files.exists(project.resolve("target")))
    }

    @Test
    fun aCompileErrorIsReportedAsAFailedRun() {
        needMaven()
        val project = singleModuleProject()
        val outcomes = runner(project).run(sandbox(), mapOf("calc" to project), mapOf("calc/src/main/java/acme/Calc.java" to "package acme; public class Calc { this does not compile }"))
        assertTrue(outcomes.toString(), outcomes.any { it.displayName == "(Maven run failed)" && it.status == TestStatus.OTHER })
    }

    private fun multiModuleProject(): Path {
        val root = tmp.newFolder("multi").toPath()
        write(root, "pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>acme</groupId><artifactId>parent</artifactId><version>1.0</version><packaging>pom</packaging>
                <properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <modules><module>core</module><module>app</module><module>docs</module></modules>
            </project>
        """)
        write(root, "core/pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <parent><groupId>acme</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                <artifactId>core</artifactId>
            </project>
        """)
        write(root, "core/src/main/java/acme/Calc.java", calc("+"))
        write(root, "app/pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <parent><groupId>acme</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                <artifactId>app</artifactId>
                <dependencies>
                    <dependency><groupId>acme</groupId><artifactId>core</artifactId><version>1.0</version></dependency>
                    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency>
                </dependencies>
            </project>
        """)
        write(root, "app/src/test/java/acme/CalcTest.java", calcTest)
        // A module that must NOT be part of the copy: were it included, this broken pom would fail the whole build.
        write(root, "docs/pom.xml", "this is not a pom")
        return root
    }

    @Test
    fun aMultiModuleProjectRunsOnlyTheAffectedModulesAndTheirDependents() {
        needMaven()
        val project = multiModuleProject()
        val roots = mapOf("core" to project.resolve("core"), "app" to project.resolve("app"))

        val ok = runner(project).run(sandbox(), roots, emptyMap())
        assertEquals(listOf(TestOutcome("acme.CalcTest", TestStatus.PASS)), ok)

        // Change the module the test does not live in: the dependent module's test must catch it.
        val broken = runner(project).run(sandbox(), roots, mapOf("core/src/main/java/acme/Calc.java" to calc("-")))
        assertEquals(TestStatus.FAIL, broken.single { it.displayName == "acme.CalcTest" }.status)
    }

    @Test
    fun aRunWithNoMavenAvailableSaysSo() {
        val project = singleModuleProject()
        // Only meaningful when nothing points at a Maven: skip if the environment provides one.
        assumeTrue(System.getenv("MAVEN_HOME") == null && System.getenv("M2_HOME") == null && mavenHome == null)
        assumeTrue((System.getenv("PATH") ?: "").split(java.io.File.pathSeparatorChar).none { Files.exists(Path.of(it).resolve("mvn")) || Files.exists(Path.of(it).resolve("mvn.cmd")) })
        val outcomes = runner(project).run(sandbox(), mapOf("calc" to project), emptyMap())
        assertEquals("(Maven not found)", outcomes.single().displayName)
    }
}
