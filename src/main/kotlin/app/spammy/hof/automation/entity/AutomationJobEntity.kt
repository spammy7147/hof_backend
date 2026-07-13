package app.spammy.hof.automation.entity

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
 * 실제 실행 중인 자동화 작업이 어느 계정의 어느 프로필을 실행하는지와 진행 상태를 저장한다.
 *
 * 프로필 설정을 JSON으로 복제하지 않고 [profile] FK를 유지한다. 실행 시점 스냅샷은 실행 엔진 설계
 * 범위이므로 여기서는 현재 단계와 시작·종료 시각만 구조화된 열로 관리한다.
 */
@Entity
@Table(name = "automation_jobs")
class AutomationJobEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id", nullable = false)
    var profile: AutomationProfileEntity,

    @Column(name = "status", nullable = false)
    var status: String,

    @Column(name = "current_step_index", nullable = false)
    var currentStepIndex: Int,

    @Column(name = "message", columnDefinition = "text")
    var message: String?,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "started_at")
    var startedAt: Instant?,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Column(name = "finished_at")
    var finishedAt: Instant?,
)
