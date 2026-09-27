package dev.gaphunter.refactorsimulator.testimpact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class MavenSandboxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- build system ----------

    @Test
    fun aPomAloneMeansMaven() {
        val dir = tmp.newFolder().toPath()
        Files.writeString(dir.resolve("pom.xml"), "<project/>")
        assertEquals(BuildSystem.MAVEN, BuildSystemDetector.detect(dir))
    }

    @Test
    fun anyGradleBuildFileWinsOverAPom() {
        for (file in listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
            val dir = tmp.newFolder().toPath()
            Files.writeString(dir.resolve("pom.xml"), "<project/>")
            Files.writeString(dir.resolve(file), "")
            assertEquals(file, BuildSystem.GRADLE, BuildSystemDetector.detect(dir))
        }
    }

    @Test
    fun noBuildFileMeansUnknown() {
        assertEquals(BuildSystem.UNKNOWN, BuildSystemDetector.detect(tmp.newFolder().toPath()))
    }

    // ---------- layout ----------

    @Test
    fun relativeDirIsStringBasedAndSlashNormalized() {
        assertEquals("", MavenSandbox.relativeDir("C:\\work\\app", "C:/work/app/"))
        assertEquals("core", MavenSandbox.relativeDir("C:\\work\\app", "C:\\work\\app\\core"))
        assertEquals("services/a", MavenSandbox.relativeDir("/work/app/", "/work/app/services/a"))
        assertNull(MavenSandbox.relativeDir("/work/app", "/work/other/core"))
        assertNull("a sibling with the same prefix is not inside the project", MavenSandbox.relativeDir("/work/app", "/work/app-two/core"))
    }

    @Test
    fun aSingleModuleProjectIsCopiedWhole() {
        val layout = MavenSandbox.layoutFor("/work/app", mapOf("app" to Paths.get("/work/app")))!!
        assertTrue(layout.isSingleModule)
        assertEquals(mapOf("app" to ""), layout.moduleDirs)
    }

    @Test
    fun aMultiModuleProjectKeepsItsDirectoryLayout() {
        val layout = MavenSandbox.layoutFor("/work/app", mapOf("core" to Paths.get("/work/app/core"), "web" to Paths.get("/work/app/web")))!!
        assertFalse(layout.isSingleModule)
        assertEquals(mapOf("core" to "core", "web" to "web"), layout.moduleDirs)
        assertTrue(layout.parentPomDirs.isEmpty())
    }

    @Test
    fun aNestedModuleCarriesTheParentPomsBetweenItAndTheRoot() {
        val layout = MavenSandbox.layoutFor("/work/app", mapOf("a" to Paths.get("/work/app/services/team/a")))!!
        assertEquals(setOf("services", "services/team"), layout.parentPomDirs)
    }

    @Test
    fun aModuleOutsideTheProjectCannotBeReproduced() {
        assertNull(MavenSandbox.layoutFor("/work/app", mapOf("x" to Paths.get("/elsewhere/x"))))
    }

    // ---------- <modules> ----------

    private val rootPom = """
        <project>
            <groupId>acme</groupId>
            <artifactId>parent</artifactId>
            <packaging>pom</packaging>
            <modules>
                <module>core</module>
                <module>web</module>
                <module>docs</module>
            </modules>
            <properties><x>1</x></properties>
        </project>
    """.trimIndent()

    @Test
    fun theModulesListIsReducedToTheCopiedModules() {
        val trimmed = MavenSandbox.trimModules(rootPom, listOf("core", "web"))!!
        assertTrue(trimmed, "<module>core</module>" in trimmed && "<module>web</module>" in trimmed)
        assertFalse(trimmed, "<module>docs</module>" in trimmed)
        assertTrue("everything else is untouched", "<properties><x>1</x></properties>" in trimmed && "<artifactId>parent</artifactId>" in trimmed)
    }

    @Test
    fun aPomWithoutModulesIsNotTrimmed() {
        assertNull(MavenSandbox.trimModules("<project><artifactId>solo</artifactId></project>", listOf("core")))
    }

    // ---------- overrides ----------

    @Test
    fun overridesAreRekeyedFromModuleNameToDirectory() {
        val layout = MavenSandbox.layoutFor("/work/app", mapOf("core-lib" to Paths.get("/work/app/core"), "web" to Paths.get("/work/app/services/web")))!!
        val remapped = MavenSandbox.remapOverrides(
            mapOf("core-lib/src/main/java/A.java" to "a", "web/src/main/java/B.java" to "b", "unknown/C.java" to "c"),
            layout,
        )
        assertEquals(mapOf("core/src/main/java/A.java" to "a", "services/web/src/main/java/B.java" to "b"), remapped)
    }

    @Test
    fun aModuleThatIsTheProjectRootKeepsThePathWithinIt() {
        val layout = MavenSandbox.layoutFor("/work/app", mapOf("app" to Paths.get("/work/app")))!!
        assertEquals(mapOf("src/main/java/A.java" to "a"), MavenSandbox.remapOverrides(mapOf("app/src/main/java/A.java" to "a"), layout))
    }

    @Test
    fun theLongestMatchingModuleNameWins() {
        val layout = MavenSandbox.layoutFor("/w", mapOf("app" to Paths.get("/w/app"), "app/extra" to Paths.get("/w/extra")))!!
        assertEquals(mapOf("extra/X.java" to "x"), MavenSandbox.remapOverrides(mapOf("app/extra/X.java" to "x"), layout))
    }

    // ---------- Surefire reports ----------

    private fun report(attributes: String, body: String = "") =
        """<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.acme.CalcTest" $attributes>$body</testsuite>"""

    @Test
    fun aPassingClassIsPass() {
        val outcome = MavenSandbox.parseSurefireReport(report("""tests="3" errors="0" skipped="0" failures="0"""", """<testcase name="a" classname="com.acme.CalcTest"/>"""))!!
        assertEquals(TestOutcome("com.acme.CalcTest", TestStatus.PASS), outcome)
    }

    @Test
    fun aFailureIsFailWithItsMessage() {
        val outcome = MavenSandbox.parseSurefireReport(
            report("""tests="2" errors="0" skipped="0" failures="1"""", """<testcase name="adds" classname="com.acme.CalcTest"><failure message="expected:&lt;4&gt; but was:&lt;0&gt;" type="AssertionFailedError">trace</failure></testcase>"""),
        )!!
        assertEquals(TestStatus.FAIL, outcome.status)
        assertEquals("adds: expected:<4> but was:<0>", outcome.truncatedOutput)
    }

    @Test
    fun anErrorIsFailToo() {
        val outcome = MavenSandbox.parseSurefireReport(
            report("""tests="1" errors="1" skipped="0" failures="0"""", """<testcase name="boom" classname="com.acme.CalcTest"><error message="NullPointerException" type="java.lang.NullPointerException">at x</error></testcase>"""),
        )!!
        assertEquals(TestStatus.FAIL, outcome.status)
        assertEquals("boom: NullPointerException", outcome.truncatedOutput)
    }

    @Test
    fun aClassOfOnlySkippedTestsIsOther() {
        assertEquals(TestStatus.OTHER, MavenSandbox.parseSurefireReport(report("""tests="2" errors="0" skipped="2" failures="0""""))!!.status)
        assertEquals(TestStatus.OTHER, MavenSandbox.parseSurefireReport(report("""tests="0" errors="0" skipped="0" failures="0""""))!!.status)
    }

    @Test
    fun aLongMessageIsCut() {
        val long = "x".repeat(2_000)
        val outcome = MavenSandbox.parseSurefireReport(
            report("""tests="1" errors="0" skipped="0" failures="1"""", """<testcase name="t" classname="c"><failure message="$long"/></testcase>"""),
        )!!
        assertEquals(500, outcome.truncatedOutput!!.length)
    }

    @Test
    fun garbageAndOtherXmlAreIgnored() {
        assertNull(MavenSandbox.parseSurefireReport("not xml"))
        assertNull(MavenSandbox.parseSurefireReport("<project/>"))
        assertNull(MavenSandbox.parseSurefireReport("""<testsuite tests="1"/>"""))
    }

    @Test
    fun aDoctypeIsRefused() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE testsuite [<!ENTITY x SYSTEM "file:///etc/passwd">]><testsuite name="a" tests="1" failures="0">&x;</testsuite>"""
        assertNull(MavenSandbox.parseSurefireReport(xxe))
    }

    // ---------- finding Maven ----------

    private fun fakeMaven(name: String): Path {
        val home = tmp.newFolder(name).toPath()
        Files.createDirectories(home.resolve("boot"))
        Files.createFile(home.resolve("boot").resolve("plexus-classworlds-2.7.0.jar"))
        return home
    }

    @Test
    fun anExplicitOverrideBeatsEverythingElse() {
        val explicit = fakeMaven("explicit")
        val env = fakeMaven("env")
        val found = MavenSandbox.findMavenHome(mapOf("MAVEN_HOME" to env.toString()), emptyList(), fakeMaven("bundled"), explicit.toString())
        assertEquals(explicit, found)
    }

    @Test
    fun mavenHomeEnvironmentThenPathThenBundled() {
        val fromEnv = fakeMaven("fromEnv")
        val fromPath = fakeMaven("fromPath")
        Files.createDirectories(fromPath.resolve("bin"))
        Files.createFile(fromPath.resolve("bin").resolve("mvn.cmd"))
        val bundled = fakeMaven("bundled")

        assertEquals(fromEnv, MavenSandbox.findMavenHome(mapOf("MAVEN_HOME" to fromEnv.toString()), listOf(fromPath.resolve("bin")), bundled))
        assertEquals(fromEnv, MavenSandbox.findMavenHome(mapOf("M2_HOME" to fromEnv.toString()), listOf(fromPath.resolve("bin")), bundled))
        assertEquals(fromPath, MavenSandbox.findMavenHome(emptyMap(), listOf(fromPath.resolve("bin")), bundled))
        assertEquals(bundled, MavenSandbox.findMavenHome(emptyMap(), emptyList(), bundled))
    }

    @Test
    fun aCandidateWithoutTheLauncherIsSkipped() {
        val broken = tmp.newFolder("broken").toPath()
        val bundled = fakeMaven("bundled")
        assertEquals(bundled, MavenSandbox.findMavenHome(mapOf("MAVEN_HOME" to broken.toString()), emptyList(), bundled))
        assertNull(MavenSandbox.findMavenHome(mapOf("MAVEN_HOME" to broken.toString()), emptyList(), null))
    }

    @Test
    fun theCommandStartsMavenThroughItsLauncherInTheSandbox() {
        val home = fakeMaven("cmd")
        val launcher = MavenSandbox.launcherJar(home)!!
        val command = MavenSandbox.command("/jdk/bin/java", home, launcher, Paths.get("/sandbox"))
        assertEquals("/jdk/bin/java", command.first())
        assertTrue(command.any { it.startsWith("-Dmaven.multiModuleProjectDirectory=") && it.endsWith("sandbox") })
        assertTrue("org.codehaus.plexus.classworlds.launcher.Launcher" in command)
        assertNotNull(command.firstOrNull { it.startsWith("-Dclassworlds.conf=") })
        assertTrue("test" in command && "-Dmaven.test.failure.ignore=true" in command)
    }
}
