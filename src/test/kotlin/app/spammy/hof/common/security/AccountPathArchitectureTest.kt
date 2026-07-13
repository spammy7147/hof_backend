package app.spammy.hof.common.security

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.test.Test
import kotlin.test.assertTrue

class AccountPathArchitectureTest {
    @Test
    fun controllersNeverAcceptAccountIdFromApiPath() {
        val controllerRoot = Path.of("src/main/kotlin/app/spammy/hof")
        val violations = Files.walk(controllerRoot).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { path -> path.extension == "kt" && path.toString().contains("/controller/") }
                .filter { path ->
                    val source = Files.readString(path)
                    source.contains("/api/accounts") ||
                        Regex("""@PathVariable\s+accountId""").containsMatchIn(source)
                }
                .map(Path::toString)
                .toList()
        }

        assertTrue(violations.isEmpty(), "accountId path/controller violations: $violations")
    }
}
