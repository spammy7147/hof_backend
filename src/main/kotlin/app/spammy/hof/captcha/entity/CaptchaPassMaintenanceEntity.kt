package app.spammy.hof.captcha.entity

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

/** 계정 전역 통행증 관측과 다음 갱신 결정을 위한 영속 상태다. */
@Entity
@Table(name = "captcha_pass_maintenance")
class CaptchaPassMaintenanceEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    val account: HofAccountEntity,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,

    @Column(name = "auth_suspended", nullable = false)
    var authSuspended: Boolean = false,

    @Column(name = "pass_state", nullable = false, length = 20)
    var passState: String = PASS_UNKNOWN,

    @Column(name = "remaining_seconds")
    var remainingSeconds: Int? = null,

    @Column(name = "valid_until")
    var validUntil: Instant? = null,

    /** 응답 완료가 아니라 HOF 요청 시작 시각이다. 늦은 과거 응답을 거르는 관측 버전으로 사용한다. */
    @Column(name = "observed_at")
    var observedAt: Instant? = null,

    @Column(name = "next_refresh_at")
    var nextRefreshAt: Instant? = null,

    @Column(name = "last_attempt_at")
    var lastAttemptAt: Instant? = null,

    @Column(name = "last_result", length = 40)
    var lastResult: String? = null,

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,

    @Column(name = "lease_token", length = 100)
    var leaseToken: String? = null,

    @Column(name = "lease_until")
    var leaseUntil: Instant? = null,

    @Column(name = "run_phase", length = 20)
    var runPhase: String? = null,

    @Column(name = "manual_challenge_id")
    var manualChallengeId: Long? = null,

    @Column(name = "notification_key", length = 100)
    var notificationKey: String? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) {
    companion object {
        const val PASS_UNKNOWN = "UNKNOWN"
        const val PASS_VALID = "VALID"
        const val PASS_REQUIRED = "REQUIRED"
    }
}
