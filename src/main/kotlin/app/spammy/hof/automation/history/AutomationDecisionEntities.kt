package app.spammy.hof.automation.history

import app.spammy.hof.automation.entity.AutomationType
import jakarta.persistence.*
import java.time.Instant

enum class AutomationDecisionResult { ACTION_SELECTED, WAITING, IDLE, FATAL }
enum class AutomationHistoryEventKind {
    EVALUATED, SELECTED, WAITING, SKIPPED, CONFIGURATION_WARNING,
    ACTION_STARTED, ACTION_SUCCEEDED, ACTION_FAILED, CYCLE_COMPLETED, CYCLE_ABORTED,
}

@Entity
@Table(name = "automation_decision_cycles")
class AutomationDecisionCycleEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(name = "account_id", nullable = false) val accountId: Long,
    @Enumerated(EnumType.STRING) @Column(name = "result", nullable = false, length = 32) val result: AutomationDecisionResult,
    @Column(name = "selected_entry_id") val selectedEntryId: Long? = null,
    @Column(name = "started_at", nullable = false) val startedAt: Instant,
    @Column(name = "finished_at", nullable = false) val finishedAt: Instant,
) {
    @OneToMany(mappedBy = "cycle", fetch = FetchType.LAZY)
    val events: MutableList<AutomationDecisionEventEntity> = mutableListOf()
}

@Entity
@Table(name = "automation_decision_events")
class AutomationDecisionEventEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "decision_cycle_id", nullable = false) val cycle: AutomationDecisionCycleEntity,
    @Column(name = "sequence_no", nullable = false) val sequence: Int,
    @Column(name = "automation_entry_id") val entryId: Long? = null,
    @Enumerated(EnumType.STRING) @Column(name = "automation_type", length = 30) val type: AutomationType? = null,
    @Enumerated(EnumType.STRING) @Column(name = "event_kind", nullable = false, length = 32) val kind: AutomationHistoryEventKind,
    @Column(name = "reason_code", nullable = false, length = 100) val reasonCode: String,
    @Column(name = "message", nullable = false, length = 1000) val message: String,
    @Column(name = "target_key", length = 255) val targetKey: String? = null,
    @Column(name = "target_name", length = 255) val targetName: String? = null,
    @Column(name = "action_kind", length = 64) val actionKind: String? = null,
    @Column(name = "preset_id") val presetId: Long? = null,
    @Column(name = "preset_name", length = 255) val presetName: String? = null,
    @Column(name = "next_run_at") val nextRunAt: Instant? = null,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant,
)
