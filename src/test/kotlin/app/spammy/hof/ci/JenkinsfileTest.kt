package app.spammy.hof.ci

import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JenkinsfileTest {
    private val source = Path.of("Jenkinsfile").readText()

    @Test
    fun `remote deployment heredoc terminator starts at column one`() {
        val terminators = Path.of("Jenkinsfile")
            .readLines()
            .filter { it.trim() == "REMOTE_SCRIPT" }

        assertEquals(listOf("REMOTE_SCRIPT"), terminators)
    }

    @Test
    fun `firebase secret is mounted read only from a build versioned host file`() {
        listOf(
            "hof-spammy-fcm",
            "REMOTE_FIREBASE_FILE",
            "firebase-service-account-\${BUILD_NUMBER}.json",
            "dst=/run/secrets/firebase-service-account.json,readonly",
            "GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase-service-account.json",
        ).forEach { required -> assertTrue(source.contains(required), required) }
    }

    @Test
    fun `firebase deployment preserves rollback credential and cleans exact paths`() {
        listOf(
            "previous_secret_path",
            "test -s \"\$REMOTE_FIREBASE_FILE\"",
            "install -d -m 700",
            "install -m 600",
            "rm -f -- \"\$secret_path\"",
        ).forEach { required -> assertTrue(source.contains(required), required) }
        assertFalse(source.contains("rm -rf"))
    }
}
