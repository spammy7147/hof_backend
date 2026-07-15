package app.spammy.hof.automation.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * 자동화의 멱등 request key, 준비 완료 실행 payload와 실행 당시 모듈 인스턴스를 보존하는 action 기록이다.
 *
 * [payloadJson]은 실제 캐릭터 ID·패턴 슬롯까지 해석한 요청이므로 성공 후에도 덮어쓰지 않는다.
 * [moduleConfig]는 모듈 삭제 시 DB의 `ON DELETE SET NULL`로 비워지지만 action과 payload는 남는다.
 */
@Entity
@Table(
    name = "automation_action_runs",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_automation_action_runs_request_key", columnNames = ["request_key"]),
    ],
)
class AutomationActionRunEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    var job: AutomationJobEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_config_id")
    var moduleConfig: AutomationModuleConfigEntity? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "module_type", nullable = false, length = 50)
    var moduleType: AutomationModuleType,

    @Column(name = "action_type", nullable = false, length = 80)
    var actionType: String,

    @Column(name = "action_key", length = 255)
    var actionKey: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    var status: AutomationActionStatus,

    @Column(name = "request_key", nullable = false, length = 255)
    var requestKey: String,

    @Column(name = "payload_json", nullable = false, columnDefinition = "text")
    var payloadJson: String,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant? = null,

    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "started_at")
    var startedAt: Instant? = null,

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)
