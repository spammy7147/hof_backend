package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationModuleConfigEntity
import app.spammy.hof.automation.entity.AutomationModuleMapEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestMapEntity
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.repository.AutomationModuleAggregate
import app.spammy.hof.automation.repository.AutomationModuleQuestAggregate
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito

class AutomationModuleReadinessEvaluatorTest {
    private val presetQueryRepository = Mockito.mock(PartyPresetQueryRepository::class.java)
    private val evaluator = AutomationModuleReadinessEvaluator(presetQueryRepository)

    @Test
    fun evaluatesCompleteMissingPatternEmptyAndMixedPresetsWithOneBatchRead() {
        val completePreset = preset(101L)
        val missingPatternPreset = preset(102L)
        val emptyPreset = preset(103L)
        val completeModule = mapModule(201L, listOf(completePreset))
        val missingPatternModule = mapModule(202L, listOf(missingPatternPreset))
        val emptyPresetModule = mapModule(203L, listOf(emptyPreset))
        val mixedModule = mapModule(204L, listOf(completePreset, missingPatternPreset))
        val mixedQuestModule = keyQuestModule(205L, listOf(completePreset, emptyPreset))
        Mockito.`when`(
            presetQueryRepository.findMembersByPresetIds(setOf(101L, 102L, 103L)),
        ).thenReturn(
            listOf(
                member(completePreset, slot = 0, withCharacter = true, withPattern = true),
                member(completePreset, slot = 1, withCharacter = false, withPattern = false),
                member(missingPatternPreset, slot = 0, withCharacter = true, withPattern = true),
                member(missingPatternPreset, slot = 1, withCharacter = true, withPattern = false),
                member(emptyPreset, slot = 0, withCharacter = false, withPattern = false),
            ),
        )

        val readiness = evaluator.evaluate(
            listOf(completeModule, missingPatternModule, emptyPresetModule, mixedModule, mixedQuestModule),
        )

        assertTrue(readiness.isReady(completeModule))
        assertFalse(readiness.isReady(missingPatternModule))
        assertFalse(readiness.isReady(emptyPresetModule))
        assertFalse(readiness.isReady(mixedModule))
        assertFalse(readiness.isReady(mixedQuestModule))
        Mockito.verify(presetQueryRepository, Mockito.times(1))
            .findMembersByPresetIds(setOf(101L, 102L, 103L))
    }

    @Test
    fun rejectsAPresetWhoseAssignedPatternCannotBeLoaded() {
        val preset = preset(104L)
        val module = mapModule(206L, listOf(preset))
        Mockito.`when`(presetQueryRepository.findMembersByPresetIds(setOf(preset.id))).thenReturn(
            listOf(member(preset, slot = 0, withCharacter = true, withPattern = true, canLoad = false)),
        )

        val readiness = evaluator.evaluate(listOf(module))

        assertFalse(readiness.isReady(module))
    }

    private fun mapModule(
        id: Long,
        presets: List<PartyPresetEntity>,
    ): AutomationModuleAggregate {
        val config = config(id, AutomationModuleType.TIME_BURN)
        return AutomationModuleAggregate(
            config,
            presets.mapIndexed { order, preset ->
                AutomationModuleMapEntity(
                    moduleConfig = config,
                    battleMap = battleMap("map-$id-$order"),
                    partyPreset = preset,
                    executionOrder = order,
                )
            },
            emptyList(),
        )
    }

    private fun keyQuestModule(
        id: Long,
        presets: List<PartyPresetEntity>,
    ): AutomationModuleAggregate {
        val config = config(id, AutomationModuleType.KEY_QUEST)
        val quest = AutomationModuleQuestEntity(moduleConfig = config, questCode = "0571", executionOrder = 0)
        return AutomationModuleAggregate(
            config,
            emptyList(),
            listOf(
                AutomationModuleQuestAggregate(
                    quest,
                    presets.mapIndexed { order, preset ->
                        AutomationModuleQuestMapEntity(
                            moduleQuest = quest,
                            battleMap = battleMap("quest-$id-$order"),
                            partyPreset = preset,
                            executionOrder = order,
                        )
                    },
                ),
            ),
        )
    }

    private fun config(
        id: Long,
        type: AutomationModuleType,
    ) = AutomationModuleConfigEntity(
        id = id,
        profile = profile(),
        moduleType = type,
        enabled = true,
        priority = id.toInt(),
        displayName = "module-$id",
        thresholdPercent = 90.takeIf { type == AutomationModuleType.TIME_BURN },
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun member(
        preset: PartyPresetEntity,
        slot: Int,
        withCharacter: Boolean,
        withPattern: Boolean,
        canLoad: Boolean = true,
    ): PartyPresetMemberEntity {
        val character = CharacterEntity(
            id = preset.id * 10 + slot,
            account = account(),
            hofCharacterId = "character-${preset.id}-$slot",
            name = "캐릭터",
            job = "직업",
            updatedAt = NOW,
        ).takeIf { withCharacter }
        val pattern = character?.let {
            CharacterPatternSlotEntity(
                id = preset.id * 100 + slot,
                character = it,
                slotCode = "0",
                label = "기본",
                canLoad = canLoad,
            )
        }.takeIf { withPattern }
        return PartyPresetMemberEntity(preset, slot, character, pattern)
    }

    private fun preset(id: Long) = PartyPresetEntity(id, account(), "preset-$id", NOW, NOW)

    private fun battleMap(code: String) = BattleMapEntity(
        id = code.hashCode().toLong().let { if (it < 0) -it else it } + 1,
        categoryId = "battle_map",
        mapCode = code,
        name = code,
        normalizedName = code,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun profile() = AutomationProfileEntity(31L, account(), "통합 자동화", "UNIFIED", true, NOW, NOW)

    private fun account() = HofAccountEntity(11L, "readiness", "encrypted", NOW)

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T00:00:00Z")
    }
}
