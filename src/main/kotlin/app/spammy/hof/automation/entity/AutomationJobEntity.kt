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
import jakarta.persistence.Version
import java.time.Instant

/**
 * 실제 실행 중인 자동화 작업이 어느 계정의 어느 프로필을 실행하는지와 진행 상태를 저장한다.
 *
 * 프로필 설정을 JSON으로 복제하지 않고 [profile] FK를 유지한다. `current_module_config_id`는 이전 범용
 * 자동화 실행 기록을 읽기 위한 호환 열이며, 런타임 모듈 entity와 연결하지 않고 원시 ID로만 보존한다.
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

    @Column(name = "current_module", length = 50)
    var currentModule: String? = null,

    @Column(name = "current_module_config_id")
    var currentModuleConfigId: Long? = null,

    @Column(name = "current_action", length = 255)
    var currentAction: String? = null,

    @Column(name = "next_run_at")
    var nextRunAt: Instant? = null,

    @Column(name = "last_heartbeat_at")
    var lastHeartbeatAt: Instant? = null,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)
