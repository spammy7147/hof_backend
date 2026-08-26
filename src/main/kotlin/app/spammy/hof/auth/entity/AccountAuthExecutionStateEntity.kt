package app.spammy.hof.auth.entity

import app.spammy.hof.account.entity.HofAccountEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapsId
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

/** 앱 refresh-token 세션 유무가 백엔드 실행을 허용하는지 나타내는 계정 전역 상태다. */
@Entity
@Table(name = "account_auth_execution_states")
class AccountAuthExecutionStateEntity(
    @Id
    @Column(name = "account_id")
    val accountId: Long,

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "account_id")
    val account: HofAccountEntity,

    @Column(name = "suspended", nullable = false)
    var suspended: Boolean = false,

    @Column(name = "suspended_at")
    var suspendedAt: Instant? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Version
    @Column(name = "version")
    var version: Long? = null,
)
