package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidDirective
import app.spammy.hof.automation.raid.RaidIntent
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.raid.RaidHoldReason
import app.spammy.hof.automation.raid.RaidWaitReason
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionView
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestSection
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.mockito.Mockito

class AutomationTargetSelectorTest {
    private val now = Instant.parse("2026-07-23T00:00:00Z")
    private val account = HofAccountEntity(7, "target-selector", "encrypted", now)
    private val questEntry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
    private val battleEntry = AutomationEntryEntity(11, account, AutomationType.BATTLE_MAP, 1, true, now, now)
    private val adventureEntry = AutomationEntryEntity(12, account, AutomationType.ADVENTURE_MAP, 2, true, now, now)
    private val raidEntry = AutomationEntryEntity(13, account, AutomationType.RAID, 0, true, now, now)
    private val typed = Mockito.mock(TypedAutomationQueryRepository::class.java)
    private val work = Mockito.mock(AutomationWorkSessionQueryRepository::class.java)
    private val loader = Mockito.mock(TypedAutomationSnapshotLoader::class.java)
    private val coordinator = Mockito.mock(AutomationCoordinator::class.java)
    private val lifecycle = Mockito.mock(AutomationWorkLifecycle::class.java)
    private val defaultRaidModule = Mockito.mock(RaidCycleModule::class.java)
    private val selector = AutomationTargetSelector(
        typed, work, loader, coordinator, lifecycle, AutomationLootSignalService(), TimeProvider { now }, defaultRaidModule,
    )

    @Test
    fun `first runnable priority stops lower entry probes`() {
        val questSnapshot = AutomationCoordinatorEntry(10, AutomationType.QUEST)
        val action = QuestAction.Accept("quest-1", "accept-1")
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, battleEntry, adventureEntry))
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(loader.loadEntry(7, 10, null)).thenReturn(questSnapshot)
        Mockito.`when`(coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(questSnapshot))))
            .thenReturn(AutomationCoordination.Runnable(10, action, emptyList()))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(10, selected.entryId)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, 11, null)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, 12, null)
    }

    @Test
    fun `unavailable battle map does not block a lower priority adventure entry`() {
        val battleSnapshot = AutomationCoordinatorEntry(11, AutomationType.BATTLE_MAP)
        val adventureSnapshot = AutomationCoordinatorEntry(12, AutomationType.ADVENTURE_MAP)
        val retryAt = now.plusSeconds(501)
        val adventureAction = AdventureMapAutomationAction(
            accountId = 7,
            categoryId = "adventure_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            settingIdentity = 30,
            executionIdentity = "adventure-1",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(battleEntry, adventureEntry))
        Mockito.`when`(loader.loadEntry(7, 11, null, null)).thenReturn(battleSnapshot)
        Mockito.`when`(coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(battleSnapshot))))
            .thenReturn(AutomationCoordination.Unavailable(retryAt, emptyList()))
        Mockito.`when`(loader.loadEntry(7, 12, null, null)).thenReturn(adventureSnapshot)
        Mockito.`when`(coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(adventureSnapshot))))
            .thenReturn(AutomationCoordination.Runnable(12, adventureAction, emptyList()))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(12, selected.entryId)
    }

    @Test
    fun `raid cooldown parks its cycle and releases the next automation entry`() {
        val runningRaid = session(30, raidEntry, AutomationWorkType.RAID, "RaidGoblin", AutomationWorkStatus.RUNNING)
        val retryAt = now.plusSeconds(120)
        val raidModule = Mockito.mock(RaidCycleModule::class.java)
        val moduleSelector = AutomationTargetSelector(
            typed,
            work,
            loader,
            coordinator,
            lifecycle,
            AutomationLootSignalService(),
            TimeProvider { now },
            raidModule,
        )
        val battleSnapshot = AutomationCoordinatorEntry(11, AutomationType.BATTLE_MAP)
        val battleAction = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "battle-after-raid-wait",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(runningRaid)
        Mockito.`when`(raidModule.decideNext(7)).thenReturn(
            RaidDirective.WaitUntil(
                at = retryAt,
                reason = RaidWaitReason.BATTLE_COOLDOWN,
                message = "레이드 전투 쿨다운",
                entryId = raidEntry.id,
                raidId = "RaidGoblin",
            ),
        )
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry, battleEntry))
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(
            session(30, raidEntry, AutomationWorkType.RAID, "RaidGoblin", AutomationWorkStatus.WAITING_COOLDOWN, nextCheckAt = retryAt),
        ))
        Mockito.`when`(loader.loadEntry(7, 11, null, null)).thenReturn(battleSnapshot)
        Mockito.`when`(coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(battleSnapshot))))
            .thenReturn(AutomationCoordination.Runnable(11, battleAction, emptyList()))

        val selected = assertIs<AutomationCoordination.Runnable>(moduleSelector.select(7))

        assertEquals(11, selected.entryId)
        assertEquals(1, selected.trace.size)
        assertEquals(AutomationType.RAID, selected.trace.single().type)
        assertEquals(AutomationDecisionOutcome.WAITING, selected.trace.single().outcome)
        assertEquals(RaidWaitReason.BATTLE_COOLDOWN.name, selected.trace.single().reasonCode)
        Mockito.verify(lifecycle).waitForCooldown(7, 30, retryAt)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, 13, "RaidGoblin")
    }

    @Test
    fun `raid wait before its first action is persisted and releases the next automation entry`() {
        val retryAt = now.plusSeconds(120)
        val battleSnapshot = AutomationCoordinatorEntry(11, AutomationType.BATTLE_MAP)
        val battleAction = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "battle-after-new-raid-wait",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry, battleEntry))
        Mockito.`when`(defaultRaidModule.decideNext(7)).thenReturn(
            RaidDirective.WaitUntil(
                at = retryAt,
                reason = RaidWaitReason.REGISTRATION_COOLDOWN,
                message = "레이드 등록 쿨타임",
                entryId = raidEntry.id,
                raidId = "RaidGoblin",
            ),
        )
        Mockito.`when`(loader.loadEntry(7, 11, null, null)).thenReturn(battleSnapshot)
        Mockito.`when`(coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(battleSnapshot))))
            .thenReturn(AutomationCoordination.Runnable(11, battleAction, emptyList()))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(11, selected.entryId)
        Mockito.verify(lifecycle).waitForRaid(7, raidEntry.id, "RaidGoblin", retryAt)
    }

    @Test
    fun `invalid raid preset is parked until configuration changes and releases other automation`() {
        val battleSnapshot = AutomationCoordinatorEntry(11, AutomationType.BATTLE_MAP)
        val battleAction = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "battle-after-raid-preset-hold",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry, battleEntry))
        Mockito.`when`(defaultRaidModule.decideNext(7)).thenReturn(
            RaidDirective.Hold(
                reason = RaidHoldReason.INVALID_PRESET,
                message = "레이드 전투 프리셋 구성을 확인해 주세요.",
                entryId = raidEntry.id,
                raidId = "RaidGoblin",
            ),
        )
        Mockito.`when`(loader.loadEntry(7, 11, null, null)).thenReturn(battleSnapshot)
        Mockito.`when`(coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(battleSnapshot))))
            .thenReturn(AutomationCoordination.Runnable(11, battleAction, emptyList()))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(11, selected.entryId)
        Mockito.verify(lifecycle).waitForRaid(
            7,
            raidEntry.id,
            "RaidGoblin",
            null,
            "레이드 전투 프리셋 구성을 확인해 주세요.",
        )
    }

    @Test
    fun `parked raid hold warning survives lower priority actions without an early raid recheck`() {
        val holdMessage = "사용자가 진행 중인 레이드가 끝날 때까지 레이드 자동화를 보류합니다."
        val battleSnapshot = AutomationCoordinatorEntry(11, AutomationType.BATTLE_MAP)
        val battleAction = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "battle-while-manual-raid-is-active",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(
            session(
                33,
                raidEntry,
                AutomationWorkType.RAID,
                "RaidManual",
                AutomationWorkStatus.WAITING_COOLDOWN,
                nextCheckAt = now.plusSeconds(30),
                holdMessage = holdMessage,
            ),
        ))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry, battleEntry))
        Mockito.`when`(loader.loadEntry(7, battleEntry.id, null, null)).thenReturn(battleSnapshot)
        Mockito.`when`(coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(battleSnapshot))))
            .thenReturn(AutomationCoordination.Runnable(battleEntry.id, battleAction, emptyList()))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(listOf(holdMessage), selected.warnings)
        Mockito.verifyNoInteractions(defaultRaidModule)
    }

    @Test
    fun `raid completion directive is classified as a cycle history outcome`() {
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry))
        Mockito.`when`(defaultRaidModule.decideNext(7)).thenReturn(
            RaidDirective.Complete(
                RaidCycleOutcome(raidEntry.id, "RaidGoblin", RaidCycleOutcomeKind.ABORTED_CLOSED),
            ),
        )

        val decision = assertIs<AutomationCoordination.Idle>(selector.select(7))

        assertEquals(AutomationDecisionOutcome.CYCLE_ABORTED, decision.trace.single().outcome)
        assertEquals(RaidCycleOutcomeKind.ABORTED_CLOSED.name, decision.trace.single().reasonCode)
    }

    @Test
    fun `due raid cooldown wakes the module for a fresh decision`() {
        val dueRaid = session(
            32, raidEntry, AutomationWorkType.RAID, "RaidGoblin",
            AutomationWorkStatus.WAITING_COOLDOWN, nextCheckAt = now,
        )
        val raidModule = Mockito.mock(RaidCycleModule::class.java)
        val moduleSelector = AutomationTargetSelector(
            typed,
            work,
            loader,
            coordinator,
            lifecycle,
            AutomationLootSignalService(),
            TimeProvider { now },
            raidModule,
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(dueRaid))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(raidEntry))
        Mockito.`when`(raidModule.decideNext(7)).thenReturn(
            RaidDirective.Execute(
                RaidIntent.Town(13, "RaidGoblin", "고블린", RaidIntentKind.REFRESH, requestRaidId = null),
            ),
        )

        val selected = assertIs<AutomationCoordination.Runnable>(moduleSelector.select(7))

        assertEquals(13, selected.entryId)
        assertEquals(RaidIntentKind.REFRESH.name, selected.trace.single().actionKind)
        assertEquals("RaidGoblin", selected.trace.single().targetKey)
        Mockito.verify(lifecycle).resumeForCheck(7, 32)
        Mockito.verify(raidModule).decideNext(7)
        Mockito.verify(loader, Mockito.never()).loadEntry(7, 13, "RaidGoblin")
    }

    @Test
    fun `running quest session stays sticky to its target`() {
        val session = session(21, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.RUNNING)
        val questSnapshot = AutomationCoordinatorEntry(10, AutomationType.QUEST)
        val action = QuestAction.Battle(
            questKey = "quest-1",
            questCycle = "1",
            missionKey = "mission",
            missionType = app.spammy.hof.quest.model.QuestMissionType.MONSTER_KILL,
            categoryId = "battle_map",
            mapCode = "map",
            mapName = "Map",
            preset = QuestPresetSelection(app.spammy.hof.automation.entity.PresetSelectionMode.PRIMARY),
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(session)
        Mockito.`when`(loader.loadEntry(7, 10, "quest-1")).thenReturn(questSnapshot)
        Mockito.`when`(coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(questSnapshot))))
            .thenReturn(AutomationCoordination.Runnable(10, action, emptyList()))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals("quest-1", (selected.action as QuestAction.Battle).questKey)
        Mockito.verify(typed, Mockito.never()).findEntries(7)
        Mockito.verify(loader).loadEntry(7, 10, "quest-1")
    }

    @Test
    fun `incomplete material quest parks resource wait and releases lower priorities`() {
        val session = session(21, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.RUNNING)
        val quest = QuestSnapshot(
            questKey = "quest-1",
            name = "재료 수집",
            state = QuestState.ACTIVE,
            section = QuestSection.ACTIVE,
            sourceOrder = 0,
            missions = listOf(
                QuestMission(
                    key = "material",
                    type = QuestMissionType.ITEM_TURN_IN,
                    target = "  Steel   Ingot ",
                    progress = QuestProgress(current = 3, required = 5),
                    completable = false,
                ),
            ),
            actionNo = null,
        )
        val questContext = QuestAutomationSnapshot(
            accountId = 7,
            quests = listOf(quest),
            selections = emptyList(),
            mapStates = emptyList(),
            currentCycles = emptyMap(),
            counters = emptyMap(),
            mapIdentityCandidates = emptyList(),
            now = now,
        )
        val entrySnapshot = AutomationCoordinatorEntry(10, AutomationType.QUEST, quest = questContext)
        Mockito.`when`(work.findRunning(7)).thenReturn(session)
        Mockito.`when`(loader.loadEntry(7, 10, "quest-1")).thenReturn(entrySnapshot)
        Mockito.`when`(coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(entrySnapshot))))
            .thenReturn(AutomationCoordination.Idle(emptyList()))
        Mockito.`when`(typed.findEntries(7)).thenReturn(emptyList())

        assertIs<AutomationCoordination.Idle>(selector.select(7))

        Mockito.verify(lifecycle).waitForResource(7, 21, "steel ingot", 2)
    }

    @Test
    fun `map clear below optimistic target reuses session progress instead of refreshing quests`() {
        val session = session(
            id = 25,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.RUNNING,
            missionKey = "clear-map",
            missionType = QuestMissionType.MAP_CLEAR.name,
            observedCurrent = 3,
            observedRequired = 5,
        )
        val entrySnapshot = AutomationCoordinatorEntry(10, AutomationType.QUEST)
        val optimisticQuest = QuestSnapshot(
            questKey = "quest-1",
            name = "quest-1",
            state = QuestState.ACTIVE,
            section = QuestSection.ACTIVE,
            sourceOrder = 0,
            missions = listOf(
                QuestMission(
                    key = "clear-map",
                    type = QuestMissionType.MAP_CLEAR,
                    target = null,
                    progress = QuestProgress(3, 5),
                    completable = false,
                ),
            ),
            actionNo = null,
        )
        val action = QuestAction.Battle(
            questKey = "quest-1",
            questCycle = "1",
            missionKey = "clear-map",
            missionType = QuestMissionType.MAP_CLEAR,
            categoryId = "battle_map",
            mapCode = "map",
            mapName = "Map",
            preset = QuestPresetSelection(app.spammy.hof.automation.entity.PresetSelectionMode.PRIMARY),
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(session)
        Mockito.`when`(loader.loadEntry(7, 10, "quest-1", listOf(optimisticQuest))).thenReturn(entrySnapshot)
        Mockito.`when`(coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(entrySnapshot))))
            .thenReturn(AutomationCoordination.Runnable(10, action, emptyList()))

        assertIs<AutomationCoordination.Runnable>(selector.select(7))

        Mockito.verify(loader).loadEntry(7, 10, "quest-1", listOf(optimisticQuest))
    }

    @Test
    fun `authoritative map clear mismatch replaces optimistic progress before continuing`() {
        val session = session(
            id = 26,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.RUNNING,
            missionKey = "clear-map",
            missionType = QuestMissionType.MAP_CLEAR.name,
            observedCurrent = 5,
            observedRequired = 5,
        )
        val entrySnapshot = AutomationCoordinatorEntry(10, AutomationType.QUEST)
        val action = QuestAction.Battle(
            questKey = "quest-1",
            questCycle = "1",
            missionKey = "clear-map",
            missionType = QuestMissionType.MAP_CLEAR,
            categoryId = "battle_map",
            mapCode = "map",
            mapName = "Map",
            preset = QuestPresetSelection(app.spammy.hof.automation.entity.PresetSelectionMode.PRIMARY),
            missionCurrent = 4,
            missionRequired = 5,
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(session)
        Mockito.`when`(loader.loadEntry(7, 10, "quest-1", null)).thenReturn(entrySnapshot)
        Mockito.`when`(coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(entrySnapshot))))
            .thenReturn(AutomationCoordination.Runnable(10, action, emptyList()))

        assertIs<AutomationCoordination.Runnable>(selector.select(7))

        Mockito.verify(lifecycle).reconcileQuestProgress(7, 26, 4, 5)
    }

    @Test
    fun `parked quest target does not block another runnable quest in the same entry`() {
        val waiting = session(
            id = 27,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.WAITING_RESOURCE,
            nextCheckAt = now.plusSeconds(1800),
        )
        val questSnapshot = AutomationCoordinatorEntry(
            10,
            AutomationType.QUEST,
            quest = QuestAutomationSnapshot(
                accountId = 7,
                quests = emptyList(),
                selections = listOf(selection("quest-1"), selection("quest-2")),
                mapStates = emptyList(),
                currentCycles = emptyMap(),
                counters = emptyMap(),
                mapIdentityCandidates = emptyList(),
                now = now,
            ),
        )
        val runnableSnapshot = questSnapshot.copy(
            quest = requireNotNull(questSnapshot.quest).copy(selections = listOf(selection("quest-2"))),
        )
        val questAction = QuestAction.Accept("quest-2", "accept-2")
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(waiting))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry))
        Mockito.`when`(loader.loadEntry(7, 10, null, null)).thenReturn(questSnapshot)
        Mockito.`when`(coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(runnableSnapshot))))
            .thenReturn(AutomationCoordination.Runnable(10, questAction, emptyList()))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals("quest-2", assertIs<QuestAction.Accept>(selected.action).questKey)
        Mockito.verify(loader).loadEntry(7, 10, null, null)
        Mockito.verify(coordinator).coordinate(AutomationCoordinatorSnapshot(listOf(runnableSnapshot)))
    }

    @Test
    fun `parked quest target releases a lower priority entry when no other quest is runnable`() {
        val waiting = session(
            id = 29,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.WAITING_COOLDOWN,
            nextCheckAt = now.plusSeconds(1800),
        )
        val questSnapshot = AutomationCoordinatorEntry(
            10,
            AutomationType.QUEST,
            quest = QuestAutomationSnapshot(
                accountId = 7,
                quests = emptyList(),
                selections = listOf(selection("quest-1")),
                mapStates = emptyList(),
                currentCycles = emptyMap(),
                counters = emptyMap(),
                mapIdentityCandidates = emptyList(),
                now = now,
            ),
        )
        val idleQuestSnapshot = questSnapshot.copy(
            quest = requireNotNull(questSnapshot.quest).copy(selections = emptyList()),
        )
        val battleSnapshot = AutomationCoordinatorEntry(11, AutomationType.BATTLE_MAP)
        val battleAction = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = app.spammy.hof.automation.entity.PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "battle-1",
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(null)
        Mockito.`when`(work.findWaiting(7)).thenReturn(listOf(waiting))
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, battleEntry))
        Mockito.`when`(loader.loadEntry(7, 10, null, null)).thenReturn(questSnapshot)
        Mockito.`when`(coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(idleQuestSnapshot))))
            .thenReturn(AutomationCoordination.Idle(emptyList()))
        Mockito.`when`(loader.loadEntry(7, 11, null, null)).thenReturn(battleSnapshot)
        Mockito.`when`(coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(battleSnapshot))))
            .thenReturn(AutomationCoordination.Runnable(11, battleAction, emptyList()))

        val selected = assertIs<AutomationCoordination.Runnable>(selector.select(7))

        assertEquals(11, selected.entryId)
        Mockito.verify(lifecycle, Mockito.never()).resumeForCheck(7, 29)
        Mockito.verify(coordinator).coordinate(AutomationCoordinatorSnapshot(listOf(idleQuestSnapshot)))
        Mockito.verify(loader).loadEntry(7, 11, null, null)
    }

    @Test
    fun `repeat quest waiting after completion is parked instead of completed`() {
        val session = session(28, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.RUNNING)
        val entrySnapshot = AutomationCoordinatorEntry(
            id = 10,
            type = AutomationType.QUEST,
            quest = QuestAutomationSnapshot(
                accountId = 7,
                quests = listOf(
                    QuestSnapshot(
                        questKey = "quest-1",
                        name = "quest-1",
                        state = QuestState.UNAVAILABLE,
                        section = QuestSection.WAITING,
                        sourceOrder = 0,
                        missions = emptyList(),
                        actionNo = null,
                    ),
                ),
                selections = emptyList(),
                mapStates = emptyList(),
                currentCycles = emptyMap(),
                counters = emptyMap(),
                mapIdentityCandidates = emptyList(),
                now = now,
            ),
        )
        Mockito.`when`(work.findRunning(7)).thenReturn(session)
        Mockito.`when`(loader.loadEntry(7, 10, "quest-1", null)).thenReturn(entrySnapshot)
        Mockito.`when`(coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(entrySnapshot))))
            .thenReturn(AutomationCoordination.Idle(emptyList()))
        Mockito.`when`(work.findWaiting(7)).thenReturn(emptyList())
        Mockito.`when`(typed.findEntries(7)).thenReturn(emptyList())

        assertIs<AutomationCoordination.Idle>(selector.select(7))

        Mockito.verify(lifecycle).waitForUnknownCooldown(7, 28)
        Mockito.verify(lifecycle, Mockito.never()).complete(7, 28)
    }

    private fun session(
        id: Long,
        entry: AutomationEntryEntity,
        workType: AutomationWorkType,
        targetKey: String,
        status: AutomationWorkStatus,
        missionKey: String? = null,
        missionType: String? = null,
        observedCurrent: Int? = null,
        observedRequired: Int? = null,
        materialName: String? = null,
        nextCheckAt: Instant? = null,
        holdMessage: String? = null,
    ) = AutomationWorkSessionView(
        id = id,
        accountId = account.id,
        entryId = entry.id,
        entryPriority = entry.priority,
        workType = workType,
        targetKey = targetKey,
        status = status,
        missionKey = missionKey,
        missionType = missionType,
        observedCurrent = observedCurrent,
        observedRequired = observedRequired,
        materialName = materialName,
        nextCheckAt = nextCheckAt,
        holdMessage = holdMessage,
    )

    private fun selection(questKey: String) = QuestAutomationSelection(
        questKey = questKey,
        enabled = true,
        maps = emptyList(),
    )

}
