package app.spammy.hof.auth.service

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.auth.dto.AuthClientType
import org.springframework.stereotype.Service

/** HOF 원본 인증 성공과 로컬 Access/Refresh Token 발급을 하나의 애플리케이션 흐름으로 묶는다. */
@Service
class AuthService(
    private val accountService: HofAccountService,
    private val jwtTokenService: JwtTokenService,
    private val refreshTokenService: RefreshTokenService,
) {
    /** HOF 로그인 성공 후 저장된 계정을 기준으로 새 토큰 패밀리를 발급한다. */
    fun login(loginId: String, password: String, clientType: AuthClientType): AuthTokenPair {
        val account = accountService.authenticate(loginId, password)
        return AuthTokenPair(
            accessToken = jwtTokenService.issue(account.id),
            refreshToken = refreshTokenService.issue(account, clientType.name),
            clientType = clientType,
        )
    }

    /** refresh token을 회전한 뒤 같은 계정 ID를 subject로 갖는 새 Access JWT를 발급한다. */
    fun refresh(rawRefreshToken: String): AuthTokenPair {
        val refresh = refreshTokenService.rotate(rawRefreshToken)
        return AuthTokenPair(
            accessToken = jwtTokenService.issue(refresh.accountId),
            refreshToken = refresh,
            clientType = AuthClientType.valueOf(refresh.clientType),
        )
    }

    /** 현재 로그인 패밀리를 폐기한다. */
    fun logout(rawRefreshToken: String?) = refreshTokenService.logout(rawRefreshToken)
}

/** Controller가 플랫폼에 맞는 응답과 쿠키를 조립할 수 있도록 발급 결과를 묶는다. */
data class AuthTokenPair(
    val accessToken: IssuedAccessToken,
    val refreshToken: IssuedRefreshToken,
    val clientType: AuthClientType,
)
