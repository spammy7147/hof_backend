package app.spammy.hof.auth.service

import app.spammy.hof.auth.config.AuthProperties
import app.spammy.hof.common.time.TimeProvider
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** 인증된 로컬 계정 ID를 subject로 갖는 단기 Access JWT를 발급한다. */
@Service
class JwtTokenService(
    private val encoder: JwtEncoder,
    private val properties: AuthProperties,
    private val timeProvider: TimeProvider,
) {
    /** 현재 시각부터 설정된 수명(기본 30분) 동안 유효한 HS256 JWT를 만든다. */
    fun issue(accountId: Long): IssuedAccessToken {
        val issuedAt = timeProvider.now()
        val expiresAt = issuedAt.plus(properties.accessTokenTtl)
        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .audience(listOf(properties.audience))
            .subject(accountId.toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .id(UUID.randomUUID().toString())
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val value = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return IssuedAccessToken(value, expiresAt)
    }
}

/** 클라이언트가 만료 전에 갱신할 수 있도록 토큰 원문과 만료 시각을 함께 반환한다. */
data class IssuedAccessToken(
    val value: String,
    val expiresAt: Instant,
)
