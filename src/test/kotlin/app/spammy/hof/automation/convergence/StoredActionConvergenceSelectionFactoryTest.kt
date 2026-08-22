package app.spammy.hof.automation.convergence

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.service.BattleAutomationActionSource
import app.spammy.hof.automation.service.BattleMapAutomationAction
import app.spammy.hof.automation.service.ResolvedAutomationParty
import app.spammy.hof.automation.service.HomeQuestAutomationActionType
import app.spammy.hof.automation.service.StoredTypedActionPayload
import app.spammy.hof.automation.service.StoredTypedAutomationAction
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import app.spammy.hof.town.raid.model.RaidAction
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StoredActionConvergenceSelectionFactoryTest {
    private val factory = StoredActionConvergenceSelectionFactory()

    @Test
    fun `퀘스트와 자택 행동은 대상별 격리 범위를 사용한다`() {
        val quest = factory.create(stored(StoredTypedActionPayload.QuestClaim("quest-a", "claim-1")))
        val home = factory.create(stored(StoredTypedActionPayload.HomeQuest(
            "home-a",
            "accept-1",
            HomeQuestAutomationActionType.ACCEPT,
        )))

        assertEquals(AutomationActionKind.QUEST_CLAIM, quest.actionKind)
        assertEquals(AutomationIsolationScope(AutomationIsolationScopeKind.QUEST_TARGET, "quest-a"), quest.scope)
        assertEquals(AutomationActionKind.HOME_ACCEPT, home.actionKind)
        assertEquals(AutomationIsolationScope(AutomationIsolationScopeKind.HOME_TARGET, "home-a"), home.scope)
    }

    @Test
    fun `전투는 소유 자동화에 맞는 격리 범위로 분리한다`() {
        val generic = factory.create(stored(battle(BattleAutomationActionSource.BATTLE_MAP_AUTOMATION)))
        val union = factory.create(stored(battle(BattleAutomationActionSource.UNION_AUTOMATION)))
        val fishing = factory.create(stored(battle(BattleAutomationActionSource.FISHING_AUTOMATION)))
        val raid = factory.create(stored(battle(BattleAutomationActionSource.RAID_AUTOMATION, "raid-7")))

        assertEquals(AutomationActionKind.MAP_BATTLE, generic.actionKind)
        assertEquals(
            AutomationIsolationScope(
                AutomationIsolationScopeKind.BATTLE_COOLDOWN_SCOPE,
                StoredActionConvergenceSelectionFactory.SHARED_BATTLE_COOLDOWN_SCOPE,
            ),
            generic.scope,
        )
        assertEquals(AutomationActionKind.UNION_BATTLE, union.actionKind)
        assertEquals(AutomationIsolationScopeKind.UNION_ENTRY, union.scope.kind)
        assertEquals(AutomationActionKind.FISHING_OBSTRUCTION_BATTLE, fishing.actionKind)
        assertEquals(AutomationIsolationScopeKind.FISHING_ENTRY, fishing.scope.kind)
        assertEquals(AutomationActionKind.RAID_BATTLE, raid.actionKind)
        assertEquals(AutomationIsolationScope(AutomationIsolationScopeKind.RAID_ENTRY, "raid-7"), raid.scope)
    }

    @Test
    fun `같은 기준 상태는 실행 identity와 무관하게 같은 fingerprint를 쓴다`() {
        val payload = StoredTypedActionPayload.FishingTown(
            FishingAction.START,
            FishingPrimaryAction.START,
            5,
        )

        val first = factory.create(stored(payload, "execution-a"))
        val replayCandidate = factory.create(stored(payload, "execution-b"))
        val changed = factory.create(stored(payload.copy(observedRemainingCasts = 4), "execution-c"))

        assertEquals(first.baselineFingerprint, replayCandidate.baselineFingerprint)
        assertNotEquals(first.baselineFingerprint, changed.baselineFingerprint)
    }

    @Test
    fun `레이드 마을 행동을 세부 행동 종류로 보존한다`() {
        val selection = factory.create(stored(StoredTypedActionPayload.RaidTown(
            action = RaidAction.START,
            raidId = "request-raid",
            targetRaidId = "raid-9",
        )))

        assertEquals(AutomationActionKind.RAID_START, selection.actionKind)
        assertEquals(AutomationIsolationScope(AutomationIsolationScopeKind.RAID_ENTRY, "raid-9"), selection.scope)
    }

    @Test
    fun `준비 전 preview도 저장된 행동과 같은 전투 범위를 계산한다`() {
        val prepared = BattleMapAutomationAction(
            accountId = 7L,
            progressDate = LocalDate.parse("2026-08-22"),
            categoryId = "dungeon",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3L,
            battleCount = 1,
            executionIdentity = "execution-preview",
            source = BattleAutomationActionSource.UNION_AUTOMATION,
            resolvedParty = ResolvedAutomationParty(
                listOf("character-1"),
                listOf(BattlePatternLoadRequest("character-1", 0)),
            ),
        )

        val preview = factory.preview(12L, prepared)

        assertEquals(AutomationActionKind.UNION_BATTLE, preview.actionKind)
        assertEquals(AutomationIsolationScope(AutomationIsolationScopeKind.UNION_ENTRY, "12"), preview.scope)
    }

    private fun stored(
        payload: StoredTypedActionPayload,
        executionIdentity: String = "execution-1",
    ) = StoredTypedAutomationAction(12L, executionIdentity, payload)

    private fun battle(
        source: BattleAutomationActionSource,
        sourceTargetKey: String? = null,
    ) = StoredTypedActionPayload.BattleMap(
        progressDate = LocalDate.parse("2026-08-22"),
        categoryId = "dungeon",
        mapCode = "map-1",
        presetMode = PresetSelectionMode.PRIMARY,
        presetId = 3L,
        battleCount = 1,
        battleRequest = RunBattleRequest(
            categoryId = "dungeon",
            mapCode = "map-1",
            characterIds = listOf("character-1"),
            patternLoads = listOf(BattlePatternLoadRequest("character-1", 0)),
            battleCount = 1,
        ),
        source = source,
        sourceTargetKey = sourceTargetKey,
    )
}
