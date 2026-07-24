package app.spammy.hof.external.parser

import org.jsoup.Jsoup
import org.springframework.stereotype.Component

data class SharedBattleCooldownNotice(val remainingSeconds: Long)

@Component
class SharedBattleCooldownParser {
    fun parse(html: String): SharedBattleCooldownNotice? {
        val text = Jsoup.parse(html).text()
        if (!SHARED_COOLDOWN_MARKER.containsMatchIn(text)) return null
        val seconds = REMAINING_SECONDS.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: DEFAULT_REMAINING_SECONDS
        return SharedBattleCooldownNotice(seconds)
    }

    private companion object {
        const val DEFAULT_REMAINING_SECONDS = 60L
        val SHARED_COOLDOWN_MARKER =
            Regex("대형\\s*데이터를\\s*읽는\\s*전투를\\s*실행한\\s*상태입니다")
        val REMAINING_SECONDS = Regex("(\\d+)\\s*초\\s*후\\s*전투\\s*가능")
    }
}
