package app.spammy.hof.character.identity

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.character.command.CharacterCommandIdentityProjectionTest
import app.spammy.hof.character.entity.*
import app.spammy.hof.character.repository.*
import app.spammy.hof.character.service.CharacterDeepSyncResponse
import app.spammy.hof.character.service.CharacterService
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.external.model.HofCharacter
import app.spammy.hof.party.entity.*
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.test.context.ActiveProfiles
import tools.jackson.module.kotlin.jacksonObjectMapper

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, AccountQueryRepository::class, CharacterQueryRepository::class,
    CharacterIdentityQueryRepository::class, CharacterOperationJobQueryRepository::class,
    CharacterSyncJobQueryRepository::class, PartyPresetQueryRepository::class, CharacterService::class,
    CharacterLifecycleTransaction::class, CharacterObservedIdentityTransaction::class,
    CharacterCommandIdentityProjectionTest.ClockConfig::class, CharacterObservedIdentityIntegrationTest.Config::class)
class CharacterObservedIdentityIntegrationTest {
    @Autowired lateinit var em: EntityManager
    @Autowired lateinit var service: CharacterService
    @Autowired lateinit var identity: CharacterObservedIdentityTransaction
    @Autowired lateinit var characters: CharacterQueryRepository
    @Autowired lateinit var histories: CharacterIdentityQueryRepository
    @Autowired lateinit var jobs: CharacterOperationJobQueryRepository
    private val now = CharacterCommandIdentityProjectionTest.NOW
    private val mapper = jacksonObjectMapper()

    @TestConfiguration
    class Config { @Bean fun identityMapper() = jacksonObjectMapper() }

    @Test
    fun `기본 정보와 모든 저장 패턴이 일치하면 기존 기록과 프리셋을 보존하고 작업 원본도 이관한다`() {
        val s = seed()
        em.flush(); em.clear()
        assertTrue(identity.canVerify(s.account.id, s.observed.id))
        assertEquals(s.previous.id, identity.resolveCompleted(s.account.id, s.operation.id))
        em.flush(); em.clear()
        val linked = characters.findByAccountIdAndId(s.account.id, s.previous.id)!!
        assertEquals("new-id", linked.hofCharacterId)
        assertEquals(CharacterLifecycle.ACTIVE, linked.lifecycle)
        assertEquals(0, linked.rosterOrder)
        assertNull(characters.findByAccountIdAndId(s.account.id, s.observed.id))
        val member = member(s)
        assertEquals(s.previous.id, member.character!!.id)
        assertEquals(s.previousSlot.id, member.patternSlot!!.id)
        val operation = jobs.findById(s.operation.id)!!
        assertEquals(s.previous.id, operation.targetCharacterId)
        assertEquals(s.previous.id, mapper.readValue(operation.resultPayload, CharacterDeepSyncResponse::class.java).characterId)
        assertNotNull(em.find(CharacterRecoveryOriginalEntity::class.java, s.operation.id))
        assertEquals("[${s.previous.id}]", em.find(CharacterSyncJobEntity::class.java, s.parent.id).deepSyncTargets)
        assertEquals(s.operation.id, em.find(CharacterSyncJobEntity::class.java, s.parent.id).currentOperationJobId)
        assertEquals(listOf("old-id", "new-id"), histories.findHistory(s.previous.id).map { it.hofCharacterId })
        assertEquals(false, histories.findHistory(s.previous.id).last().userConfirmed)
        assertNull(identity.resolveCompleted(s.account.id, s.operation.id))
    }

    @ParameterizedTest
    @ValueSource(strings = ["name", "job", "level", "duplicate-active", "duplicate-missing", "preexisting-active",
        "judge", "quantity", "skill", "row-order", "position", "guard", "empty-slot", "missing-rows", "extra-slot",
        "failed-new", "unrestored", "unreleased", "partial-collection", "failed-old", "no-old-collection", "wrong-original",
        "new-preset", "new-transfer", "other-account"])
    fun `이름 중복 정보 차이 불완전 증거나 독립 사용 기록이 있으면 자동으로 합치지 않는다`(difference: String) {
        val s = seed()
        when (difference) {
            "name" -> s.observed.name = "다른 이름"
            "job" -> s.observed.job = "Mage"
            "level" -> s.observed.level = 11
            "duplicate-active", "duplicate-missing" -> em.persist(CharacterEntity(account = s.account, hofCharacterId = "duplicate",
                name = s.previous.name, job = "Mage", level = 99, updatedAt = now,
                lifecycle = if (difference == "duplicate-active") CharacterLifecycle.ACTIVE else CharacterLifecycle.MISSING))
            "preexisting-active" -> em.createQuery("update CharacterHofIdHistoryEntity h set h.validFrom = :at where h.character.id = :id")
                .setParameter("at", now.minusSeconds(1)).setParameter("id", s.observed.id).executeUpdate()
            "judge" -> s.rows[0].judge = "9"
            "quantity" -> s.rows[0].quantity = "51"
            "skill" -> s.rows[0].skill = "different"
            "row-order" -> { s.rows[0].judge = "1"; s.rows[0].skill = "skill-1"; s.rows[1].judge = "0"; s.rows[1].skill = "skill-0" }
            "position" -> s.observedSlot.selectedPosition = "back"
            "guard" -> s.observedSlot.guardValue = "0"
            "empty-slot" -> s.observedSlot.canLoad = false
            "missing-rows" -> s.rows.forEach(em::remove)
            "extra-slot" -> em.persist(CharacterPatternSlotEntity(character = s.observed, slotCode = "1", label = "추가", canLoad = false))
            "failed-new" -> s.operation.status = CharacterOperationStatus.FAILED
            "unrestored" -> s.operation.recoveryStatus = CharacterRecoveryStatus.RESTORING
            "unreleased" -> s.operation.automationReleased = false
            "partial-collection" -> em.find(CharacterRecoveryOriginalEntity::class.java, s.operation.id).collectionComplete = false
            "failed-old" -> operation(s.account, s.previous, now.plusSeconds(2)).status = CharacterOperationStatus.FAILED
            "no-old-collection" -> em.find(CharacterRecoveryOriginalEntity::class.java, s.previousOperation.id).collectionComplete = false
            "wrong-original" -> em.createNativeQuery("update character_recovery_originals set hof_character_id='other-id' where job_id=:id")
                .setParameter("id", s.operation.id).executeUpdate()
            "new-preset" -> em.persist(PartyPresetMemberEntity(preset = s.member.preset, slotIndex = 1, character = s.observed, patternSlot = s.observedSlot))
            "new-transfer" -> operation(s.account, s.observed, now.plusSeconds(21)).operationType = CharacterOperationType.TRANSFER
            else -> Unit
        }
        em.flush()
        em.clear()
        val accountId = if (difference == "other-account") -1L else s.account.id
        assertNull(identity.resolveCompleted(accountId, s.operation.id), difference)
        em.flush(); em.clear()
        assertEquals("old-id", characters.findByAccountIdAndId(s.account.id, s.previous.id)!!.hofCharacterId)
        assertNotNull(characters.findByAccountIdAndId(s.account.id, s.observed.id))
        assertEquals(s.previous.id, member(s).character!!.id)
        assertEquals(s.observed.id, jobs.findById(s.operation.id)!!.targetCharacterId)
    }

    private fun operation(account: HofAccountEntity, character: CharacterEntity, at: Instant): CharacterOperationJobEntity {
        val job = CharacterOperationJobEntity(account = account, operationType = CharacterOperationType.DEEP_SYNC,
            status = CharacterOperationStatus.COMPLETED, targetCharacterId = character.id, startedAt = at, updatedAt = at,
            finishedAt = at.plusSeconds(1), recoveryStatus = CharacterRecoveryStatus.RESTORED, automationReleased = true,
            resultPayload = mapper.writeValueAsString(CharacterDeepSyncResponse(character.id, emptyList())))
        em.persist(job)
        em.persist(CharacterRecoveryOriginalEntity(jobId = job.id, hofCharacterId = character.hofCharacterId,
            position = "front", guard = "1", capturedAt = at, collectionComplete = true))
        return job
    }

    private fun member(seed: Seed): PartyPresetMemberEntity = em.createQuery(
        "select m from PartyPresetMemberEntity m where m.preset.id = :id and m.slotIndex = 0", PartyPresetMemberEntity::class.java)
        .setParameter("id", seed.member.preset.id).singleResult

    private fun pattern(character: CharacterEntity): Pair<CharacterPatternSlotEntity, List<CharacterSavedPatternRowEntity>> {
        val slot = CharacterPatternSlotEntity(character = character, slotCode = "0", label = "저장", canLoad = true,
            selectedPosition = "front", guardValue = "1")
        em.persist(slot)
        val rows = (0..1).map { CharacterSavedPatternRowEntity(patternSlot = slot, rowIndex = it, judge = it.toString(),
            judgeText = "조건", quantity = "50", quantityText = "수치", skill = "skill-$it", skillText = "스킬").also(em::persist) }
        return slot to rows
    }

    private fun seed(): Seed {
        val account = HofAccountEntity(loginId = "identity-${UUID.randomUUID()}", encryptedPassword = "fixture", createdAt = now).also(em::persist)
        val previous = CharacterEntity(account = account, hofCharacterId = "old-id", name = "대상", job = "Knight", level = 10, updatedAt = now).also(em::persist)
        em.persist(CharacterHofIdHistoryEntity(character = previous, account = account, hofCharacterId = "old-id", validFrom = now,
            linkReason = CharacterHofIdLinkReason.INITIAL_SYNC, userConfirmed = true))
        val previousSlot = pattern(previous).first
        val previousOperation = operation(account, previous, now)
        val preset = PartyPresetEntity(account = account, name = "유지할 프리셋", createdAt = now, updatedAt = now).also(em::persist)
        val member = PartyPresetMemberEntity(preset = preset, slotIndex = 0, character = previous, patternSlot = previousSlot).also(em::persist)
        service.reconcileObservedRoster(account, listOf(HofCharacter(id = "new-id", name = "대상", job = "Knight", level = 10, rosterOrder = 0)), now.plusSeconds(10))
        em.flush()
        val observed = characters.findByAccountIdAndHofCharacterId(account.id, "new-id")!!
        val (slot, rows) = pattern(observed)
        val operation = operation(account, observed, now.plusSeconds(20))
        val parent = CharacterSyncJobEntity(account = account, status = CharacterSyncJobStatus.RUNNING, startedAt = now,
            syncMode = CharacterSyncMode.DEEP, deepSyncTargets = "[${observed.id}]", currentOperationJobId = operation.id).also(em::persist)
        em.flush()
        return Seed(account, previous, observed, previousSlot, slot, rows, member, previousOperation, operation, parent)
    }

    private data class Seed(val account: HofAccountEntity, val previous: CharacterEntity, val observed: CharacterEntity,
        val previousSlot: CharacterPatternSlotEntity, val observedSlot: CharacterPatternSlotEntity,
        val rows: List<CharacterSavedPatternRowEntity>, val member: PartyPresetMemberEntity,
        val previousOperation: CharacterOperationJobEntity, val operation: CharacterOperationJobEntity, val parent: CharacterSyncJobEntity)
}
