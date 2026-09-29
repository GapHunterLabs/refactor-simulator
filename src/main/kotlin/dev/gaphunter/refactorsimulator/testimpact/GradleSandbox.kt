package dev.gaphunter.refactorsimulator.testimpact

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * The isolated copy a Gradle project's related tests run in: the WHOLE Gradle build (settings, every build script,
 * the wrapper, version catalogs, sources), minus build outputs, with the simulated files written on top.
 *
 * The first implementation copied IntelliJ modules one by one. With Gradle, IntelliJ creates one module per source
 * set (`app.main`, `app.test`), whose content root is `src/main` or `src/test`: the copy had sources and no build
 * scripts at all, so every Gradle run failed -- on single-module projects too. Copying the build Gradle actually
 * reads, and asking Gradle itself which project owns a file, removes the guesswork.
 *
 * Pure file logic, no IDE APIs: unit-tested on temp directories.
 */
object GradleSandbox {

    /** Directories never copied: build outputs, caches and VCS/IDE state. */
    val SKIPPED_DIRS = setOf("build", "out", ".gradle", ".kotlin", ".git", ".idea", "node_modules")

    /**
     * Makes [temp] mirror [root] (files copied when missing or different in size/timestamp, files gone from [root]
     * deleted), leaves the build outputs already in [temp] alone so Gradle stays incremental, then writes
     * [overrides] (path relative to [root], with `/` -> simulated text) on top. A file overridden by an earlier
     * simulation has a newer timestamp than its original, so the mirror step restores it before the new overrides.
     */
    fun sync(root: Path, temp: Path, overrides: Map<String, String>) {
        val seen = mutableSetOf<Path>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root && dir.fileName.toString() in SKIPPED_DIRS) return FileVisitResult.SKIP_SUBTREE
                    Files.createDirectories(temp.resolve(root.relativize(dir).toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val rel = root.relativize(file).toString()
                    val target = temp.resolve(rel)
                    seen.add(target.normalize())
                    if (!Files.exists(target) || Files.size(target) != attrs.size() ||
                        Files.getLastModifiedTime(target) != attrs.lastModifiedTime()
                    ) {
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        deleteVanished(temp, seen)
        for ((rel, text) in overrides) {
            val target = temp.resolve(rel)
            Files.createDirectories(target.parent)
            Files.writeString(target, text)
        }
    }

    private fun deleteVanished(temp: Path, seen: Set<Path>) {
        Files.walkFileTree(
            temp,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir != temp && dir.fileName.toString() in SKIPPED_DIRS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (file.normalize() !in seen) Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /**
     * The Gradle project path (`:order-core`, or `:` for the root) that owns [relativeFile], given each project's
     * directory relative to the build root (`""` for the root) as Gradle reports it. The deepest directory wins, so a
     * nested project is chosen over its parent. Null when no project contains the file.
     */
    fun projectPathFor(projectDirs: Map<String, String>, relativeFile: String): String? {
        val file = relativeFile.replace('\\', '/').trimStart('/')
        return projectDirs.entries
            .filter { (dir, _) -> dir.isEmpty() || file.startsWith(dir.trimEnd('/') + "/") }
            .maxByOrNull { (dir, _) -> dir.length }
            ?.value
    }

    /** The `test` task of [gradlePath]: `:test` for the root project, `:a:b:test` otherwise. */
    fun testTask(gradlePath: String): String = if (gradlePath == ":") ":test" else "$gradlePath:test"

    /** Path of [file] relative to [root] with `/` separators, or null when [file] is outside [root]. */
    fun relativeTo(root: String, file: String): String? {
        val r = root.replace('\\', '/').trimEnd('/')
        val f = file.replace('\\', '/')
        return if (f.startsWith("$r/", ignoreCase = true)) f.substring(r.length + 1) else null
    }
}
