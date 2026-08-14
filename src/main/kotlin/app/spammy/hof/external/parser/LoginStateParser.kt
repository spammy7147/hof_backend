package app.spammy.hof.external.parser

import app.spammy.hof.external.model.HofLoginState
import org.springframework.stereotype.Component

@Component
/**
 * HOF HTML이 로그인된 상태인지 판단한다.
 */
class LoginStateParser {
    /**
     * 로그인 form, 캐릭터 링크, 로그아웃 텍스트, `menu2` 사용자 영역과 상태바 존재 여부로 로그인 상태를 추정한다.
     */
    fun parse(html: String): HofLoginState {
        val document = HofHtmlParser.parse(html)
        val text = document.text()
        val hasLoginForm = document.select("""input[name=id]""").isNotEmpty() &&
            document.select("""input[name=pass]""").isNotEmpty() &&
            document.select("""input[name=Login]""").isNotEmpty()
        val hasCharacterLinks = document.select("""[href*=char=], [action*=char=], [onclick*=char=]""").isNotEmpty() ||
            Regex("""[?&]char=\d+""").containsMatchIn(html)
        val hasLogoutText = Regex("""logout|logoff|로그아웃""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val hasUserHeader = document.select("#menu2").isNotEmpty()
        val hasStatusHeader = Regex("""Funds\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
            Regex("""Time\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(text)

        return HofLoginState(
            isLoggedIn = !hasLoginForm && (hasCharacterLinks || hasLogoutText || hasStatusHeader || hasUserHeader),
            hasLoginForm = hasLoginForm,
            hasCharacterLinks = hasCharacterLinks,
            hasLogoutText = hasLogoutText,
            hasStatusHeader = hasStatusHeader,
            hasUserHeader = hasUserHeader,
        )
    }
}
