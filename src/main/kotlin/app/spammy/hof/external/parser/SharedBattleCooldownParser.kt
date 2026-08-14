package app.spammy.hof.external.parser

import org.springframework.stereotype.Component

data class SharedBattleCooldownNotice(val remainingSeconds: Long)

@Component
class SharedBattleCooldownParser {
    fun parse(html: String): SharedBattleCooldownNotice? {
        val text = HofHtmlParser.parse(html).text()
        UnionBattleCooldownTextParser.parseRemainingSeconds(text)?.let { seconds ->
            return SharedBattleCooldownNotice(seconds)
        }
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

internal object UnionBattleCooldownTextParser {
    fun parseRemainingSeconds(text: String): Long? {
        val match = UNION_COOLDOWN.find(text) ?: return null
        val first = match.groupValues[1].toLongOrNull() ?: return null
        val second = match.groupValues[2].toLongOrNull() ?: return null
        val third = match.groupValues[3].toLongOrNull()
        if (second !in 0L..59L || third != null && third !in 0L..59L) return null

        return runCatching {
            if (third == null) {
                Math.addExact(Math.multiplyExact(first, 60L), second)
            } else {
                Math.addExact(
                    Math.multiplyExact(first, 3_600L),
                    Math.addExact(Math.multiplyExact(second, 60L), third),
                )
            }
        }.getOrNull()?.takeIf { it > 0L }
    }

    private val UNION_COOLDOWN = Regex(
        """Time\s*left\s*to\s*next\s*battle\s*:\s*(\d+)\s*:\s*(\d{1,2})(?:\s*:\s*(\d{1,2}))?""",
        RegexOption.IGNORE_CASE,
    )
}
