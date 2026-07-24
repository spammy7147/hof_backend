package app.spammy.hof.push.service

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("prod")
class FirebaseAndroidMessageSender(
    private val firebaseMessaging: FirebaseMessaging,
) {
    @Throws(FirebaseMessagingException::class)
    fun send(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>,
    ) {
        val message = Message.builder()
            .setToken(token)
            .setNotification(Notification.builder().setTitle(title).setBody(body).build())
            .putAllData(data)
            .setAndroidConfig(
                AndroidConfig.builder()
                    .setNotification(AndroidNotification.builder().setChannelId(CHANNEL_ID).build())
                    .build(),
            )
            .build()
        firebaseMessaging.send(message)
    }

    private companion object {
        const val CHANNEL_ID = "automation-alerts"
    }
}
