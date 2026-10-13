package app.spammy.hof.character.command

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.character.entity.*
import app.spammy.hof.character.identity.*
import app.spammy.hof.character.repository.*
import app.spammy.hof.character.service.CharacterService
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.external.model.HofCharacter
import app.spammy.hof.party.entity.*
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.party.service.PartyPresetResponseMapper
import app.spammy.hof.status.repository.HofStatusSnapshotQueryRepository
import app.spammy.hof.town.common.service.AccountHofMutationFence
import jakarta.persistence.EntityManager
import java.util.UUID
import kotlin.test.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@DataJpaTest
@Import(QueryDslConfig::class, AccountQueryRepository::class, CharacterQueryRepository::class,
    CharacterIdentityQueryRepository::class, CharacterOperationJobQueryRepository::class,
    HofStatusSnapshotQueryRepository::class, CharacterService::class, CharacterLifecycleService::class,
    CharacterLifecycleTransaction::class, AccountHofMutationFence::class,
    CharacterCommandIdentityProjection::class, CharacterCommandIdentityProjectionTest.ClockConfig::class,
    PartyPresetQueryRepository::class, PartyPresetResponseMapper::class)
class CharacterIdReplacementIntegrationTest {
    @Autowired lateinit var em: EntityManager
    @Autowired lateinit var service: CharacterService
    @Autowired lateinit var lifecycle: CharacterLifecycleService
    @Autowired lateinit var projection: CharacterCommandIdentityProjection
    @Autowired lateinit var characters: CharacterQueryRepository
    @Autowired lateinit var presets: PartyPresetQueryRepository
    @Autowired lateinit var mapper: PartyPresetResponseMapper
    private val now = CharacterCommandIdentityProjectionTest.NOW

    @Test
    fun `연속 Knockback 뒤 기존 프리셋 두 개와 저장 패턴은 같은 기록의 최신 HOF ID를 참조한다`() {
        val seed = seed()
        for ((index, nextId) in listOf("new-id", "newer-id").withIndex()) {
            val previousId = if (index == 0) "old-id" else "new-id"
            observe(seed, nextId)
            assertEquals(CharacterCommandIdentityProjectionResult.Applied,
                projection.recordConfirmedKnockback(seed.accountId, seed.characterId, previousId, nextId,
                    setOf(previousId, "peer-id"), setOf("peer-id", nextId), now.plusSeconds(index + 2L)))
            verifyPresets(seed, nextId)
        }
    }

    @Test
    fun `인게임 직접 Knockback 관측은 자동 연결하지 않고 수동 연결 뒤 기존 프리셋을 복원한다`() {
        val seed = seed()
        observe(seed, "new-id")
        em.flush(); em.clear()
        assertEquals(CharacterLifecycle.MISSING, characters.findByAccountIdAndId(seed.accountId, seed.characterId)?.lifecycle)
        assertNotEquals(seed.characterId, characters.findByAccountIdAndHofCharacterId(seed.accountId, "new-id")?.id)
        verifyPresets(seed, "old-id")
        lifecycle.link(seed.accountId, seed.characterId, "new-id", CharacterHofIdLinkReason.MANUAL_LINK, true)
        verifyPresets(seed, "new-id")
        assertEquals(CharacterLifecycle.ACTIVE, characters.findByAccountIdAndId(seed.accountId, seed.characterId)?.lifecycle)
        assertEquals(1, characters.findByAccountIdAndId(seed.accountId, seed.characterId)?.rosterOrder)
    }

    @Test
    fun `앱 Knockback 명단을 기존 기록에 직접 반영해도 새 ID와 맨 뒤 순서를 보존한다`() {
        val seed = seed()
        assertEquals(CharacterCommandIdentityProjectionResult.Applied,
            projection.recordConfirmedKnockback(seed.accountId, seed.characterId, "old-id", "new-id",
                setOf("old-id", "peer-id"), linkedSetOf("peer-id", "new-id"), now.plusSeconds(2)))
        verifyPresets(seed, "new-id")
        assertEquals(listOf("peer-id", "new-id"), service.findAll(seed.accountId).map { it.hofCharacterId })
    }

    @Test
    fun `Knockback 재연결 뒤 캐릭터 목록 순서가 새 명단의 맨 뒤 순서와 일치한다`() {
        val seed = seed()
        observe(seed, "new-id")
        assertEquals(CharacterCommandIdentityProjectionResult.Applied,
            projection.recordConfirmedKnockback(seed.accountId, seed.characterId, "old-id", "new-id",
                setOf("old-id", "peer-id"), setOf("peer-id", "new-id"), now.plusSeconds(2)))
        em.flush(); em.clear()
        assertEquals(1, characters.findByAccountIdAndId(seed.accountId, seed.characterId)?.rosterOrder)
    }

    private fun observe(seed: Seed, nextId: String) {
        service.reconcileObservedRoster(em.find(HofAccountEntity::class.java, seed.accountId), listOf(
            HofCharacter(id = "peer-id", name = "동료", job = "Knight", level = 10, rosterOrder = 0),
            HofCharacter(id = nextId, name = "대상", job = "Knight", level = 10, rosterOrder = 1),
        ), now.plusSeconds(1))
        em.flush(); em.clear()
    }

    private fun verifyPresets(seed: Seed, expectedHofId: String) {
        em.flush(); em.clear()
        val rows = presets.findAllByAccountId(seed.accountId)
        assertEquals(2, rows.size)
        for (preset in rows) {
            val members = presets.findMembersByPresetIds(listOf(preset.id))
            assertEquals(seed.characterId, members.first().character?.id)
            assertEquals(seed.patternId, members.first().patternSlot?.id)
            val response = mapper.toPresetResponse(preset, members)
            assertEquals(expectedHofId, response.members.first().characterId)
            assertEquals(2, response.members.first().patternSlot)
        }
    }

    private fun seed(): Seed {
        val account = HofAccountEntity(loginId = "audit-${UUID.randomUUID()}", encryptedPassword = "fixture", createdAt = now)
        em.persist(account)
        val target = CharacterEntity(account = account, hofCharacterId = "old-id", name = "대상", job = "Knight", level = 10, rosterOrder = 0, updatedAt = now)
        val peer = CharacterEntity(account = account, hofCharacterId = "peer-id", name = "동료", job = "Knight", level = 10, rosterOrder = 1, updatedAt = now)
        for (character in listOf(target, peer)) {
            em.persist(character)
            em.persist(CharacterHofIdHistoryEntity(character = character, account = account,
                hofCharacterId = character.hofCharacterId, validFrom = now,
                linkReason = CharacterHofIdLinkReason.INITIAL_SYNC, userConfirmed = true))
        }
        val pattern = CharacterPatternSlotEntity(character = target, slotCode = "2", label = "저장 패턴", canLoad = true)
        em.persist(pattern)
        repeat(2) { i ->
            val preset = PartyPresetEntity(account = account, name = "검증 $i", createdAt = now, updatedAt = now, displayOrder = i)
            em.persist(preset)
            repeat(5) { slot -> em.persist(PartyPresetMemberEntity(preset = preset, slotIndex = slot,
                character = if (slot == 0) target else null, patternSlot = if (slot == 0) pattern else null)) }
        }
        em.flush()
        return Seed(account.id, target.id, pattern.id)
    }

    private data class Seed(val accountId: Long, val characterId: Long, val patternId: Long)
}
