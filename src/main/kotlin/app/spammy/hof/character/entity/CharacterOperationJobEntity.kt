package app.spammy.hof.character.entity

import app.spammy.hof.account.entity.HofAccountEntity
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
import java.time.Instant

enum class CharacterOperationType { DEEP_SYNC, RESTORE, TRANSFER }
enum class CharacterOperationStatus { PENDING, RUNNING, COMPLETED, FAILED, STOPPED }
enum class CharacterRecoveryStatus { NOT_STARTED, REQUIRED, RESTORING, RESTORED, UNAVAILABLE, ACCEPTED }

/** 오래 걸리는 캐릭터 작업의 요청, 체크포인트와 결과를 계정별로 보존한다. */
@Entity
@Table(name = "character_operation_jobs")
class CharacterOperationJobEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity,

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 30)
    var operationType: CharacterOperationType,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: CharacterOperationStatus = CharacterOperationStatus.PENDING,

    @Column(name = "source_character_id")
    var sourceCharacterId: Long? = null,

    @Column(name = "target_character_id", nullable = false)
    var targetCharacterId: Long,

    @Column(name = "request_payload", columnDefinition = "text")
    var requestPayload: String? = null,

    @Column(name = "progress_payload", nullable = false, columnDefinition = "text")
    var progressPayload: String = "[]",

    @Column(name = "completed_step_ids", nullable = false, columnDefinition = "text")
    var completedStepIds: String = "[]",

    @Column(name = "result_payload", columnDefinition = "text")
    var resultPayload: String? = null,

    @Column(name = "message", columnDefinition = "text")
    var message: String? = null,

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,

    /** null은 복원 원본 보존을 지원하기 전에 생성된 작업이다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "recovery_status", length = 20)
    var recoveryStatus: CharacterRecoveryStatus? = null,

    @Column(name = "automation_intent_revision")
    var automationIntentRevision: Long? = null,

    @Column(name = "resume_automation", nullable = false)
    var resumeAutomation: Boolean = false,

    @Column(name = "automation_released", nullable = false)
    var automationReleased: Boolean = false,

    @Column(name = "restore_attempt_limit", nullable = false)
    var restoreAttemptLimit: Int = 3,

    @Column(name = "recovery_review_token", length = 36)
    var recoveryReviewToken: String? = null,

    @Column(name = "recovery_review_fingerprint", length = 64)
    var recoveryReviewFingerprint: String? = null,

    @Column(name = "recovery_reviewed_at")
    var recoveryReviewedAt: Instant? = null,

    @Column(name = "recovery_accepted_at")
    var recoveryAcceptedAt: Instant? = null,
)
