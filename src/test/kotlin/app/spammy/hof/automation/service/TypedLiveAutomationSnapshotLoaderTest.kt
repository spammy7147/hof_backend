package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.battle.service.BattleMapIdentityResolver
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.quest.service.QuestGatewayService
import java.time.Instant
import kotlin.test.Test
import org.mockito.Mockito
import app.spammy.hof.party.entity.*
import app.spammy.hof.character.entity.*
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TypedLiveAutomationSnapshotLoaderTest {
    @Test
    fun `primary rematerializes while explicit and per snapshot execution identities remain stable`() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = HofAccountEntity(7, "login", "encrypted", now)
        val primaryA = preset(101, account, "A", now)
        val primaryB = preset(102, account, "B", now)
        val explicitX = preset(103, account, "X", now)
        val questEntry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
        val battleEntry = AutomationEntryEntity(11, account, AutomationType.BATTLE_MAP, 1, true, now, now)
        val adventureEntry = AutomationEntryEntity(12, account, AutomationType.ADVENTURE_MAP, 2, true, now, now)
        val selection = QuestAutomationSelectionEntity(20, questEntry, "q", true, 0)
        val typed = Mockito.mock(TypedAutomationQueryRepository::class.java)
        val mapQuery = Mockito.mock(BattleMapQueryRepository::class.java)
        val mapService = Mockito.mock(BattleMapService::class.java)
        val presets = Mockito.mock(PartyPresetQueryRepository::class.java)
        val quest = Mockito.mock(QuestGatewayService::class.java)
        val identities = Mockito.mock(BattleMapIdentityResolver::class.java)
        val loader = TypedLiveAutomationSnapshotLoader(
            quest, typed, mapQuery, presets, identities, mapService, TimeProvider { now },
            HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
        )
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, battleEntry, adventureEntry))
        Mockito.`when`(typed.findQuestSelections(10)).thenReturn(listOf(selection))
        Mockito.`when`(typed.findQuestMaps(listOf(20))).thenReturn(listOf(QuestAutomationMapEntity(21, selection, "m", "battle_map", "qmap", PresetSelectionMode.PRIMARY, null, 0, true)))
        Mockito.`when`(typed.findBattleSettings(11)).thenReturn(listOf(BattleAutomationMapEntity(22, battleEntry, "battle_map", "bmap", 1, PresetSelectionMode.PRIMARY, null, 0)))
        Mockito.`when`(typed.findAdventureSettings(12)).thenReturn(listOf(AdventureAutomationMapEntity(23, adventureEntry, "adventure_map", "amap", PresetSelectionMode.EXPLICIT, explicitX, 0)))
        Mockito.`when`(quest.load(7)).thenReturn(emptyList())
        Mockito.`when`(mapQuery.findAllStatesForExecution(7)).thenReturn(emptyList())
        Mockito.`when`(presets.findAllByAccountId(7)).thenReturn(listOf(primaryA, primaryB, explicitX))
        Mockito.`when`(presets.findPrimaryByAccountId(7)).thenReturn(primaryA, primaryB)
        Mockito.`when`(presets.findMembersByPresetIds(listOf(101L, 102L, 103L))).thenReturn(
            members(primaryA, "A", account, now) + members(primaryB, "B", account, now) + members(explicitX, "X", account, now),
        )

        val first = loader.loadTyped(7)
        val second = loader.loadTyped(7)
        val firstQuest = requireNotNull(first.entries[0].quest)
        val secondQuest = requireNotNull(second.entries[0].quest)
        assertEquals((0..4).map { "A-$it" }, firstQuest.selections.single().maps.single().preset.resolvedParty?.characterIds)
        assertEquals((0..4).map { "B-$it" }, secondQuest.selections.single().maps.single().preset.resolvedParty?.characterIds)
        assertEquals((1..5).toList(), secondQuest.selections.single().maps.single().preset.resolvedParty?.patternLoads?.map { it.slot })
        val firstBattle = requireNotNull(first.entries[1].battle)
        val secondBattle = requireNotNull(second.entries[1].battle)
        assertEquals(101, firstBattle.primaryPresetId); assertEquals(102, secondBattle.primaryPresetId)
        assertNotEquals(firstBattle.executionIdentity, secondBattle.executionIdentity)
        assertTrue(firstBattle.executionIdentity.isNotBlank() && firstBattle.executionIdentity.length <= 128)
        val firstAdventure = requireNotNull(first.entries[2].adventure)
        val secondAdventure = requireNotNull(second.entries[2].adventure)
        val firstResolution = firstAdventure.presetResolutions.getValue(23) as AdventureMapPresetResolution.Valid
        val secondResolution = secondAdventure.presetResolutions.getValue(23) as AdventureMapPresetResolution.Valid
        assertEquals(103, firstResolution.resolvedPresetId); assertEquals(103, secondResolution.resolvedPresetId)
        assertEquals((0..4).map { "X-$it" }, secondResolution.resolvedParty?.characterIds)
        assertEquals(firstAdventure.executionIdentities.getValue(23), firstAdventure.executionIdentities.getValue(23))
        assertNotEquals(firstAdventure.executionIdentities.getValue(23), secondAdventure.executionIdentities.getValue(23))
        assertTrue(secondAdventure.executionIdentities.getValue(23).length <= 128)
    }

    @Test
    fun `quest without configured maps refreshes battle and adventure alias categories`() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = HofAccountEntity(7, "login", "encrypted", now)
        val typed = Mockito.mock(TypedAutomationQueryRepository::class.java)
        val mapQuery = Mockito.mock(BattleMapQueryRepository::class.java)
        val mapService = Mockito.mock(BattleMapService::class.java)
        val presets = Mockito.mock(PartyPresetQueryRepository::class.java)
        val quest = Mockito.mock(QuestGatewayService::class.java)
        val identities = Mockito.mock(BattleMapIdentityResolver::class.java)
        val loader = TypedLiveAutomationSnapshotLoader(
            quest, typed, mapQuery, presets, identities, mapService, TimeProvider { now },
            HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
        )
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)))
        Mockito.`when`(typed.findQuestSelections(10)).thenReturn(emptyList())
        Mockito.`when`(typed.findQuestMaps(emptyList())).thenReturn(emptyList())
        Mockito.`when`(quest.load(7)).thenReturn(emptyList())
        Mockito.`when`(mapQuery.findAllStatesForExecution(7)).thenReturn(emptyList())
        Mockito.`when`(presets.findAllByAccountId(7)).thenReturn(emptyList())
        Mockito.`when`(presets.findMembersByPresetIds(emptyList())).thenReturn(emptyList())

        loader.loadTyped(7)

        Mockito.verify(mapService).findMaps(7, "battle_map")
        Mockito.verify(mapService).findMaps(7, "adventure_map")
    }

    private fun preset(id: Long, account: HofAccountEntity, name: String, now: Instant) = PartyPresetEntity(id, account, name, now, now)
    private fun members(preset: PartyPresetEntity, prefix: String, account: HofAccountEntity, now: Instant) = (0..4).map { slot ->
        val character = CharacterEntity(
            id = 1_000 + preset.id * 10 + slot, account = account, hofCharacterId = "$prefix-$slot",
            name = "$prefix-$slot", job = "job", level = 1, patternSlotCount = 1, imageUrl = null, updatedAt = now,
        )
        val pattern = CharacterPatternSlotEntity(2_000 + preset.id * 10 + slot, character, (slot + 1).toString(), "p", true)
        PartyPresetMemberEntity(preset, slot, character, pattern)
    }
}
