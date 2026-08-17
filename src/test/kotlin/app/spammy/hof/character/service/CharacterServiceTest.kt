package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.character.entity.CharacterActionPatternEntity
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterEquipmentEntity
import app.spammy.hof.character.entity.CharacterGuardSettingEntity
import app.spammy.hof.character.entity.CharacterHofIdHistoryEntity
import app.spammy.hof.character.entity.CharacterHofIdLinkReason
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.character.entity.CharacterPositionChoiceEntity
import app.spammy.hof.character.entity.CharacterSkillEntity
import app.spammy.hof.character.entity.CharacterSkillType
import app.spammy.hof.character.entity.CharacterStatsEntity
import app.spammy.hof.character.entity.CharacterStatusLineEntity
import app.spammy.hof.character.repository.CharacterActionPatternCommandRepository
import app.spammy.hof.character.repository.CharacterEquipmentCommandRepository
import app.spammy.hof.character.repository.CharacterGuardSettingCommandRepository
import app.spammy.hof.character.repository.CharacterHofIdHistoryRepository
import app.spammy.hof.character.repository.CharacterIdentityQueryRepository
import app.spammy.hof.character.repository.CharacterPatternSlotCommandRepository
import app.spammy.hof.character.repository.CharacterPositionChoiceCommandRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.repository.CharacterRepository
import app.spammy.hof.character.repository.CharacterSkillCommandRepository
import app.spammy.hof.character.repository.CharacterStatsCommandRepository
import app.spammy.hof.character.repository.CharacterStatusLineCommandRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.common.error.ApiException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class CharacterServiceTest {
    private val now = Instant.parse("2026-07-07T00:00:00Z")
    private val account = HofAccountEntity(
        id = 1L,
        loginId = "abcd12",
        encryptedPassword = "qwer12",
        createdAt = now,
    )
    private val characterRepository = Mockito.mock(CharacterRepository::class.java)
    private val characterQueryRepository = Mockito.mock(CharacterQueryRepository::class.java)
    private val characterHofIdHistoryRepository = Mockito.mock(CharacterHofIdHistoryRepository::class.java)
    private val characterIdentityQueryRepository = Mockito.mock(CharacterIdentityQueryRepository::class.java)
    private val statsRepository = Mockito.mock(CharacterStatsCommandRepository::class.java)
    private val statusLineRepository = Mockito.mock(CharacterStatusLineCommandRepository::class.java)
    private val patternSlotRepository = Mockito.mock(CharacterPatternSlotCommandRepository::class.java)
    private val actionPatternRepository = Mockito.mock(CharacterActionPatternCommandRepository::class.java)
    private val guardSettingRepository = Mockito.mock(CharacterGuardSettingCommandRepository::class.java)
    private val positionChoiceRepository = Mockito.mock(CharacterPositionChoiceCommandRepository::class.java)
    private val equipmentRepository = Mockito.mock(CharacterEquipmentCommandRepository::class.java)
    private val skillRepository = Mockito.mock(CharacterSkillCommandRepository::class.java)
    private val service = CharacterService(
        characterRepository = characterRepository,
        characterQueryRepository = characterQueryRepository,
        characterHofIdHistoryRepository = characterHofIdHistoryRepository,
        characterIdentityQueryRepository = characterIdentityQueryRepository,
        statsRepository = statsRepository,
        statusLineRepository = statusLineRepository,
        patternSlotRepository = patternSlotRepository,
        actionPatternRepository = actionPatternRepository,
        guardSettingRepository = guardSettingRepository,
        positionChoiceRepository = positionChoiceRepository,
        equipmentRepository = equipmentRepository,
        skillRepository = skillRepository,
        timeProvider = TimeProvider { now },
    )

    @Test
    fun `stable record detail cannot be read through another account`() {
        Mockito.`when`(characterQueryRepository.findByAccountIdAndId(2L, 10L)).thenReturn(null)

        assertFailsWith<ApiException> { service.findDetailById(accountId = 2L, characterId = 10L) }

        Mockito.verify(characterQueryRepository).findByAccountIdAndId(2L, 10L)
    }

    @Test
    fun findAllReturnsStoredPatternSlotLabelsForBattleSelection() {
        val character = character()
        Mockito.`when`(characterQueryRepository.findAllByAccountId(1L)).thenReturn(listOf(character))
        Mockito.`when`(characterQueryRepository.findPatternSlotsByCharacterIds(listOf(10L)))
            .thenReturn(
                listOf(
                    patternSlot(character, id = 20L, slot = "0", label = "범용"),
                    patternSlot(character, id = 21L, slot = "1", label = "대회랑"),
                ),
            )

        val characters = service.findAll(accountId = 1L)

        assertEquals(1, characters.size)
        assertEquals("http://sic.zerosic.com/ZeroHOF/image/char/sknight02.gif", characters.single().imageUrl)
        assertEquals(2, characters.single().patternSlots.size)
        assertEquals("0", characters.single().patternSlots[0].slot)
        assertEquals("범용", characters.single().patternSlots[0].label)
        assertTrue(characters.single().patternSlots[0].canLoad)
        assertEquals("대회랑", characters.single().patternSlots[1].label)
        Mockito.verify(characterQueryRepository).findAllByAccountId(1L)
        Mockito.verify(characterQueryRepository).findPatternSlotsByCharacterIds(listOf(10L))
    }

    @Test
    fun findDetailReturnsStoredCharacterSnapshot() {
        val character = character(patternSlotCount = 1)
        val characterIds = listOf(character.id)
        Mockito.`when`(characterQueryRepository.findByAccountIdAndHofCharacterId(1L, "111")).thenReturn(character)
        Mockito.`when`(characterIdentityQueryRepository.findHistory(character.id)).thenReturn(
            listOf(
                CharacterHofIdHistoryEntity(
                    character = character,
                    account = account,
                    hofCharacterId = "old-111",
                    validFrom = now.minusSeconds(60),
                    validTo = now,
                    linkReason = CharacterHofIdLinkReason.KNOCKBACK,
                    userConfirmed = false,
                ),
            ),
        )
        Mockito.`when`(characterQueryRepository.findStatusLinesByCharacterIds(characterIds))
            .thenReturn(listOf(CharacterStatusLineEntity(id = 30L, character = character, lineOrder = 0, content = "HP : 5628 + 5033")))
        Mockito.`when`(characterQueryRepository.findPatternSlotsByCharacterIds(characterIds))
            .thenReturn(listOf(patternSlot(character, id = 20L, slot = "0", label = "범용")))
        Mockito.`when`(characterQueryRepository.findStatsByCharacterId(character.id))
            .thenReturn(
                CharacterStatsEntity(
                    character = character,
                    atk = 116,
                    matk = 5,
                    defBase = 141,
                    defBonus = 1649,
                    mdefBase = 111,
                    mdefBonus = 1089,
                    handleUsed = 25,
                    handleMax = 25,
                    costUsed = 0,
                    costMax = 10,
                ),
            )
        Mockito.`when`(characterQueryRepository.findActionPatternsByCharacterIds(characterIds))
            .thenReturn(
                listOf(
                    CharacterActionPatternEntity(
                        id = 40L,
                        character = character,
                        rowIndex = 0,
                        judge = "1701",
                        judgeText = "자신이 후방",
                        quantity = "0",
                        quantityText = "0",
                        skill = "4003",
                        skillText = "Stance Restore(Self) - (SP:0)",
                    ),
                ),
            )
        Mockito.`when`(characterQueryRepository.findGuardSettingByCharacterId(character.id))
            .thenReturn(
                CharacterGuardSettingEntity(
                    character = character,
                    selectedPosition = "front",
                    guardValue = "always",
                    guardText = "반드시 지킨다",
                ),
            )
        Mockito.`when`(characterQueryRepository.findPositionChoicesByCharacterIds(characterIds))
            .thenReturn(
                listOf(
                    CharacterPositionChoiceEntity(id = 50L, character = character, choiceOrder = 0, value = "front", checked = true),
                    CharacterPositionChoiceEntity(id = 51L, character = character, choiceOrder = 1, value = "back", checked = false),
                ),
            )
        Mockito.`when`(characterQueryRepository.findEquipmentByCharacterIds(characterIds))
            .thenReturn(
                listOf(
                    CharacterEquipmentEntity(
                        id = 60L,
                        character = character,
                        equipmentOrder = 0,
                        slot = "weapon",
                        part = "Weapon",
                        name = "Soulcollector's Sword Breaker",
                        iconUrl = "swordb.gif",
                        description = "Atk:96",
                        checked = false,
                    ),
                ),
            )
        Mockito.`when`(characterQueryRepository.findSkillsByCharacterIds(characterIds))
            .thenReturn(
                listOf(
                    CharacterSkillEntity(
                        id = 70L,
                        character = character,
                        skillType = CharacterSkillType.LEARNED,
                        skillOrder = 0,
                        sourceValue = "",
                        name = "Attack / enemy - individual",
                        iconUrl = "skill_042.png",
                        category = "사용 가능 스킬(Mastered)",
                    ),
                    CharacterSkillEntity(
                        id = 71L,
                        character = character,
                        skillType = CharacterSkillType.LEARNABLE,
                        skillOrder = 0,
                        sourceValue = "1014",
                        name = "Double Quick Slash / 8pt",
                        iconUrl = "skill_074z.png",
                        category = "",
                    ),
                ),
            )

        val detail = service.findDetail(accountId = 1L, hofCharacterId = "111")

        assertEquals(10L, detail.id)
        assertEquals("111", detail.hofCharacterId)
        assertEquals("소셜", detail.name)
        assertEquals("Social Knight", detail.job)
        assertEquals(60, detail.level)
        assertEquals("http://sic.zerosic.com/ZeroHOF/image/char/sknight02.gif", detail.imageUrl)
        assertEquals(listOf("HP : 5628 + 5033"), detail.statusLines)
        assertEquals("범용", detail.patternSlots.single().label)
        assertEquals(116, detail.stats.atk)
        assertEquals(1649, detail.stats.defBonus)
        assertEquals("자신이 후방", detail.actionPatterns.single().judgeText)
        assertEquals("front", detail.positionGuard.selectedPosition)
        assertEquals("반드시 지킨다", detail.positionGuard.guardText)
        assertEquals("Soulcollector's Sword Breaker", detail.equipment.single().name)
        assertEquals("Attack / enemy - individual", detail.learnedSkills.single().name)
        assertEquals("1014", detail.learnableSkills.single().value)
        assertEquals("old-111", detail.hofIdHistory.single().hofCharacterId)
        assertEquals("KNOCKBACK", detail.hofIdHistory.single().linkReason)
    }

    private fun character(patternSlotCount: Int = 2): CharacterEntity =
        CharacterEntity(
            id = 10L,
            account = account,
            hofCharacterId = "111",
            name = "소셜",
            job = "Social Knight",
            level = 60,
            patternSlotCount = patternSlotCount,
            imageUrl = "http://sic.zerosic.com/ZeroHOF/image/char/sknight02.gif",
            updatedAt = now,
        )

    private fun patternSlot(
        character: CharacterEntity,
        id: Long,
        slot: String,
        label: String,
    ): CharacterPatternSlotEntity =
        CharacterPatternSlotEntity(
            id = id,
            character = character,
            slotCode = slot,
            label = label,
            canLoad = true,
        )
}
