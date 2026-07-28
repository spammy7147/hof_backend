package app.spammy.hof.party.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.character.repository.CharacterPatternSlotCommandRepository
import app.spammy.hof.character.repository.CharacterRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetFolderEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.party.repository.PartyPresetFolderQueryRepository
import app.spammy.hof.party.repository.PartyPresetFolderRepository
import app.spammy.hof.party.repository.PartyPresetMemberCommandRepository
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.party.repository.PartyPresetRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.hibernate.SessionFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(
    QueryDslConfig::class,
    PartyPresetFolderQueryRepository::class,
    PartyPresetQueryRepository::class,
    PartyPresetResponseMapper::class,
    PartyPresetCatalogService::class,
)
class PartyPresetCatalogServiceTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var characterRepository: CharacterRepository

    @Autowired
    private lateinit var patternSlotRepository: CharacterPatternSlotCommandRepository

    @Autowired
    private lateinit var folderRepository: PartyPresetFolderRepository

    @Autowired
    private lateinit var presetRepository: PartyPresetRepository

    @Autowired
    private lateinit var memberRepository: PartyPresetMemberCommandRepository

    @Autowired
    private lateinit var service: PartyPresetCatalogService

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    @Test
    fun assemblesOwnedFoldersAssignedAndUnassignedPresetsWithMembersInThreeQueries() {
        val account = savedAccount("catalog-owner")
        val otherAccount = savedAccount("catalog-other")
        val character = savedCharacter(account, "catalog-character")
        val pattern = savedPatternSlot(character, 3)
        val root = savedFolder(account, "루트", displayOrder = 0)
        val child = savedFolder(account, "자식", displayOrder = 0, parent = root)
        val unassigned = savedPreset(account, "미분류", displayOrder = 4)
        val assigned = savedPreset(account, "자식 프리셋", displayOrder = 0, folder = child)
        val foreignFolder = savedFolder(otherAccount, "다른 계정 폴더", displayOrder = 0)
        val foreignPreset = savedPreset(otherAccount, "다른 계정 프리셋", displayOrder = 0, folder = foreignFolder)
        saveMembers(unassigned)
        saveMembers(assigned, character, pattern)
        saveMembers(foreignPreset)
        entityManager.flush()
        entityManager.clear()
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()

        val response = service.find(account.id)

        assertEquals(3L, statistics.prepareStatementCount)
        assertEquals(listOf(root.id, child.id), response.folders.map { folder -> folder.id })
        assertNull(response.folders.first().parentFolderId)
        assertEquals(root.id, response.folders.last().parentFolderId)
        assertEquals(listOf(unassigned.id, assigned.id), response.presets.map { preset -> preset.id })
        assertNull(response.presets.first().folderId)
        assertEquals(child.id, response.presets.last().folderId)
        assertEquals(listOf(0, 1, 2, 3, 4), response.presets.last().members.map { member -> member.slotIndex })
        assertEquals("catalog-character", response.presets.last().members.first().characterId)
        assertEquals(3, response.presets.last().members.first().patternSlot)
        assertEquals(NOW.toString(), response.folders.first().createdAt)
        assertEquals(NOW.toString(), response.folders.first().updatedAt)
    }

    @Test
    fun emptyAccountReturnsAnEmptyCatalogWithoutAMemberQuery() {
        val account = savedAccount("catalog-empty")
        entityManager.flush()
        entityManager.clear()
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()

        val response = service.find(account.id)

        assertEquals(emptyList(), response.folders)
        assertEquals(emptyList(), response.presets)
        assertEquals(2L, statistics.prepareStatementCount)
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

    private fun savedFolder(
        account: HofAccountEntity,
        name: String,
        displayOrder: Int,
        parent: PartyPresetFolderEntity? = null,
    ): PartyPresetFolderEntity =
        folderRepository.save(
            PartyPresetFolderEntity(
                account = account,
                parent = parent,
                name = name,
                displayOrder = displayOrder,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )

    private fun savedPreset(
        account: HofAccountEntity,
        name: String,
        displayOrder: Int,
        folder: PartyPresetFolderEntity? = null,
    ): PartyPresetEntity =
        presetRepository.save(
            PartyPresetEntity(
                account = account,
                folder = folder,
                name = name,
                displayOrder = displayOrder,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )

    private fun saveMembers(
        preset: PartyPresetEntity,
        character: CharacterEntity? = null,
        patternSlot: CharacterPatternSlotEntity? = null,
    ) {
        memberRepository.saveAll(
            (4 downTo 0).map { slotIndex ->
                PartyPresetMemberEntity(
                    preset = preset,
                    slotIndex = slotIndex,
                    character = character.takeIf { slotIndex == 0 },
                    patternSlot = patternSlot.takeIf { slotIndex == 0 },
                )
            },
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-27T00:00:00Z")
    }
}
