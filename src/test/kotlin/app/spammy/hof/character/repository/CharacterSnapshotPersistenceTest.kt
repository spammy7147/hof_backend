package app.spammy.hof.character.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.character.entity.*
import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@DataJpaTest
class CharacterSnapshotPersistenceTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var characters: CharacterRepository
    @Autowired private lateinit var sections: CharacterSectionSyncStateCommandRepository
    @Autowired private lateinit var effects: CharacterStatusEffectCommandRepository
    @Autowired private lateinit var faith: CharacterFaithCommandRepository
    @Autowired private lateinit var options: CharacterPatternOptionCommandRepository
    @Autowired private lateinit var stats: CharacterStatsCommandRepository
    @Autowired private lateinit var patternSlots: CharacterPatternSlotCommandRepository
    @Autowired private lateinit var savedPatternRows: CharacterSavedPatternRowCommandRepository
    @Autowired private lateinit var candidates: CharacterEquipmentCandidateCommandRepository
    @Autowired private lateinit var equipmentSavedSlots: CharacterEquipmentSavedSlotCommandRepository
    @Autowired private lateinit var equipmentSavedItems: CharacterEquipmentSavedItemCommandRepository
    @Autowired private lateinit var skills: CharacterSkillCommandRepository
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun `stores section freshness and replacing one section preserves other sections`() {
        val account = accounts.save(HofAccountEntity(loginId = "section-snapshot", encryptedPassword = "x", createdAt = NOW))
        val character = characters.save(
            CharacterEntity(
                account = account,
                hofCharacterId = "character-1",
                name = "소셜",
                job = "Social Knight",
                updatedAt = NOW,
            ),
        )
        sections.saveAll(
            listOf(
                CharacterSectionSyncStateEntity(
                    character = character,
                    section = CharacterSection.STATS,
                    status = CharacterSectionSyncStatus.SUCCESS,
                    parserVersion = "character-v1",
                    lastAttemptedAt = NOW,
                    lastSucceededAt = NOW,
                    observedCount = 8,
                ),
                CharacterSectionSyncStateEntity(
                    character = character,
                    section = CharacterSection.EQUIPMENT_CANDIDATES,
                    status = CharacterSectionSyncStatus.FAILED,
                    parserVersion = "character-v1",
                    lastAttemptedAt = NOW,
                    errorCode = "EXPECTED_SCRIPT_NOT_FOUND",
                    errorMessage = "장비 후보 스크립트를 찾지 못했습니다. observed=0",
                    observedCount = 0,
                ),
            ),
        )
        val oldEffect = effects.save(
            CharacterStatusEffectEntity(
                character = character,
                effectOrder = 0,
                effectType = CharacterStatusEffectType.SET,
                name = "밤을 사냥하는 자",
                valueText = "[SET:밤을 사냥하는 자]",
                description = "Atk+10% · Real DEX+15",
                active = true,
            ),
        )
        faith.save(CharacterFaithEntity(character = character, godName = "Marduk", currentValue = 380000, maxValue = 380000))
        options.save(
            CharacterPatternOptionEntity(
                character = character,
                optionType = CharacterPatternOptionType.CONDITION,
                optionOrder = 0,
                sourceValue = "1000",
                label = "반드시",
                category = "기본",
            ),
        )
        stats.save(
            CharacterStatsEntity(
                character = character,
                expCurrent = 0,
                expMaxed = true,
                hpBase = 5628,
                hpBonus = 5033,
                spBase = 312,
                spBonus = 462,
                strReal = 128,
                strBonus = 95,
                intReal = 59,
                intBonus = 0,
                dexReal = 34,
                dexBonus = 0,
                spdReal = 167,
                spdBonus = 23,
                lukReal = 10,
                lukBonus = 77,
                strDescription = "힘 기반 공격력과 HP가 증가합니다.",
            ),
        )
        val patternSlot = patternSlots.save(
            CharacterPatternSlotEntity(
                character = character,
                slotCode = "0",
                label = "범용",
                canLoad = true,
                selectedPosition = "front",
                guardValue = "always",
                guardText = "반드시 지킨다",
            ),
        )
        savedPatternRows.save(
            CharacterSavedPatternRowEntity(
                patternSlot = patternSlot,
                rowIndex = 0,
                judge = "1000",
                judgeText = "반드시",
                quantity = "0",
                quantityText = "0",
                skill = "1000",
                skillText = "Attack - (SP:0)",
            ),
        )
        candidates.save(
            CharacterEquipmentCandidateEntity(
                character = character,
                candidateOrder = 0,
                sourceValue = "8801",
                typeCode = "weapon",
                name = "Soulcollector's Sword Breaker",
                iconUrl = "swordb.gif",
                description = "Atk:96",
            ),
        )
        val equipmentSlot = equipmentSavedSlots.save(
            CharacterEquipmentSavedSlotEntity(character = character, slotNumber = 1, observedAt = NOW),
        )
        equipmentSavedItems.save(
            CharacterEquipmentSavedItemEntity(
                equipmentSavedSlot = equipmentSlot,
                itemOrder = 0,
                equipmentPart = "Weapon",
                name = "Soulcollector's Sword Breaker",
                iconUrl = "swordb.gif",
                description = "Atk:96",
            ),
        )
        skills.save(
            CharacterSkillEntity(
                character = character,
                skillType = CharacterSkillType.LEARNED,
                skillOrder = 0,
                sourceValue = "1044",
                name = "Parrying",
                iconUrl = "skill_1044.gif",
                category = "Support",
                targetText = "self",
                scopeText = "individual",
                spCost = 0,
                multiplierText = "Def·Mdef +20% (0:-30)",
                description = "데미지 1회 무효화",
            ),
        )
        entityManager.flush()

        effects.delete(oldEffect)
        effects.flush()
        effects.save(
            CharacterStatusEffectEntity(
                character = character,
                effectOrder = 0,
                effectType = CharacterStatusEffectType.EFFECT,
                name = "공격숙련",
                valueText = "+22%",
                description = "방어 무시 능력의 효율과 주는 데미지가 증가합니다.",
            ),
        )
        entityManager.flush()
        entityManager.clear()

        assertEquals(2L, count("CharacterSectionSyncStateEntity"))
        assertEquals(1L, count("CharacterStatusEffectEntity"))
        assertEquals(1L, count("CharacterFaithEntity"))
        assertEquals(1L, count("CharacterPatternOptionEntity"))
        assertEquals(1L, count("CharacterSavedPatternRowEntity"))
        assertEquals(1L, count("CharacterEquipmentCandidateEntity"))
        assertEquals(1L, count("CharacterEquipmentSavedSlotEntity"))
        assertEquals(1L, count("CharacterEquipmentSavedItemEntity"))
        assertEquals(1L, count("CharacterSkillEntity"))
        assertNotNull(
            entityManager.find(
                CharacterSectionSyncStateEntity::class.java,
                CharacterSectionSyncStateId(character.id, CharacterSection.EQUIPMENT_CANDIDATES),
            ).errorMessage,
        )
    }

    private fun count(entity: String): Long =
        entityManager.createQuery("select count(e) from $entity e", Long::class.javaObjectType).singleResult

    companion object {
        private val NOW = Instant.parse("2026-08-17T03:00:00Z")
    }
}
