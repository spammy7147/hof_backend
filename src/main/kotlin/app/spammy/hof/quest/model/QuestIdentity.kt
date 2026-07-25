package app.spammy.hof.quest.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

data class QuestIdentity(val questKey: String, val displayCode: String, val name: String)

object QuestIdentityFactory {
    private val whitespace = Regex("\\s+")
    private val keyPattern = Regex("q:[0-9a-f]{64}")

    fun create(displayCode: String, name: String): QuestIdentity {
        val code = displayCode.trim().uppercase(Locale.ROOT)
        val normalizedName = whitespace.replace(name, " ").trim()
        require(code.isNotBlank()) { "Quest display code must not be blank." }
        require(normalizedName.isNotBlank()) { "Quest name must not be blank." }
        val bytes = "$code\u0000$normalizedName".toByteArray(StandardCharsets.UTF_8)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        return QuestIdentity("q:$digest", code, normalizedName)
    }

    fun matches(questKey: String, displayCode: String, name: String): Boolean =
        keyPattern.matches(questKey) && create(displayCode, name).questKey == questKey
}
