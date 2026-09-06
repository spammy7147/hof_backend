package app.spammy.hof.ci

import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JenkinsfileTest {
    private val source = Path.of("Jenkinsfile").readText()

    @Test
    fun `Jenkins executes the same deployment entry point as the recovery tests`() {
        assertTrue(source.contains("python3 -\" < scripts/deploy_backend.py"))
        assertTrue(source.contains("command -v python3"))
    }

    @Test
    fun `Jenkins forwards the existing credential and ingress contract`() {
        listOf(
            "hof-spammy-fcm",
            "REMOTE_FIREBASE_FILE",
            "REMOTE_RELEASE_ENV_FILE",
            "HOF_AUTH_ALLOWED_ORIGIN_PATTERNS",
            "SERVER_FORWARD_HEADERS_STRATEGY",
            "StrictHostKeyChecking=yes",
        ).forEach { required -> assertTrue(source.contains(required), required) }
    }

    @Test
    fun `deployment recovers failures through the production entry point`() {
        val output = Files.createTempFile("hof-deployment-tests-", ".log")
        try {
            val process = ProcessBuilder("python3", "-B", "-m", "unittest", "discover", "-s", "scripts/tests", "-v")
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Deployment execution tests timed out: ${output.readText()}")
            }
            assertEquals(0, process.exitValue(), output.readText())
        } finally {
            Files.deleteIfExists(output)
        }
    }
}
