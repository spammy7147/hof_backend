package app.spammy.hof.auth.entity

import app.spammy.hof.account.entity.HofAccountEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

/**
 * 한 번 발급된 불투명 refresh token의 수명 주기를 저장한다.
 *
 * 원문 토큰 대신 SHA-256 해시만 저장한다. [familyId]는 로그인 한 번에서 시작해 회전으로 파생된
 * 토큰들을 묶으며, 과거 토큰 재사용이 확인되면 같은 패밀리를 한꺼번에 폐기하는 기준이 된다.
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshTokenEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity,

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    val tokenHash: String,

    @Column(name = "family_id", nullable = false, length = 36)
    val familyId: String,

    @Column(name = "client_type", nullable = false, length = 20)
    val clientType: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "rotated_at")
    var rotatedAt: Instant? = null,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
)
