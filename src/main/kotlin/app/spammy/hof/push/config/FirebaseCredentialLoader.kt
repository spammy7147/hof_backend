package app.spammy.hof.push.config

import com.google.auth.oauth2.GoogleCredentials
import java.nio.file.Files
import java.nio.file.Path

class FirebaseCredentialLoader(
    private val credentialsPath: String,
) {
    fun load(): GoogleCredentials {
        check(credentialsPath.isNotBlank()) {
            "GOOGLE_APPLICATION_CREDENTIALS must point to the mounted Firebase service account file."
        }
        val path = Path.of(credentialsPath)
        check(Files.isRegularFile(path) && Files.isReadable(path)) {
            "Firebase credentials path must be a readable file."
        }
        check(Files.size(path) > 0L) { "Firebase credentials file must be non-empty." }
        return try {
            Files.newInputStream(path).use(GoogleCredentials::fromStream)
        } catch (exception: Exception) {
            throw IllegalStateException("Firebase credentials file is malformed.", exception)
        }
    }
}
