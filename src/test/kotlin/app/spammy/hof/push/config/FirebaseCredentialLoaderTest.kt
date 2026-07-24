package app.spammy.hof.push.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class FirebaseCredentialLoaderTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `blank credential path is rejected`() {
        val error = assertFailsWith<IllegalStateException> { FirebaseCredentialLoader(" ").load() }
        assertTrue(error.message.orEmpty().contains("GOOGLE_APPLICATION_CREDENTIALS"))
    }

    @Test
    fun `missing credential file is rejected`() {
        val error = assertFailsWith<IllegalStateException> {
            FirebaseCredentialLoader(directory.resolve("missing.json").toString()).load()
        }
        assertTrue(error.message.orEmpty().contains("readable file"))
    }

    @Test
    fun `empty credential file is rejected`() {
        val file = Files.createFile(directory.resolve("empty.json"))
        val error = assertFailsWith<IllegalStateException> { FirebaseCredentialLoader(file.toString()).load() }
        assertTrue(error.message.orEmpty().contains("non-empty"))
    }

    @Test
    fun `malformed credential json is rejected without exposing file contents`() {
        val file = directory.resolve("malformed.json")
        file.writeText("not-json-private-material")

        val error = assertFailsWith<IllegalStateException> { FirebaseCredentialLoader(file.toString()).load() }

        assertTrue(error.message.orEmpty().contains("malformed"))
        assertFalse(error.message.orEmpty().contains("private-material"))
    }
}
