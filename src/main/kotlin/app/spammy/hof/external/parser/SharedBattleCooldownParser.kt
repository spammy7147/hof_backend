package app.spammy.hof.external.parser

import org.jsoup.Jsoup
import org.springframework.stereotype.Component

data class SharedBattleCooldownNotice(val remainingSeconds: Long)

@Component
class SharedBattleCooldownParser {
    fun parse(html: String): SharedBattleCooldownNotice? {
        val text = Jsoup.parse(html).text()
        if (!LARGE_RAID_MARKER.containsMatchIn(text)) return null
        val seconds = REMAINING_SECONDS.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: return null
        return SharedBattleCooldownNotice(seconds)
    }

    private companion object {
        val LARGE_RAID_MARKER = Regex("대형\\s*레이드")
        val REMAINING_SECONDS = Regex("(\\d+)\\s*초\\s*후\\s*전투\\s*가능")
    }
}
