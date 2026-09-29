package dev.gaphunter.refactorsimulator.testimpact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/** [GradleSandbox]: the mirror of the Gradle build the isolated run uses, and which project owns a file. */
class GradleSandboxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(root: Path, relative: String, text: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun read(root: Path, relative: String) = Files.readString(root.resolve(relative))

    private fun build(): Path {
        val root = tmp.newFolder("build-root").toPath()
        write(root, "settings.gradle.kts", "include(\"core\")")
        write(root, "build.gradle.kts", "// root build")
        write(root, "gradle/wrapper/gradle-wrapper.properties", "distributionUrl=x")
        write(root, "core/build.gradle.kts", "// core build")
        write(root, "core/src/main/java/a/Calc.java", "class Calc { int calcTotal() { return 1; } }")
        write(root, "core/build/classes/Calc.class", "compiled")
        write(root, ".gradle/cache", "cache")
        write(root, ".idea/workspace.xml", "ide state")
        return root
    }

    @Test
    fun `the whole build is mirrored, build scripts included, outputs and IDE state left out`() {
        val root = build()
        val temp = tmp.newFolder("temp").toPath()

        GradleSandbox.sync(root, temp, emptyMap())

        for (rel in listOf("settings.gradle.kts", "build.gradle.kts", "gradle/wrapper/gradle-wrapper.properties",
            "core/build.gradle.kts", "core/src/main/java/a/Calc.java")) {
            assertTrue("$rel should be copied", Files.exists(temp.resolve(rel)))
        }
        for (rel in listOf("core/build", ".gradle", ".idea")) {
            assertFalse("$rel should not be copied", Files.exists(temp.resolve(rel)))
        }
    }

    @Test
    fun `simulated texts are written on top of the mirror`() {
        val root = build()
        val temp = tmp.newFolder("temp").toPath()

        GradleSandbox.sync(root, temp, mapOf("core/src/main/java/a/Calc.java" to "class Calc { int totalDue() { return 1; } }"))

        assertTrue(read(temp, "core/src/main/java/a/Calc.java").contains("totalDue"))
        assertTrue("the real project is never touched", read(root, "core/src/main/java/a/Calc.java").contains("calcTotal"))
    }

    @Test
    fun `a file overridden by the previous simulation is restored when the next one leaves it alone`() {
        val root = build()
        val temp = tmp.newFolder("temp").toPath()
        GradleSandbox.sync(root, temp, mapOf("core/src/main/java/a/Calc.java" to "class Calc { int totalDue() { return 1; } }"))

        GradleSandbox.sync(root, temp, emptyMap())

        assertEquals(read(root, "core/src/main/java/a/Calc.java"), read(temp, "core/src/main/java/a/Calc.java"))
    }

    @Test
    fun `a file deleted from the project disappears from the mirror, build outputs in the mirror are kept`() {
        val root = build()
        val temp = tmp.newFolder("temp").toPath()
        GradleSandbox.sync(root, temp, emptyMap())
        write(temp, "core/build/classes/Calc.class", "compiled in the mirror")
        Files.delete(root.resolve("core/src/main/java/a/Calc.java"))

        GradleSandbox.sync(root, temp, emptyMap())

        assertFalse(Files.exists(temp.resolve("core/src/main/java/a/Calc.java")))
        assertTrue("incremental outputs survive", Files.exists(temp.resolve("core/build/classes/Calc.class")))
    }

    @Test
    fun `the deepest project directory owns a file, the root owns the rest`() {
        val dirs = mapOf("" to ":", "order-core" to ":order-core", "order-core/sub" to ":order-core:sub")

        assertEquals(":order-core", GradleSandbox.projectPathFor(dirs, "order-core/src/test/java/X.java"))
        assertEquals(":order-core:sub", GradleSandbox.projectPathFor(dirs, "order-core/sub/src/test/X.kt"))
        assertEquals(":", GradleSandbox.projectPathFor(dirs, "src/test/java/X.java"))
        assertEquals(":", GradleSandbox.projectPathFor(dirs, "order-core-extra/src/X.java"))
        assertNull(GradleSandbox.projectPathFor(mapOf("app" to ":app"), "lib/src/X.java"))
    }

    @Test
    fun `test task of the root and of a nested project`() {
        assertEquals(":test", GradleSandbox.testTask(":"))
        assertEquals(":order-core:test", GradleSandbox.testTask(":order-core"))
    }

    @Test
    fun `paths relative to the build root, Windows separators and drive-letter case included`() {
        assertEquals("order-core/src/X.java", GradleSandbox.relativeTo("C:/ghl-demo/acme", "C:/ghl-demo/acme/order-core/src/X.java"))
        assertEquals("order-core/src/X.java", GradleSandbox.relativeTo("C:\\ghl-demo\\acme\\", "c:/ghl-demo/acme/order-core/src/X.java"))
        assertNull(GradleSandbox.relativeTo("C:/ghl-demo/acme", "C:/ghl-demo/acme-other/X.java"))
    }
}
