package app.spammy.hof.automation.convergence

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
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
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

@Entity
@Table(name = "automation_action_attempts")
class AutomationActionAttemptEntity(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    val account: HofAccountEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "automation_entry_id")
    val entry: AutomationEntryEntity?,

    @Column(name = "execution_identity", nullable = false, length = 128)
    val executionIdentity: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "action_kind", nullable = false, length = 50)
    val actionKind: AutomationActionKind,

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 50)
    val scopeKind: AutomationIsolationScopeKind,

    @Column(name = "scope_key", nullable = false, length = 200)
    val scopeKey: String,

    @Column(name = "policy_version", nullable = false, length = 80)
    val policyVersion: String,

    @Column(name = "baseline_fingerprint", nullable = false, length = 128)
    val baselineFingerprint: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "submitted_at")
    var submittedAt: Instant? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

@Entity
@Table(name = "automation_action_convergences")
class ActionConvergenceEntity(
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attempt_id", nullable = false, unique = true)
    val attempt: AutomationActionAttemptEntity,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 50)
    val scopeKind: AutomationIsolationScopeKind,

    @Column(name = "scope_key", nullable = false, length = 200)
    val scopeKey: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 30)
    var result: ActionConvergenceResult? = null,

    @Column(name = "active_marker")
    var activeMarker: Int? = ACTIVE,

    @Column(name = "successful_observation_count", nullable = false)
    var successfulObservationCount: Int = 0,

    @Column(name = "first_pending_at")
    var firstPendingAt: Instant? = null,

    @Column(name = "next_probe_at")
    var nextProbeAt: Instant? = null,

    @Column(name = "reason_code", length = 100)
    var reasonCode: String? = null,

    @Column(name = "evidence_case_id", length = 64)
    var evidenceCaseId: String? = null,

    @Column(name = "suppression_released_at")
    var suppressionReleasedAt: Instant? = null,

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    companion object {
        const val ACTIVE = 1
    }
}

@Entity
@Table(name = "automation_account_battle_gates")
class AccountBattleGateEntity(
    @Id
    @Column(name = "account_id")
    val accountId: Long,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", insertable = false, updatable = false)
    val account: HofAccountEntity,

    @Column(name = "challenge_id")
    var challengeId: Long?,

    @Column(name = "reason", nullable = false, length = 100)
    var reason: String,

    @Column(name = "opened_at", nullable = false)
    var openedAt: Instant,

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null,
) {
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}

@Entity
@Table(name = "automation_evidence_cases")
class AutomationEvidenceCaseEntity(
    @Id
    @Column(name = "id", length = 64)
    val id: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attempt_id", nullable = false)
    val attempt: AutomationActionAttemptEntity,

    @Column(name = "evidence_source", nullable = false, length = 40)
    val evidenceSource: String,

    @Column(name = "observation_completeness", length = 30)
    val observationCompleteness: String?,

    @Column(name = "observation_freshness", length = 20)
    val observationFreshness: String?,

    @Column(name = "state_fingerprint", length = 128)
    val stateFingerprint: String?,

    @Column(name = "response_shape_fingerprint", length = 128)
    val responseShapeFingerprint: String? = null,

    @Column(name = "sanitized_snippet", length = 1000)
    val sanitizedSnippet: String? = null,

    @Column(name = "reason_code", nullable = false, length = 100)
    val reasonCode: String,

    @Column(name = "policy_version", nullable = false, length = 80)
    val policyVersion: String,

    @Column(name = "build_version", nullable = false, length = 80)
    val buildVersion: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
)
