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
import jakarta.persistence.OneToOne
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "quest_map_execution_counters")
class QuestMapExecutionCounterEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity,
    @Column(name = "quest_code", nullable = false, length = 100)
    var questCode: String,
    @Column(name = "quest_cycle", nullable = false, length = 100)
    var questCycle: String,
    @Column(name = "mission_key", nullable = false, length = 100)
    var missionKey: String,
    @Column(name = "category_id", nullable = false, length = 50)
    var categoryId: String,
    @Column(name = "map_code", nullable = false, length = 100)
    var mapCode: String,
    @Column(name = "successful_runs", nullable = false)
    var successfulRuns: Int,
)

@Entity
@Table(name = "battle_automation_daily_progress")
class BattleAutomationDailyProgressEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity,
    @Column(name = "progress_date", nullable = false)
    var progressDate: LocalDate,
    @Column(name = "category_id", nullable = false, length = 50)
    var categoryId: String,
    @Column(name = "map_code", nullable = false, length = 100)
    var mapCode: String,
    @Column(name = "source", nullable = false, length = 50)
    var source: String,
    @Column(name = "successful_runs", nullable = false)
    var successfulRuns: Int,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)

@Entity
@Table(name = "adventure_daily_refresh")
class AdventureDailyRefreshEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity,
    @Column(name = "refresh_date", nullable = false)
    var refreshDate: LocalDate,
    @Column(name = "refreshed_at", nullable = false)
    var refreshedAt: Instant,
)

/** Account-wide retry/manual-stop state for the adventure daily refresh gate. */
@Entity
@Table(name = "adventure_daily_preflight_states")
class AdventureDailyPreflightStateEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "account_id", nullable = false, unique = true)
    var account: HofAccountEntity,
    @Column(name = "refresh_date", nullable = false)
    var refreshDate: LocalDate,
    @Column(name = "failed_attempts", nullable = false)
    var failedAttempts: Int,
    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant?,
    @Column(name = "stop_reason", length = 30)
    var stopReason: String?,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)
