package app.spammy.hof.account.entity

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Instant

/**
 * 앱에서 관리하는 HOF 계정과 로그인 비밀번호를 저장하는 JPA 엔티티다.
 */
@Entity
@Table(name = "hof_accounts")
class HofAccountEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "login_id", nullable = false, unique = true)
    var loginId: String,

    @Column(name = "encrypted_password", nullable = false)
    var encryptedPassword: String,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,

    @OneToMany(mappedBy = "account", cascade = [CascadeType.ALL], orphanRemoval = true)
    val cookies: MutableList<HofCookieEntity> = mutableListOf(),
)
