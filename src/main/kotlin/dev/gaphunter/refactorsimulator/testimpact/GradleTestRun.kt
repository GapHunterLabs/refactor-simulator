package dev.gaphunter.refactorsimulator.testimpact

import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.ResultHandler
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.test.JvmTestKind
import org.gradle.tooling.events.test.JvmTestOperationDescriptor
import org.gradle.tooling.events.test.TestFailureResult
import org.gradle.tooling.events.test.TestFinishEvent
import org.gradle.tooling.events.test.TestSuccessResult
import org.gradle.tooling.model.GradleProject
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One Gradle test run through the Tooling API, against the isolated copy [GradleSandbox] prepared. No IDE state:
 * the directory, the test file and its class are all it needs, so it is exercised against a real build in
 * GradleTestRunIntegrationTest.
 */
object GradleTestRun {

    private const val TIMEOUT_MINUTES = 5L

    fun failed(reason: String) = TestOutcome("(Gradle run failed)", TestStatus.OTHER, reason.take(1500))

    /**
     * Asks Gradle which project owns [relativeTestFile] (the build's own project tree, not a guess from directory
     * names), then runs only [testClassName] through that project's `test` task. Only real test methods become
     * outcomes; the build failing for another reason becomes a "(Gradle run failed)" outcome with Gradle's message
     * and the end of its error output (a compilation error in the simulated code shows up there).
     */
    fun run(buildDir: Path, relativeTestFile: String, testClassName: String): List<TestOutcome> {
        val outcomes = mutableListOf<TestOutcome>()
        val errors = ByteArrayOutputStream()
        val connector = GradleConnector.newConnector().forProjectDirectory(buildDir.toFile())
        try {
            connector.connect().use { connection: ProjectConnection ->
                val projectDirs = mutableMapOf<String, String>()
                fun collect(gradleProject: GradleProject) {
                    projectDirs[GradleSandbox.relativeTo(buildDir.toString(), gradleProject.projectDirectory.path) ?: ""] =
                        gradleProject.path
                    gradleProject.children.forEach { collect(it) }
                }
                collect(connection.getModel(GradleProject::class.java))
                val gradlePath = GradleSandbox.projectPathFor(projectDirs, relativeTestFile)
                    ?: return listOf(failed("no project of this Gradle build contains $relativeTestFile"))

                val cancellation = GradleConnector.newCancellationTokenSource()
                val latch = CountDownLatch(1)
                var failure: Throwable? = null
                // TestLauncher, not newBuild().withArguments("--tests", ...): the Tooling API rejects task options as
                // build arguments ("Unknown command-line option '--tests'", caught by GradleTestRunIntegrationTest).
                connection.newTestLauncher()
                    .withTaskAndTestClasses(GradleSandbox.testTask(gradlePath), listOf(testClassName))
                    .withCancellationToken(cancellation.token())
                    .setStandardError(errors)
                    .addProgressListener(
                        { event: ProgressEvent ->
                            val descriptor = (event as? TestFinishEvent)?.descriptor as? JvmTestOperationDescriptor
                            if (descriptor != null && descriptor.jvmTestKind == JvmTestKind.ATOMIC) {
                                val result = (event as TestFinishEvent).result
                                val status = when (result) {
                                    is TestSuccessResult -> TestStatus.PASS
                                    is TestFailureResult -> TestStatus.FAIL
                                    else -> TestStatus.OTHER
                                }
                                val message = (result as? TestFailureResult)?.failures?.firstOrNull()?.message?.take(500)
                                val owner = descriptor.className?.substringAfterLast('.') ?: ""
                                outcomes += TestOutcome("$owner.${descriptor.methodName ?: descriptor.displayName}", status, message)
                            }
                        },
                        OperationType.TEST,
                    )
                    .run(object : ResultHandler<Void> {
                        override fun onComplete(result: Void?) = latch.countDown()
                        override fun onFailure(e: GradleConnectionException) {
                            failure = e
                            latch.countDown()
                        }
                    })

                if (!latch.await(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    cancellation.cancel()
                    return listOf(failed("Gradle didn't finish within $TIMEOUT_MINUTES minutes"))
                }
                failure?.let { outcomes += failed(reasonOf(it, errors)) }
            }
        } catch (e: Exception) {
            // Connecting or reading the project model failed: the build doesn't even configure in the copy.
            return listOf(failed(reasonOf(e, errors)))
        } finally {
            connector.disconnect()
        }
        return outcomes
    }

    /** Gradle's message chain plus the last lines of its error output, where the real cause usually is. */
    private fun reasonOf(e: Throwable, errors: ByteArrayOutputStream): String {
        val chain = generateSequence(e) { it.cause }.mapNotNull { it.message }.distinct().take(4).joinToString("\n")
        val tail = errors.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.takeLast(15).joinToString("\n")
        return if (tail.isEmpty()) chain else "$chain\n\n$tail"
    }
}
