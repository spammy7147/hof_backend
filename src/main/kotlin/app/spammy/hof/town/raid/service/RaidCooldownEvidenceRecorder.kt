package app.spammy.hof.town.raid.service

import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.parser.RaidCooldownAssociationStatus
import app.spammy.hof.external.parser.RaidCooldownPageObservation
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

enum class RaidCooldownEvidenceAction {
    RAID_BATTLE_WINDOW,
}

data class RaidCooldownEvidenceContext(
    val accountId: Long,
    val actionKind: RaidCooldownEvidenceAction,
    val raidScope: String,
    val activeJoinedRaidCount: Int,
)

fun interface RaidCooldownEvidenceRecorder {
    fun record(
        context: RaidCooldownEvidenceContext,
        observation: RaidCooldownPageObservation,
    ): String
}

object NonPersistentRaidCooldownEvidenceRecorder : RaidCooldownEvidenceRecorder {
    override fun record(
        context: RaidCooldownEvidenceContext,
        observation: RaidCooldownPageObservation,
    ): String = observation.responseShapeFingerprint.take(16)
}

@Entity
@Table(
    name = "raid_cooldown_evidence_cases",
    uniqueConstraints = [UniqueConstraint(
        name = "uk_raid_cooldown_evidence_shape",
        columnNames = [
            "account_id",
            "action_kind",
            "raid_scope",
            "association_mode",
            "reason_code",
            "dom_fingerprint",
            "response_shape_fingerprint",
        ],
    )],
)
class RaidCooldownEvidenceCaseEntity(
    @Id
    @Column(name = "id", length = 64)
    val id: String,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "action_kind", nullable = false, length = 40)
    val actionKind: RaidCooldownEvidenceAction,

    @Column(name = "raid_scope", nullable = false, length = 255)
    val raidScope: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "association_mode", nullable = false, length = 40)
    val associationMode: RaidCooldownAssociationStatus,

    @Column(name = "reason_code", nullable = false, length = 100)
    val reasonCode: String,

    @Column(name = "timer_shape", nullable = false, length = 40)
    var timerShape: String,

    @Column(name = "candidate_seconds", nullable = false, length = 255)
    var candidateSeconds: String,

    @Column(name = "candidate_count", nullable = false)
    var candidateCount: Int,

    @Column(name = "map_count", nullable = false)
    var mapCount: Int,

    @Column(name = "active_joined_raid_count", nullable = false)
    var activeJoinedRaidCount: Int,

    @Column(name = "dom_fingerprint", nullable = false, length = 64)
    val domFingerprint: String,

    @Column(name = "response_shape_fingerprint", nullable = false, length = 64)
    val responseShapeFingerprint: String,

    @Column(name = "policy_version", nullable = false, length = 80)
    val policyVersion: String,

    @Column(name = "build_version", nullable = false, length = 80)
    val buildVersion: String,

    @Column(name = "first_observed_at", nullable = false)
    val firstObservedAt: Instant,

    @Column(name = "last_observed_at", nullable = false)
    var lastObservedAt: Instant,

    @Column(name = "observation_count", nullable = false)
    var observationCount: Int,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
) {
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}

@Repository
@Transactional
class JpaRaidCooldownEvidenceRecorder(
    private val entityManager: EntityManager,
    private val timeProvider: TimeProvider,
) : RaidCooldownEvidenceRecorder {
    override fun record(
        context: RaidCooldownEvidenceContext,
        observation: RaidCooldownPageObservation,
    ): String {
        require(observation.incomplete) { "Only incomplete raid cooldown observations are evidence cases." }
        val now = timeProvider.now()
        val existing = entityManager.createQuery(
            "select evidence from RaidCooldownEvidenceCaseEntity evidence " +
                "where evidence.accountId = :accountId and evidence.actionKind = :actionKind " +
                "and evidence.raidScope = :raidScope and evidence.associationMode = :associationMode " +
                "and evidence.reasonCode = :reasonCode and evidence.domFingerprint = :domFingerprint " +
                "and evidence.responseShapeFingerprint = :responseShapeFingerprint",
            RaidCooldownEvidenceCaseEntity::class.java,
        ).setParameter("accountId", context.accountId)
            .setParameter("actionKind", context.actionKind)
            .setParameter("raidScope", context.raidScope)
            .setParameter("associationMode", observation.status)
            .setParameter("reasonCode", observation.reasonCode)
            .setParameter("domFingerprint", observation.domFingerprint)
            .setParameter("responseShapeFingerprint", observation.responseShapeFingerprint)
            .resultList
            .singleOrNull()
        val normalizedSeconds = observation.candidateSeconds.sorted().take(MAX_CANDIDATE_SECONDS)
            .joinToString(",")
        if (existing != null) {
            existing.timerShape = observation.timerShape()
            existing.candidateSeconds = normalizedSeconds
            existing.candidateCount = observation.candidateCount
            existing.mapCount = observation.mapCount
            existing.activeJoinedRaidCount = context.activeJoinedRaidCount
            existing.lastObservedAt = now
            existing.observationCount += 1
            existing.expiresAt = now.plus(RETENTION)
            return existing.id
        }
        val created = RaidCooldownEvidenceCaseEntity(
            id = UUID.randomUUID().toString(),
            accountId = context.accountId,
            actionKind = context.actionKind,
            raidScope = context.raidScope,
            associationMode = observation.status,
            reasonCode = observation.reasonCode,
            timerShape = observation.timerShape(),
            candidateSeconds = normalizedSeconds,
            candidateCount = observation.candidateCount,
            mapCount = observation.mapCount,
            activeJoinedRaidCount = context.activeJoinedRaidCount,
            domFingerprint = observation.domFingerprint,
            responseShapeFingerprint = observation.responseShapeFingerprint,
            policyVersion = POLICY_VERSION,
            buildVersion = BUILD_VERSION,
            firstObservedAt = now,
            lastObservedAt = now,
            observationCount = 1,
            expiresAt = now.plus(RETENTION),
        )
        entityManager.persist(created)
        return created.id
    }

    private fun RaidCooldownPageObservation.timerShape(): String = when {
        status == RaidCooldownAssociationStatus.PARSE_FAILED -> "MARKER_UNPARSEABLE"
        candidateSeconds.size == 1 -> "SINGLE_POSITIVE_SECONDS"
        candidateSeconds.isNotEmpty() -> "MULTIPLE_POSITIVE_SECONDS"
        else -> "NO_PARSED_SECONDS"
    }

    private companion object {
        // 12 signed 64-bit values plus separators fit inside the VARCHAR(255) evidence column.
        const val MAX_CANDIDATE_SECONDS = 12
        const val POLICY_VERSION = "raid-cooldown-association-v1"
        val RETENTION: Duration = Duration.ofDays(30)
        val BUILD_VERSION: String = JpaRaidCooldownEvidenceRecorder::class.java.`package`.implementationVersion ?: "local"
    }
}
