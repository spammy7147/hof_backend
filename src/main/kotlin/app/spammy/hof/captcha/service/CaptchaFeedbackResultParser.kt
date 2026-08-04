package app.spammy.hof.captcha.service

import org.jsoup.Jsoup
import org.springframework.stereotype.Component

data class CaptchaFeedbackLabel(
    val correctText: String,
    val accepted: Boolean,
)

/** 현재 플레이어와 제출값이 모두 정확히 일치하는 단 하나의 검문 기록만 학습 label로 채택한다. */
@Component
class CaptchaFeedbackResultParser {
    fun parse(
        html: String,
        currentPlayerName: String,
        submittedText: String,
    ): CaptchaFeedbackLabel? {
        if (!submittedText.isValidCaptchaText()) return null
        val normalizedPlayerName = currentPlayerName.canonicalPlayerName()
        if (normalizedPlayerName.isBlank() || normalizedPlayerName.equals(UNKNOWN_PLAYER, ignoreCase = true)) {
            return null
        }

        val pageText = Jsoup.parse(html).text()
        val matches = HISTORY_ENTRY.findAll(pageText)
            .mapNotNull { match ->
                val playerName = match.groups[PLAYER_GROUP]?.value?.canonicalPlayerName() ?: return@mapNotNull null
                val submitted = match.groups[SUBMITTED_GROUP]?.value ?: return@mapNotNull null
                val correct = match.groups[CORRECT_GROUP]?.value ?: return@mapNotNull null
                val outcome = match.groups[OUTCOME_GROUP]?.value ?: return@mapNotNull null
                if (playerName != normalizedPlayerName || submitted != submittedText) return@mapNotNull null
                if (!correct.isValidCaptchaText()) return@mapNotNull null

                val accepted = outcome == SUCCESS_OUTCOME
                if (accepted != (submitted == correct)) return@mapNotNull null
                CaptchaFeedbackLabel(correctText = correct, accepted = accepted)
            }
            .toList()

        return matches.singleOrNull()
    }

    private fun String.withoutWhitespace(): String = filterNot(Char::isWhitespace)

    private fun String.canonicalPlayerName(): String =
        LEADING_TITLES.replace(withoutWhitespace(), "")

    private fun String.isValidCaptchaText(): Boolean =
        length == CAPTCHA_LENGTH && all(ALLOWED_CHARACTERS::contains)

    private companion object {
        const val CAPTCHA_LENGTH = 5
        const val ALLOWED_CHARACTERS =
            "ABCDEFGHJKLMNPRSTUVWXYZabcdefghjkmnprstuvwxyz23456789"
        const val UNKNOWN_PLAYER = "Unknown"
        const val SUCCESS_OUTCOME = "성공"
        const val PLAYER_GROUP = "player"
        const val OUTCOME_GROUP = "outcome"
        const val SUBMITTED_GROUP = "submitted"
        const val CORRECT_GROUP = "correct"

        val HISTORY_ENTRY = Regex(
            """\[(?<$PLAYER_GROUP>[^]]+)]\s*검문\s*통과에\s*(?<$OUTCOME_GROUP>성공|실패)했다\s*""" +
                """\(\s*대답\s*:\s*(?<$SUBMITTED_GROUP>[$ALLOWED_CHARACTERS]{$CAPTCHA_LENGTH})\s*""" +
                """정답\s*:\s*(?<$CORRECT_GROUP>[$ALLOWED_CHARACTERS]{$CAPTCHA_LENGTH})\s*\)""",
        )
        val LEADING_TITLES = Regex("^(?:《[^》]+》)+")
    }
}
