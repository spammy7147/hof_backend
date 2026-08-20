package app.spammy.hof.party.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.character.repository.CharacterPatternSlotCommandRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.repository.CharacterRepository
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.BattleAutomationMapEntity
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.repository.AutomationEntryCommandRepository
import app.spammy.hof.automation.repository.BattleAutomationMapCommandRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.dto.CreatePartyPresetRequest
import app.spammy.hof.party.dto.PartyPresetMemberRequest
import app.spammy.hof.party.dto.ReorderPartyPresetsRequest
import app.spammy.hof.party.dto.UpdatePartyPresetRequest
import app.spammy.hof.party.entity.PartyPresetFolderEntity
import app.spammy.hof.party.repository.PartyPresetFolderQueryRepository
import app.spammy.hof.party.repository.PartyPresetFolderRepository
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder

@DataJpaTest
@ActiveProfiles("test")
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    CharacterQueryRepository::class,
    PartyPresetQueryRepository::class,
    PartyPresetFolderQueryRepository::class,
    PartyPresetResponseMapper::class,
    PartyPresetCatalogService::class,
    PartyPresetService::class,
    PartyPresetServiceTest.ClockConfig::class,
)
class PartyPresetServiceTest {
    @MockitoBean
    private lateinit var presetChangeNotifier: PartyPresetChangeNotifier

    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var characterRepository: CharacterRepository

    @Autowired
    private lateinit var patternSlotRepository: CharacterPatternSlotCommandRepository

    @MockitoSpyBean
    private lateinit var accountQueries: AccountQueryRepository

    @MockitoSpyBean
    private lateinit var folderQueries: PartyPresetFolderQueryRepository

    @MockitoSpyBean
    private lateinit var queryRepository: PartyPresetQueryRepository

    @Autowired
    private lateinit var folderRepository: PartyPresetFolderRepository

    @Autowired
    private lateinit var automationEntries: AutomationEntryCommandRepository

    @Autowired
    private lateinit var battleMaps: BattleAutomationMapCommandRepository

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
        assertEquals(false, response.isPrimary)
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
    fun createPlacesEachNewPresetAtTheTopWithContiguousOrder() {
        val account = savedAccount("party-create-order")
        val first = service.create(account.id, request("첫 번째"))
        val second = service.create(account.id, request("두 번째"))
        val third = service.create(account.id, request("세 번째"))

        val responses = service.findAll(account.id)

        assertEquals(listOf(third.id, second.id, first.id), responses.map { it.id })
        assertEquals(listOf(0, 1, 2), responses.map { it.displayOrder })
    }

    @Test
    fun createInFolderShiftsOnlyDestinationSiblingsAndMapsFolder() {
        val account = savedAccount("party-create-folder")
        val folder = savedFolder(account, "공격대")
        val otherFolder = savedFolder(account, "일일")
        val unassigned = service.create(account.id, request("미분류"))
        val other = service.create(account.id, request("다른 폴더", folderId = otherFolder.id))
        val first = service.create(account.id, request("첫 번째", folderId = folder.id))

        val second = service.create(account.id, request("두 번째", folderId = folder.id))

        val stored = service.findAll(account.id)
        assertEquals(folder.id, second.folderId)
        assertEquals(
            listOf(second.id to 0, first.id to 1),
            stored.filter { it.folderId == folder.id }.map { it.id to it.displayOrder },
        )
        assertEquals(listOf(other.id to 0), stored.filter { it.folderId == otherFolder.id }.map { it.id to it.displayOrder })
        assertEquals(listOf(unassigned.id to 0), stored.filter { it.folderId == null }.map { it.id to it.displayOrder })
    }

    @Test
    fun createRejectsMissingAndForeignFolderWithoutChangingSiblingOrder() {
        val account = savedAccount("party-create-folder-owner")
        val foreignAccount = savedAccount("party-create-folder-foreign")
        val foreignFolder = savedFolder(foreignAccount, "외부")
        val existing = service.create(account.id, request("기존"))

        listOf(Long.MAX_VALUE, foreignFolder.id).forEach { folderId ->
            val exception = assertFailsWith<ApiException> {
                service.create(account.id, request("생성 금지", folderId = folderId))
            }
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
            assertEquals(listOf(existing.id to 0), service.findAll(account.id).map { it.id to it.displayOrder })
        }
    }

    @Test
    fun accountLockPrecedesFolderAndPresetReadsForCreateUpdateAndReorder() {
        val account = savedAccount("party-lock-order")
        val folder = savedFolder(account, "폴더")

        clearInvocations(accountQueries, folderQueries, queryRepository)
        val created = service.create(account.id, request("생성", folderId = folder.id))
        inOrder(accountQueries, folderQueries, queryRepository).apply {
            verify(accountQueries).findByIdForUpdate(account.id)
            verify(folderQueries).findOwnedByAccountIdAndId(account.id, folder.id)
            verify(queryRepository).findAllByAccountIdAndFolderId(account.id, folder.id)
        }

        clearInvocations(accountQueries, folderQueries, queryRepository)
        service.update(account.id, created.id, UpdatePartyPresetRequest("수정", members(), folder.id))
        inOrder(accountQueries, folderQueries, queryRepository).apply {
            verify(accountQueries).findByIdForUpdate(account.id)
            verify(folderQueries).findOwnedByAccountIdAndId(account.id, folder.id)
            verify(queryRepository).findOwnedByAccountIdAndId(account.id, created.id)
        }

        clearInvocations(accountQueries, folderQueries, queryRepository)
        service.reorder(account.id, ReorderPartyPresetsRequest(folder.id, listOf(created.id)))
        inOrder(accountQueries, folderQueries, queryRepository).apply {
            verify(accountQueries).findByIdForUpdate(account.id)
            verify(folderQueries).findOwnedByAccountIdAndId(account.id, folder.id)
            verify(queryRepository).findAllByAccountIdAndFolderId(account.id, folder.id)
        }
    }

    @Test
    fun presetContentUpdateNotifiesAutomationImmediately() {
        val account = savedAccount("party-automation-notify")
        val created = service.create(account.id, request("변경 전"))
        clearInvocations(presetChangeNotifier)

        service.update(account.id, created.id, UpdatePartyPresetRequest("변경 후", members()))

        org.mockito.Mockito.verify(presetChangeNotifier).changed(account.id)
    }

    @Test
    fun reorderPersistsTheCompleteOwnedPresetOrder() {
        val account = savedAccount("party-reorder")
        val first = service.create(account.id, request("첫 번째"))
        val second = service.create(account.id, request("두 번째"))
        val third = service.create(account.id, request("세 번째"))

        val catalog = service.reorder(
            account.id,
            ReorderPartyPresetsRequest(folderId = null, presetIds = listOf(first.id, third.id, second.id)),
        )
        val reordered = catalog.presets.filter { it.folderId == null }

        assertEquals(listOf(first.id, third.id, second.id), reordered.map { it.id })
        assertEquals(listOf(0, 1, 2), reordered.map { it.displayOrder })
        assertEquals(reordered, service.findAll(account.id))
    }

    @Test
    fun reorderRejectsDuplicateMissingUnknownAndForeignIdsWithoutChangingOrder() {
        val account = savedAccount("party-reorder-invalid")
        val other = savedAccount("party-reorder-foreign")
        val first = service.create(account.id, request("첫 번째"))
        val second = service.create(account.id, request("두 번째"))
        val foreign = service.create(other.id, request("다른 계정"))
        val originalIds = service.findAll(account.id).map { it.id }
        val invalidRequests = listOf(
            listOf(first.id, first.id),
            listOf(first.id),
            listOf(first.id, Long.MAX_VALUE),
            listOf(first.id, foreign.id),
        )

        invalidRequests.forEach { presetIds ->
            val exception = assertFailsWith<ApiException> {
                service.reorder(account.id, ReorderPartyPresetsRequest(folderId = null, presetIds = presetIds))
            }
            assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
            assertEquals(originalIds, service.findAll(account.id).map { it.id })
        }

        assertEquals(setOf(first.id, second.id), originalIds.toSet())
    }

    @Test
    fun reorderIsExactAndScopedToRequestedFolderAndReturnsCatalog() {
        val account = savedAccount("party-folder-reorder")
        val folder = savedFolder(account, "폴더")
        val otherFolder = savedFolder(account, "다른 폴더")
        val first = service.create(account.id, request("첫 번째", folderId = folder.id))
        val second = service.create(account.id, request("두 번째", folderId = folder.id))
        val unassigned = service.create(account.id, request("미분류"))
        val other = service.create(account.id, request("다른 폴더 프리셋", folderId = otherFolder.id))

        val catalog = service.reorder(
            account.id,
            ReorderPartyPresetsRequest(folderId = folder.id, presetIds = listOf(first.id, second.id)),
        )

        assertEquals(listOf(first.id, second.id), catalog.presets.filter { it.folderId == folder.id }.map { it.id })
        assertEquals(listOf(0, 1), catalog.presets.filter { it.folderId == folder.id }.map { it.displayOrder })
        assertEquals(listOf(unassigned.id to 0), catalog.presets.filter { it.folderId == null }.map { it.id to it.displayOrder })
        assertEquals(listOf(other.id to 0), catalog.presets.filter { it.folderId == otherFolder.id }.map { it.id to it.displayOrder })
        assertEquals(setOf(folder.id, otherFolder.id), catalog.folders.map { it.id }.toSet())

        listOf(
            listOf(first.id, first.id),
            listOf(first.id),
            listOf(first.id, second.id, Long.MAX_VALUE),
            listOf(first.id, other.id),
            listOf(first.id, unassigned.id),
        ).forEach { invalidIds ->
            val exception = assertFailsWith<ApiException> {
                service.reorder(account.id, ReorderPartyPresetsRequest(folder.id, invalidIds))
            }
            assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
        }
    }

    @Test
    fun reorderAllowsEmptyOnlyForEmptyOwnedFolderAndHidesForeignFolder() {
        val account = savedAccount("party-empty-folder-reorder")
        val empty = savedFolder(account, "빈 폴더")
        val foreignAccount = savedAccount("party-empty-folder-foreign")
        val foreign = savedFolder(foreignAccount, "외부 폴더")

        assertEquals(empty.id, service.reorder(account.id, ReorderPartyPresetsRequest(empty.id, emptyList())).folders.single().id)
        listOf(foreign.id, Long.MAX_VALUE).forEach { folderId ->
            val exception = assertFailsWith<ApiException> {
                service.reorder(account.id, ReorderPartyPresetsRequest(folderId, emptyList()))
            }
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
        }
    }

    @Test
    fun reorderRejectsEmptyListWhenFolderHasPresetWithoutChangingOrder() {
        val account = savedAccount("party-nonempty-folder-empty-reorder")
        val folder = savedFolder(account, "프리셋 있음")
        val first = service.create(account.id, request("첫 번째", folderId = folder.id))
        val second = service.create(account.id, request("두 번째", folderId = folder.id))
        val before = service.findAll(account.id).filter { it.folderId == folder.id }

        val exception = assertFailsWith<ApiException> {
            service.reorder(account.id, ReorderPartyPresetsRequest(folder.id, emptyList()))
        }

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
        assertEquals(listOf(second.id, first.id), before.map { it.id })
        assertEquals(before, service.findAll(account.id).filter { it.folderId == folder.id })
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
        service.makePrimary(account.id, created.id)

        val updated = service.update(
            account.id,
            created.id,
            UpdatePartyPresetRequest(
                name = "변경 후",
                members = members(characterId = "char-after", patternSlot = 1),
            ),
        )

        assertEquals("변경 후", updated.name)
        assertEquals(true, updated.isPrimary)
        assertEquals(5L, queryRepository.countMembers(created.id))
        assertEquals("char-after", updated.members.first().characterId)
        assertEquals(1, updated.members.first().patternSlot)
        assertEquals(
            listOf("char-after", null, null, null, null),
            queryRepository.findMembersByPresetIds(listOf(created.id)).map { it.character?.hofCharacterId },
        )
    }

    @Test
    fun updateMovesPresetAcrossNullableFoldersAndNormalizesBothSides() {
        val account = savedAccount("party-move-folders")
        val folderA = savedFolder(account, "A")
        val folderB = savedFolder(account, "B")
        val unassignedPeer = service.create(account.id, request("미분류 형제"))
        val moving = service.create(account.id, request("이동", folderId = folderA.id))
        val sourcePeer = service.create(account.id, request("출발 형제", folderId = folderA.id))
        val destinationPeer = service.create(account.id, request("도착 형제", folderId = folderB.id))
        service.makePrimary(account.id, moving.id)
        val createdAt = moving.createdAt

        val movedToB = service.update(
            account.id,
            moving.id,
            UpdatePartyPresetRequest("B로", members(), folderB.id),
        )
        assertEquals(moving.id, movedToB.id)
        assertEquals(true, movedToB.isPrimary)
        assertEquals(createdAt, movedToB.createdAt)
        assertEquals(folderB.id, movedToB.folderId)
        assertEquals(listOf(sourcePeer.id to 0), service.findAll(account.id).filter { it.folderId == folderA.id }.map { it.id to it.displayOrder })
        assertEquals(listOf(moving.id to 0, destinationPeer.id to 1), service.findAll(account.id).filter { it.folderId == folderB.id }.map { it.id to it.displayOrder })

        val movedToNull = service.update(account.id, moving.id, UpdatePartyPresetRequest("미분류로", members(), null))
        assertNull(movedToNull.folderId)
        assertEquals(listOf(destinationPeer.id to 0), service.findAll(account.id).filter { it.folderId == folderB.id }.map { it.id to it.displayOrder })
        assertEquals(listOf(moving.id to 0, unassignedPeer.id to 1), service.findAll(account.id).filter { it.folderId == null }.map { it.id to it.displayOrder })

        val movedToA = service.update(account.id, moving.id, UpdatePartyPresetRequest("A로", members(), folderA.id))
        assertEquals(folderA.id, movedToA.folderId)
        assertEquals(listOf(moving.id to 0, sourcePeer.id to 1), service.findAll(account.id).filter { it.folderId == folderA.id }.map { it.id to it.displayOrder })
        assertEquals(listOf(unassignedPeer.id to 0), service.findAll(account.id).filter { it.folderId == null }.map { it.id to it.displayOrder })
    }

    @Test
    fun updateInSameFolderPreservesDisplayOrder() {
        val account = savedAccount("party-same-folder")
        val folder = savedFolder(account, "폴더")
        val older = service.create(account.id, request("기존", folderId = folder.id))
        service.create(account.id, request("앞 프리셋", folderId = folder.id))

        val updated = service.update(account.id, older.id, UpdatePartyPresetRequest("이름 변경", members(), folder.id))

        assertEquals(1, updated.displayOrder)
        assertEquals(listOf(0, 1), service.findAll(account.id).filter { it.folderId == folder.id }.map { it.displayOrder })
    }

    @Test
    fun movingPresetPreservesStoredExplicitAutomationReference() {
        val account = savedAccount("party-move-automation-reference")
        val source = savedFolder(account, "출발")
        val destination = savedFolder(account, "도착")
        val character = savedCharacter(account, "automation-character")
        savedPatternSlot(character, 2)
        val requestedMembers = members(character.hofCharacterId, 2)
        val preset = service.create(
            account.id,
            CreatePartyPresetRequest("자동화 프리셋", requestedMembers, source.id),
        )
        service.makePrimary(account.id, preset.id)
        val entry = automationEntries.save(
            AutomationEntryEntity(
                account = account,
                type = AutomationType.BATTLE_MAP,
                priority = 0,
                enabled = true,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        val automationMap = battleMaps.save(
            BattleAutomationMapEntity(
                entry = entry,
                categoryId = "battle_map",
                mapCode = "gb0",
                dailyTargetCount = 1,
                presetMode = PresetSelectionMode.EXPLICIT,
                partyPreset = requireNotNull(queryRepository.findOwnedByAccountIdAndId(account.id, preset.id)),
                executionOrder = 0,
            ),
        )
        entityManager.flush()

        val moved = service.update(
            account.id,
            preset.id,
            UpdatePartyPresetRequest("이동됨", requestedMembers, destination.id),
        )
        entityManager.flush()
        entityManager.clear()

        assertEquals(preset.id, moved.id)
        assertEquals(destination.id, moved.folderId)
        assertEquals(true, moved.isPrimary)
        assertEquals((0..4).toList(), moved.members.map { it.slotIndex })
        assertEquals(character.hofCharacterId, moved.members.first().characterId)
        assertEquals(2, moved.members.first().patternSlot)
        val persisted = service.findAll(account.id).single()
        assertEquals(moved, persisted)
        assertEquals(character.hofCharacterId, persisted.members.first().characterId)
        assertEquals(2, persisted.members.first().patternSlot)
        assertEquals(
            preset.id,
            entityManager.createNativeQuery("select party_preset_id from battle_automation_maps where id = :id")
                .setParameter("id", automationMap.id)
                .singleResult.toString().toLong(),
        )
    }

    @Test
    fun invalidFolderOrMemberUpdateLeavesFolderOrderNameAndMembersUntouched() {
        val account = savedAccount("party-folder-update-rollback")
        val folder = savedFolder(account, "폴더")
        val character = savedCharacter(account, "selected")
        savedPatternSlot(character, 0)
        val moving = service.create(account.id, request("원본", "selected", 0))
        val before = service.findAll(account.id).single()

        val invalidRequests = listOf(
            UpdatePartyPresetRequest("폴더 오류", members("selected", 0), Long.MAX_VALUE),
            UpdatePartyPresetRequest("멤버 오류", members("selected", 1), folder.id),
        )
        invalidRequests.forEach { request ->
            assertFailsWith<ApiException> { service.update(account.id, moving.id, request) }
            assertEquals(before, service.findAll(account.id).single())
        }
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

    @Test
    fun selectingSecondPresetAsPrimaryClearsThePreviousPrimaryForTheAccount() {
        val account = savedAccount("party-primary-switch")
        val first = service.create(account.id, request("첫 번째"))
        val second = service.create(account.id, request("두 번째"))

        val firstSelected = service.makePrimary(account.id, first.id)
        val secondSelected = service.makePrimary(account.id, second.id)
        entityManager.flush()
        entityManager.clear()

        assertEquals(true, firstSelected.isPrimary)
        assertEquals(true, secondSelected.isPrimary)
        assertEquals(
            mapOf(first.id to false, second.id to true),
            service.findAll(account.id).associate { it.id to it.isPrimary },
        )
        assertEquals(second.id, queryRepository.findPrimaryByAccountId(account.id)?.id)
    }

    @Test
    fun deletingPrimaryLeavesAccountWithoutAnImplicitReplacement() {
        val account = savedAccount("party-primary-delete")
        val remaining = service.create(account.id, request("남을 프리셋"))
        val primary = service.create(account.id, request("삭제할 기본 프리셋"))
        service.makePrimary(account.id, primary.id)

        service.delete(account.id, primary.id)
        entityManager.flush()
        entityManager.clear()

        assertNull(queryRepository.findPrimaryByAccountId(account.id))
        assertEquals(false, service.findAll(account.id).single { it.id == remaining.id }.isPrimary)
    }

    @Test
    fun makePrimaryEnforcesPresetOwnership() {
        val owner = savedAccount("party-primary-owner")
        val requester = savedAccount("party-primary-requester")
        val foreignPreset = service.create(owner.id, request("다른 계정 프리셋"))

        val exception = assertFailsWith<ApiException> {
            service.makePrimary(requester.id, foreignPreset.id)
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
        assertNull(queryRepository.findPrimaryByAccountId(owner.id))
        assertNull(queryRepository.findPrimaryByAccountId(requester.id))
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
        folderId: Long? = null,
    ): CreatePartyPresetRequest =
        CreatePartyPresetRequest(
            name = name,
            members = members(characterId = characterId, patternSlot = patternSlot),
            folderId = folderId,
        )

    private fun savedFolder(account: HofAccountEntity, name: String): PartyPresetFolderEntity =
        folderRepository.save(
            PartyPresetFolderEntity(
                account = account,
                name = name,
                displayOrder = 0,
                createdAt = NOW,
                updatedAt = NOW,
            ),
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
