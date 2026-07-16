package app.spammy.hof.automation.entity

import app.spammy.hof.account.entity.HofAccountEntity
import jakarta.persistence.*
import java.time.Instant

enum class TypedAutomationLifecycle { RUNNING, PAUSED, STOPPED }
enum class TypedAutomationActionStatus { PREPARED, SUBMITTING, SUCCEEDED, FAILED, AMBIGUOUS }

@Entity
@Table(name = "typed_automation_runtime_states")
class TypedAutomationRuntimeStateEntity(
    @Id @Column(name = "account_id") val accountId: Long,
    @OneToOne(fetch = FetchType.LAZY) @MapsId @JoinColumn(name = "account_id") val account: HofAccountEntity,
    @Enumerated(EnumType.STRING) @Column(name = "lifecycle_status") var lifecycleStatus: TypedAutomationLifecycle,
    @Column(name = "stop_reason") var stopReason: String? = null,
    @Column(name = "stop_action_id") var stopActionId: Long? = null,
    @Column(name = "retry_attempt") var retryAttempt: Int = 0,
    @Column(name = "next_attempt_at") var nextAttemptAt: Instant? = null,
    @Column(name = "lease_token") var leaseToken: String? = null,
    @Column(name = "lease_until") var leaseUntil: Instant? = null,
    @Column(name = "warning_text", columnDefinition = "text") var warningText: String? = null,
    @Column(name = "last_error", columnDefinition = "text") var lastError: String? = null,
    @Column(name = "created_at") val createdAt: Instant,
    @Column(name = "updated_at") var updatedAt: Instant,
    // Nullable version lets Spring Data identify a new @MapsId row and persist it instead of merging it.
    @Version @Column(name = "version") var version: Long? = null,
)

@Entity
@Table(name = "typed_automation_action_runs")
class TypedAutomationActionRunEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "account_id") val account: HofAccountEntity,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "automation_entry_id") val entry: AutomationEntryEntity?,
    @Column(name = "execution_identity") val executionIdentity: String,
    @Column(name = "action_kind") val actionKind: String,
    @Column(name = "schema_version") val schemaVersion: Int,
    @Column(name = "payload_json", columnDefinition = "text") val payloadJson: String,
    @Column(name = "action_fingerprint") val actionFingerprint: String,
    @Enumerated(EnumType.STRING) @Column(name = "status") var status: TypedAutomationActionStatus,
    @Column(name = "retry_attempt") var retryAttempt: Int = 0,
    @Column(name = "next_attempt_at") var nextAttemptAt: Instant? = null,
    @Column(name = "lease_token") var leaseToken: String,
    @Column(name = "last_error", columnDefinition = "text") var lastError: String? = null,
    @Column(name = "created_at") val createdAt: Instant,
    @Column(name = "submitted_at") var submittedAt: Instant? = null,
    @Column(name = "finished_at") var finishedAt: Instant? = null,
    @Column(name = "updated_at") var updatedAt: Instant,
)
