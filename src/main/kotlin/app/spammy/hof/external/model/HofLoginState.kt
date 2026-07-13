package app.spammy.hof.external.model

/**
 * HOF 홈 HTML을 보고 현재 로그인 상태를 판별한 결과다.
 */
data class HofLoginState(
    val isLoggedIn: Boolean,
    val hasLoginForm: Boolean,
    val hasCharacterLinks: Boolean,
    val hasLogoutText: Boolean,
    val hasStatusHeader: Boolean,
)
