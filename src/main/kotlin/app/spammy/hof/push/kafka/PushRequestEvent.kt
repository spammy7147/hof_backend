package app.spammy.hof.push.kafka

data class PushRequestEvent(
    val eventId: String,
    val accountId: Long,
    val type: String,
    val challengeId: Long? = null,
)
