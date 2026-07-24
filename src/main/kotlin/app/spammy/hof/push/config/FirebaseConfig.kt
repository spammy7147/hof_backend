package app.spammy.hof.push.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("prod")
class FirebaseConfig {
    @Bean
    fun firebaseCredentials(
        @Value("\${GOOGLE_APPLICATION_CREDENTIALS:}") credentialsPath: String,
    ): GoogleCredentials = FirebaseCredentialLoader(credentialsPath).load()

    @Bean
    fun firebaseApp(credentials: GoogleCredentials): FirebaseApp = FirebaseApp.getApps().firstOrNull() ?: FirebaseApp.initializeApp(
        FirebaseOptions.builder()
            .setCredentials(credentials)
            .build(),
    )

    @Bean
    fun firebaseMessaging(firebaseApp: FirebaseApp): FirebaseMessaging = FirebaseMessaging.getInstance(firebaseApp)
}
