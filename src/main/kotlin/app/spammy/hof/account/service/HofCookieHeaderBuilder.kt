package app.spammy.hof.account.service

import org.springframework.stereotype.Component

@Component
/**
 * DB에 저장된 쿠키 Map을 HTTP Cookie 헤더 문자열로 변환한다.
 */
class HofCookieHeaderBuilder {
    /**
     * `name=value; name2=value2` 형태의 Cookie 헤더 값을 만든다.
     */
    fun build(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
}
