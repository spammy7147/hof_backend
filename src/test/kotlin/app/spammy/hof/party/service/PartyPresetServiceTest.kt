package app.spammy.hof.party.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.character.repository.CharacterPatternSlotCommandRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.repository.CharacterRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.dto.CreatePartyPresetRequest
import app.spammy.hof.party.dto.PartyPresetMemberRequest
import app.spammy.hof.party.dto.UpdatePartyPresetRequest
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.hibernate.SessionFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    CharacterQueryRepository::class,
    PartyPresetQueryRepository::class,
    PartyPresetService::class,
    PartyPresetServiceTest.ClockConfig::class,
)
class PartyPresetServiceTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var characterRepository: CharacterRepository

    @Autowired
    private lateinit var patternSlotRepository: CharacterPatternSlotCommandRepository

    @Autowired
    private lateinit var queryRepository: PartyPresetQueryRepository

    @Autowired
    private lateinit var service: PartyPresetService

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    @Test
    fun createPersistsFiveRelationalMembersAndRoundTripsTheApiShape() {
        val account = savedAccount("party-round-trip")
        val character = savedCharacter(account, "char-1")
        savedPatternSlot(character, 0)

        val response = service.create(
            account.id,
            CreatePartyPresetRequest(
                name = "  범용 파티  ",
                members = listOf(
                    member(4),
                    member(2),
                    member(0, characterId = "char-1", patternSlot = 0),
                    member(3),
                    member(1),
                ),
            ),
        )

        assertEquals("범용 파티", response.name)
        assertEquals(5L, queryRepository.countMembers(response.id))
        assertEquals(listOf(0, 1, 2, 3, 4), response.members.map { it.slotIndex })
        assertEquals("char-1", response.members.first().characterId)
        assertEquals(0, response.members.first().patternSlot)
        assertEquals(response, service.findAll(account.id).single())
    }

    @Test
    fun listUsesTwoQueriesAndOrdersEqualTimestampsByDescendingId() {
        val account = savedAccount("party-list")
        val first = service.create(account.id, request("첫 번째"))
        val second = service.create(account.id, request("두 번째"))
        entityManager.flush()
        entityManager.clear()
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()

        val responses = service.findAll(account.id)

        assertEquals(listOf(second.id, first.id), responses.map { it.id })
        assertEquals(2L, statistics.prepareStatementCount)
        assertEquals(listOf(0, 1, 2, 3, 4), responses.first().members.map { it.slotIndex })
    }

    @Test
    fun listMapsNonnumericStoredPatternSlotCodeToNull() {
        val account = savedAccount("party-nonnumeric-pattern")
        val character = savedCharacter(account, "char-nonnumeric-pattern")
        val patternSlot = savedPatternSlot(character, 0)
        service.create(
            account.id,
            request("문자열 패턴 코드", characterId = character.hofCharacterId, patternSlot = 0),
        )
        patternSlot.slotCode = "primary"
        entityManager.flush()
        entityManager.clear()

        val response = service.findAll(account.id).single()

        assertEquals(character.hofCharacterId, response.members.first().characterId)
        assertNull(response.members.first().patternSlot)
    }

    @Test
    fun updateDeletesOldRowsBeforeInsertingFiveReplacements() {
        val account = savedAccount("party-update")
        val firstCharacter = savedCharacter(account, "char-before")
        val secondCharacter = savedCharacter(account, "char-after")
        savedPatternSlot(firstCharacter, 0)
        savedPatternSlot(secondCharacter, 1)
        val created = service.create(
            account.id,
            request("변경 전", characterId = "char-before", patternSlot = 0),
        )

        val updated = service.update(
            account.id,
            created.id,
            UpdatePartyPresetRequest(
                name = "변경 후",
                members = members(characterId = "char-after", patternSlot = 1),
            ),
        )

        assertEquals("변경 후", updated.name)
        assertEquals(5L, queryRepository.countMembers(created.id))
        assertEquals("char-after", updated.members.first().characterId)
        assertEquals(1, updated.members.first().patternSlot)
        assertEquals(
            listOf("char-after", null, null, null, null),
            queryRepository.findMembersByPresetIds(listOf(created.id)).map { it.character?.hofCharacterId },
        )
    }

    @Test
    fun invalidUpdateLeavesExistingMembersUntouched() {
        val account = savedAccount("party-atomic-update")
        val selected = savedCharacter(account, "char-selected")
        val other = savedCharacter(account, "char-other")
        savedPatternSlot(selected, 0)
        savedPatternSlot(other, 1)
        val created = service.create(
            account.id,
            request("원본", characterId = "char-selected", patternSlot = 0),
        )

        val exception = assertFailsWith<ApiException> {
            service.update(
                account.id,
                created.id,
                UpdatePartyPresetRequest(
                    name = "저장되면 안 됨",
                    members = members(characterId = "char-selected", patternSlot = 1),
                ),
            )
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
        val stored = queryRepository.findMembersByPresetIds(listOf(created.id))
        assertEquals(5, stored.size)
        assertEquals("char-selected", stored.first().character?.hofCharacterId)
        assertEquals("0", stored.first().patternSlot?.slotCode)
        assertEquals("원본", assertNotNull(queryRepository.findOwnedByAccountIdAndId(account.id, created.id)).name)
    }

    @Test
    fun rejectsCharacterOwnedByAnotherAccountAsNotFound() {
        val owner = savedAccount("party-character-owner")
        val requester = savedAccount("party-character-requester")
        savedCharacter(owner, "foreign-char")

        val exception = assertFailsWith<ApiException> {
            service.create(
                requester.id,
                request("다른 계정 캐릭터", characterId = "foreign-char"),
            )
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun rejectsPatternSlotThatDoesNotBelongToTheSelectedCharacterAsNotFound() {
        val account = savedAccount("party-pattern-owner")
        val selected = savedCharacter(account, "selected-char")
        val other = savedCharacter(account, "other-char")
        savedPatternSlot(selected, 0)
        savedPatternSlot(other, 1)

        val exception = assertFailsWith<ApiException> {
            service.create(
                account.id,
                request("잘못된 패턴", characterId = "selected-char", patternSlot = 1),
            )
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun rejectsPatternForAnEmptyCharacterSlotAsInvalidRequest() {
        val account = savedAccount("party-empty-pattern")

        val exception = assertFailsWith<ApiException> {
            service.create(
                account.id,
                CreatePartyPresetRequest(
                    name = "빈 슬롯 패턴",
                    members = listOf(member(0, patternSlot = 0)) + (1..4).map(::member),
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
    }

    @Test
    fun rejectsAnySlotSetOtherThanExactlyZeroThroughFour() {
        val account = savedAccount("party-invalid-slots")

        val exception = assertFailsWith<ApiException> {
            service.create(
                account.id,
                CreatePartyPresetRequest(
                    name = "잘못된 슬롯",
                    members = (0..3).map(::member) + member(5),
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
    }

    @Test
    fun rejectsNegativePatternSlotAsInvalidRequest() {
        val account = savedAccount("party-negative-pattern")
        savedCharacter(account, "char-negative")

        val exception = assertFailsWith<ApiException> {
            service.create(
                account.id,
                request("음수 패턴", characterId = "char-negative", patternSlot = -1),
            )
        }

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
    }

    @Test
    fun deleteRemovesChildrenBeforeTheParent() {
        val account = savedAccount("party-delete")
        val created = service.create(account.id, request("삭제할 프리셋"))

        service.delete(account.id, created.id)

        assertEquals(0L, queryRepository.countMembers(created.id))
        assertNull(queryRepository.findOwnedByAccountIdAndId(account.id, created.id))
    }

    private fun savedAccount(loginId: String): HofAccountEntity =
        accountRepository.save(
            HofAccountEntity(
                loginId = loginId,
                encryptedPassword = "encrypted",
                createdAt = NOW,
            ),
        )

    private fun savedCharacter(
        account: HofAccountEntity,
        hofCharacterId: String,
    ): CharacterEntity =
        characterRepository.save(
            CharacterEntity(
                account = account,
                hofCharacterId = hofCharacterId,
                name = hofCharacterId,
                job = "검사",
                updatedAt = NOW,
            ),
        )

    private fun savedPatternSlot(
        character: CharacterEntity,
        slot: Int,
    ): CharacterPatternSlotEntity =
        patternSlotRepository.save(
            CharacterPatternSlotEntity(
                character = character,
                slotCode = slot.toString(),
                label = "$slot 번 패턴",
                canLoad = true,
            ),
        )

    private fun request(
        name: String,
        characterId: String? = null,
        patternSlot: Int? = null,
    ): CreatePartyPresetRequest =
        CreatePartyPresetRequest(
            name = name,
            members = members(characterId = characterId, patternSlot = patternSlot),
        )

    private fun members(
        characterId: String? = null,
        patternSlot: Int? = null,
    ): List<PartyPresetMemberRequest> =
        (0..4).map { slot ->
            member(
                slotIndex = slot,
                characterId = characterId.takeIf { slot == 0 },
                patternSlot = patternSlot.takeIf { slot == 0 },
            )
        }

    private fun member(
        slotIndex: Int,
        characterId: String? = null,
        patternSlot: Int? = null,
    ): PartyPresetMemberRequest =
        PartyPresetMemberRequest(
            slotIndex = slotIndex,
            characterId = characterId,
            patternSlot = patternSlot,
        )

    @TestConfiguration
    class ClockConfig {
        @Bean
        fun timeProvider(): TimeProvider = TimeProvider { NOW }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-12T00:00:00Z")
    }
}
