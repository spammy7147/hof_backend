package app.spammy.hof.automation.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
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
 * 이전 프로필 job의 멱등 request key와 실행 payload를 보존하는 호환 action 기록이다.
 *
 * 범용 모듈 runtime entity는 제거했으므로 `module_config_id`와 `module_type`은 원시 호환 값으로만 읽는다.
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

    @Column(name = "module_config_id")
    var moduleConfigId: Long? = null,

    @Column(name = "module_type", nullable = false, length = 50)
    var moduleType: String,

    @Column(name = "action_type", nullable = false, length = 80)
    var actionType: String,

    @Column(name = "action_key", length = 255)
    var actionKey: String?,

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
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
