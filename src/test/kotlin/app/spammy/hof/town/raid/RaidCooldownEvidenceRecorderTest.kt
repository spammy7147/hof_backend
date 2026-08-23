package app.spammy.hof.town.raid

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.parser.RaidCooldownAssociationStatus
import app.spammy.hof.external.parser.RaidCooldownPageObservation
import app.spammy.hof.town.raid.service.JpaRaidCooldownEvidenceRecorder
import app.spammy.hof.town.raid.service.RaidCooldownEvidenceAction
import app.spammy.hof.town.raid.service.RaidCooldownEvidenceContext
import app.spammy.hof.town.raid.service.RaidCooldownEvidenceCaseEntity
import jakarta.persistence.EntityManager
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
class RaidCooldownEvidenceRecorderTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun `동일한 비식별 쿨타임 관측은 30일 evidence case 하나에 누적한다`() {
        val firstAt = Instant.parse("2026-08-23T00:00:00Z")
        var now = firstAt
        val account = accounts.save(HofAccountEntity(loginId = "evidence", encryptedPassword = "secret", createdAt = now))
        val recorder = JpaRaidCooldownEvidenceRecorder(entityManager, TimeProvider { now })
        val observation = RaidCooldownPageObservation(
            status = RaidCooldownAssociationStatus.AMBIGUOUS,
            candidateSeconds = listOf(119, 120),
            mapCount = 1,
            candidateCount = 2,
            domFingerprint = "d".repeat(64),
            responseShapeFingerprint = "r".repeat(64),
            reasonCode = "RAID_COOLDOWN_ASSOCIATION_AMBIGUOUS",
        )

        val context = RaidCooldownEvidenceContext(
            accountId = account.id,
            actionKind = RaidCooldownEvidenceAction.RAID_BATTLE_WINDOW,
            raidScope = "RaidGoblin",
            activeJoinedRaidCount = 1,
        )
        val caseId = recorder.record(context, observation)
        now = now.plusSeconds(15)
        assertEquals(caseId, recorder.record(context, observation))
        entityManager.flush()
        entityManager.clear()

        val stored = entityManager.find(RaidCooldownEvidenceCaseEntity::class.java, caseId)
        assertEquals(2, stored.observationCount)
        assertEquals("119,120", stored.candidateSeconds)
        assertEquals(RaidCooldownEvidenceAction.RAID_BATTLE_WINDOW, stored.actionKind)
        assertEquals("RaidGoblin", stored.raidScope)
        assertEquals(1, stored.activeJoinedRaidCount)
        assertEquals(firstAt, stored.firstObservedAt)
        assertEquals(now, stored.lastObservedAt)
        assertEquals(now.plus(Duration.ofDays(30)), stored.expiresAt)
        assertFalse(stored.toString().contains("evidence"))
        assertFalse(stored.toString().contains("secret"))
    }

    @Test
    fun `후보 시간이 많아도 evidence 열 길이를 넘기지 않는다`() {
        val now = Instant.parse("2026-08-23T00:00:00Z")
        val account = accounts.save(HofAccountEntity(loginId = "bounded", encryptedPassword = "secret", createdAt = now))
        val recorder = JpaRaidCooldownEvidenceRecorder(entityManager, TimeProvider { now })
        val observation = RaidCooldownPageObservation(
            status = RaidCooldownAssociationStatus.AMBIGUOUS,
            candidateSeconds = List(20) { Long.MAX_VALUE - it },
            mapCount = 1,
            candidateCount = 20,
            domFingerprint = "d".repeat(64),
            responseShapeFingerprint = "r".repeat(64),
            reasonCode = "RAID_COOLDOWN_ASSOCIATION_AMBIGUOUS",
        )

        val caseId = recorder.record(
            RaidCooldownEvidenceContext(
                accountId = account.id,
                actionKind = RaidCooldownEvidenceAction.RAID_BATTLE_WINDOW,
                raidScope = "RaidGoblin",
                activeJoinedRaidCount = 1,
            ),
            observation,
        )
        entityManager.flush()
        entityManager.clear()

        val stored = entityManager.find(RaidCooldownEvidenceCaseEntity::class.java, caseId)
        assertTrue(stored.candidateSeconds.length <= 255)
        assertEquals(20, stored.candidateCount)
    }
}
