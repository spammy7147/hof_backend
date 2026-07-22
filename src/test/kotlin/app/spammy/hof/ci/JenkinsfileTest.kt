package app.spammy.hof.ci

import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals

class JenkinsfileTest {
    @Test
    fun `remote deployment heredoc terminator starts at column one`() {
        val terminators = Path.of("Jenkinsfile")
            .readLines()
            .filter { it.trim() == "REMOTE_SCRIPT" }

        assertEquals(listOf("REMOTE_SCRIPT"), terminators)
    }
}
