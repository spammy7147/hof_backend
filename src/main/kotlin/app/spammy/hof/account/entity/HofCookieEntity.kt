package app.spammy.hof.account.entity

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
 * HOF 원본 서버 로그인 후 받은 세션 쿠키를 계정별로 저장하는 JPA 엔티티다.
 */
@Entity
@Table(name = "hof_cookies")
class HofCookieEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity,

    @Column(name = "name", nullable = false)
    var name: String,

    @Column(name = "cookie_value", nullable = false, columnDefinition = "text")
    var value: String,

    @Column(name = "domain")
    var domain: String? = null,

    @Column(name = "path")
    var path: String? = null,

    @Column(name = "expires_at")
    var expiresAt: Instant? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)
