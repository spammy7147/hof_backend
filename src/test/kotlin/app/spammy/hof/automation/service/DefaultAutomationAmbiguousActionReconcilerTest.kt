package app.spammy.hof.automation.service

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.raid.HofRaidObservationAdapter
import app.spammy.hof.automation.raid.RaidAttempt
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.raid.RaidObservation
import app.spammy.hof.automation.raid.RaidRecordResult
import app.spammy.hof.automation.raid.RaidResultObservation
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.battle.dto.BattleMapResponse
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.quest.model.QuestSection
import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.town.raid.model.RaidAction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertIs
import org.mockito.Mockito

class DefaultAutomationAmbiguousActionReconcilerTest {
    private val now = Instant.parse("2026-07-25T00:00:00Z")
    private val questGateway = Mockito.mock(QuestGatewayService::class.java)
    private val battleMapService = Mockito.mock(BattleMapService::class.java)
    private val battleHandler = Mockito.mock(BattleMapAutomationHandler::class.java)
    private val battleOutcomeReconciler = Mockito.mock(BattleOutcomeReconciler::class.java)
    private val workLifecycle = Mockito.mock(AutomationWorkLifecycle::class.java)
    private val defaultRaidModule = Mockito.mock(RaidCycleModule::class.java)
    private val defaultRaidAdapter = Mockito.mock(HofRaidObservationAdapter::class.java)
    private val reconciler = DefaultAutomationAmbiguousActionReconciler(
        questGateway,
        battleMapService,
        battleHandler,
        battleOutcomeReconciler,
        HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
        TimeProvider { now },
        workLifecycle,
        defaultRaidModule,
        defaultRaidAdapter,
    )

    @Test
    fun `accepted quest state confirms ambiguous accept without replay`() {
        Mockito.`when`(questGateway.load(7, HofRequestOrigin.AUTOMATION))
            .thenReturn(listOf(quest("Q-1", QuestState.ACTIVE, null)))

        assertIs<AmbiguousActionResolution.Applied>(reconciler.reconcile(7, questAccept()))
    }

    @Test
    fun `still available quest proves ambiguous accept was not applied`() {
        Mockito.`when`(questGateway.load(7, HofRequestOrigin.AUTOMATION))
            .thenReturn(listOf(quest("Q-1", QuestState.AVAILABLE, "accept-no")))

        assertIs<AmbiguousActionResolution.Resubmit>(reconciler.reconcile(7, questAccept()))
    }

    @Test
    fun `ambiguous raid action reuses the module result rule and never guesses a transition`() {
        val raidModule = Mockito.mock(RaidCycleModule::class.java)
        val adapter = Mockito.mock(HofRaidObservationAdapter::class.java)
        val localWorkLifecycle = Mockito.mock(AutomationWorkLifecycle::class.java)
        val raidReconciler = DefaultAutomationAmbiguousActionReconciler(
            questGateway,
            battleMapService,
            battleHandler,
            battleOutcomeReconciler,
            HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
            TimeProvider { now },
            localWorkLifecycle,
            raidModule,
            adapter,
        )
        val observation = RaidObservation(emptyList(), false, false)
        Mockito.`when`(adapter.read(7L)).thenReturn(observation)
        Mockito.`when`(raidModule.recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.RESET, "RaidGoblin"),
            RaidResultObservation.Page(observation),
        )).thenReturn(RaidRecordResult.NeedsRecheck(now.plusSeconds(30), "아직 확정할 수 없습니다."))
        val action = StoredTypedAutomationAction(
            13L,
            "raid-reset-ambiguous",
            StoredTypedActionPayload.RaidTown(RaidAction.RESET, "RaidGoblin"),
        )

        assertIs<AmbiguousActionResolution.VerifyLater>(raidReconciler.reconcile(7L, action))
        Mockito.verify(raidModule).recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.RESET, "RaidGoblin"),
            RaidResultObservation.Page(observation),
        )
        Mockito.verifyNoInteractions(localWorkLifecycle)
    }

    @Test
    fun `authoritative raid observation confirms an ambiguous action through the module`() {
        val raidModule = Mockito.mock(RaidCycleModule::class.java)
        val adapter = Mockito.mock(HofRaidObservationAdapter::class.java)
        val raidReconciler = DefaultAutomationAmbiguousActionReconciler(
            questGateway,
            battleMapService,
            battleHandler,
            battleOutcomeReconciler,
            HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
            TimeProvider { now },
            Mockito.mock(AutomationWorkLifecycle::class.java),
            raidModule,
            adapter,
        )
        val observation = RaidObservation(emptyList(), false, false)
        Mockito.`when`(adapter.read(7L)).thenReturn(observation)
        Mockito.`when`(raidModule.recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.REFRESH, "RaidGoblin", null),
            RaidResultObservation.Page(observation),
        )).thenReturn(RaidRecordResult.Recorded())
        val action = StoredTypedAutomationAction(
            13L,
            "raid-refresh-ambiguous",
            StoredTypedActionPayload.RaidTown(RaidAction.REFRESH, null, "RaidGoblin"),
        )

        assertIs<AmbiguousActionResolution.Applied>(raidReconciler.reconcile(7L, action))
    }

    @Test
    fun `authoritative raid observation proving no application safely resubmits`() {
        val observation = RaidObservation(emptyList(), false, false)
        Mockito.`when`(defaultRaidAdapter.read(7L)).thenReturn(observation)
        Mockito.`when`(defaultRaidModule.recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.REGISTER, "RaidGoblin", "RaidGoblin"),
            RaidResultObservation.Page(observation),
        )).thenReturn(RaidRecordResult.NotApplied("등록되지 않았습니다."))
        val action = StoredTypedAutomationAction(
            13L,
            "raid-register-not-applied",
            StoredTypedActionPayload.RaidTown(RaidAction.REGISTER, "RaidGoblin"),
        )

        assertIs<AmbiguousActionResolution.Resubmit>(reconciler.reconcile(7L, action))
    }

    @Test
    fun `missing quest confirms ambiguous claim was applied`() {
        Mockito.`when`(questGateway.load(7, HofRequestOrigin.AUTOMATION)).thenReturn(emptyList())

        assertIs<AmbiguousActionResolution.Applied>(
            reconciler.reconcile(
                7,
                StoredTypedAutomationAction(
                    10,
                    "claim-1",
                    StoredTypedActionPayload.QuestClaim("Q-1", "claim-no"),
                ),
            ),
        )
    }

    @Test
    fun `advanced quest mission confirms ambiguous quest battle`() {
        Mockito.`when`(questGateway.load(7, HofRequestOrigin.AUTOMATION)).thenReturn(
            listOf(
                quest("Q-1", QuestState.ACTIVE, null).copy(
                    missions = listOf(
                        QuestMission(
                            key = "kill",
                            type = QuestMissionType.MONSTER_KILL,
                            target = "slime",
                            progress = QuestProgress(3, 5),
                            completable = false,
                        ),
                    ),
                ),
            ),
        )

        assertIs<AmbiguousActionResolution.Applied>(reconciler.reconcile(7, questBattle(2)))
    }

    @Test
    fun `unchanged active quest mission resubmits ambiguous quest battle`() {
        Mockito.`when`(questGateway.load(7, HofRequestOrigin.AUTOMATION)).thenReturn(
            listOf(
                quest("Q-1", QuestState.ACTIVE, null).copy(
                    missions = listOf(
                        QuestMission(
                            key = "kill",
                            type = QuestMissionType.MONSTER_KILL,
                            target = "slime",
                            progress = QuestProgress(2, 5),
                            completable = false,
                        ),
                    ),
                ),
            ),
        )

        assertIs<AmbiguousActionResolution.Resubmit>(reconciler.reconcile(7, questBattle(2)))
    }

    @Test
    fun `new cooldown confirms ambiguous adventure battle`() {
        Mockito.`when`(battleMapService.findMaps(7, "adventure_map", HofRequestOrigin.AUTOMATION))
            .thenReturn(listOf(mapResponse(cooldownSeconds = 300, attemptCount = 2)))

        assertIs<AmbiguousActionResolution.Applied>(reconciler.reconcile(7, adventureAction(attemptCount = 3)))
    }

    @Test
    fun `unchanged runnable adventure map resubmits ambiguous battle`() {
        Mockito.`when`(battleMapService.findMaps(7, "adventure_map", HofRequestOrigin.AUTOMATION))
            .thenReturn(listOf(mapResponse(cooldownSeconds = null, attemptCount = 3)))

        assertIs<AmbiguousActionResolution.Resubmit>(reconciler.reconcile(7, adventureAction(attemptCount = 3)))
    }

    @Test
    fun `adventure map becoming unavailable confirms ambiguous battle without replay`() {
        Mockito.`when`(battleMapService.findMaps(7, "adventure_map", HofRequestOrigin.AUTOMATION))
            .thenReturn(listOf(mapResponse(cooldownSeconds = null, attemptCount = null, enabled = false)))

        assertIs<AmbiguousActionResolution.Applied>(reconciler.reconcile(7, adventureAction(attemptCount = null)))
        Mockito.verify(workLifecycle).completeAdventureAction(7, 11, "adventure_map", "map-1")
    }

    @Test
    fun `ambiguous battle map is conservatively applied without remote retry`() {
        Mockito.`when`(battleHandler.confirmAmbiguousSuccess(anyBattleAction()))
            .thenReturn(BattleOutcomeResolution.Applied("battle-1", 3))

        assertIs<AmbiguousActionResolution.Applied>(reconciler.reconcile(7, battleMapAction()))

        Mockito.verify(battleHandler).confirmAmbiguousSuccess(anyBattleAction())
        Mockito.verify(workLifecycle).completeBattleMapAction(
            7,
            12,
            "battle_map",
            "map-1",
        )
        Mockito.verifyNoInteractions(battleMapService)
    }

    @Test
    fun `ambiguous raid battle without exact terminal evidence remains in reconciliation`() {
        Mockito.`when`(battleOutcomeReconciler.reloadRecentAuthoritativeEvidence(anyRaidBattleAction()))
            .thenReturn(BattleOutcomeReconciliation.Unproven("exact result is unavailable"))

        assertIs<AmbiguousActionResolution.VerifyLater>(reconciler.reconcile(7, raidBattleAction()))

        Mockito.verifyNoInteractions(battleHandler)
        Mockito.verifyNoInteractions(defaultRaidModule)
        Mockito.verify(workLifecycle, Mockito.never()).completeBattleMapAction(7, 13, "raid", "raid001")
    }

    @Test
    fun `ambiguous raid battle with exact terminal evidence records only the raid result`() {
        val evidence = BattleAuthoritativeOutcomeEvidence(
            accountId = 7,
            executionIdentity = "raid-battle-1",
            categoryId = "raid",
            mapCode = "raid001",
            battleCount = 1,
            resultIdentity = "raid-result-1",
            outcomes = listOf(BattleAutomationRoundOutcome.VICTORY),
        )
        Mockito.`when`(battleOutcomeReconciler.reloadRecentAuthoritativeEvidence(anyRaidBattleAction()))
            .thenReturn(BattleOutcomeReconciliation.Proven(evidence))
        Mockito.`when`(defaultRaidModule.recordObservedResult(
            7,
            RaidAttempt(13, RaidIntentKind.BATTLE, "RaidGoblin", null),
            RaidResultObservation.BattleCompleted,
        )).thenReturn(RaidRecordResult.Recorded())

        assertIs<AmbiguousActionResolution.Applied>(reconciler.reconcile(7, raidBattleAction()))

        Mockito.verify(defaultRaidModule).recordObservedResult(
            7,
            RaidAttempt(13, RaidIntentKind.BATTLE, "RaidGoblin", null),
            RaidResultObservation.BattleCompleted,
        )
        Mockito.verifyNoInteractions(battleHandler)
        Mockito.verify(workLifecycle, Mockito.never()).completeBattleMapAction(7, 13, "raid", "raid001")
    }

    private fun questAccept() = StoredTypedAutomationAction(
        10,
        "accept-1",
        StoredTypedActionPayload.QuestAccept("Q-1", "accept-no"),
    )

    private fun questBattle(observedCurrent: Int) = StoredTypedAutomationAction(
        10,
        "quest-battle-1",
        StoredTypedActionPayload.QuestBattle(
            questKey = "Q-1",
            questCycle = "1",
            missionKey = "kill",
            missionType = QuestMissionType.MONSTER_KILL,
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 301,
            battleCount = 1,
            battleRequest = battleRequest("battle_map", "map-1"),
            observedCurrent = observedCurrent,
            observedRequired = 5,
        ),
    )

    private fun adventureAction(attemptCount: Int?) = StoredTypedAutomationAction(
        11,
        "adventure-1",
        StoredTypedActionPayload.AdventureMap(
            categoryId = "adventure_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 301,
            battleCount = 1,
            settingIdentity = 99,
            battleRequest = battleRequest("adventure_map", "map-1"),
            observedAttemptRemaining = attemptCount,
        ),
    )

    private fun battleMapAction() = StoredTypedAutomationAction(
        12,
        "battle-1",
        StoredTypedActionPayload.BattleMap(
            progressDate = java.time.LocalDate.parse("2026-07-25"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 301,
            battleCount = 3,
            battleRequest = battleRequest("battle_map", "map-1", 3),
        ),
    )

    private fun raidBattleAction() = StoredTypedAutomationAction(
        13,
        "raid-battle-1",
        StoredTypedActionPayload.BattleMap(
            progressDate = java.time.LocalDate.parse("2026-07-25"),
            categoryId = "raid",
            mapCode = "raid001",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 301,
            battleCount = 1,
            battleRequest = battleRequest("raid", "raid001"),
            source = BattleAutomationActionSource.RAID_AUTOMATION,
            sourceTargetKey = "RaidGoblin",
        ),
    )

    private fun mapResponse(
        cooldownSeconds: Long?,
        attemptCount: Int?,
        enabled: Boolean = true,
    ) = BattleMapResponse(
        categoryId = "adventure_map",
        mapCode = "map-1",
        name = "Adventure",
        groupName = null,
        groupOrder = 0,
        mapOrder = 0,
        recommendedLevel = null,
        availableCount = null,
        attemptCount = attemptCount,
        winCount = null,
        cooldownRemainingText = cooldownSeconds?.let { "${it}s" },
        cooldownRemainingSeconds = cooldownSeconds,
        keyMode = BattleMapKeyMode.UNLIMITED,
        keyCount = null,
        requiredTime = 0,
        enabled = enabled,
        resolved = true,
        iconUrl = null,
        rawHref = "?map=map-1",
    )

    private fun anyBattleAction(): BattleMapAutomationAction =
        Mockito.any(BattleMapAutomationAction::class.java) ?: BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-25"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 301,
            battleCount = 3,
            executionIdentity = "matcher",
        )

    private fun anyRaidBattleAction(): BattleMapAutomationAction =
        Mockito.any(BattleMapAutomationAction::class.java) ?: BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-25"),
            categoryId = "raid",
            mapCode = "raid001",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 301,
            battleCount = 1,
            executionIdentity = "matcher",
            source = BattleAutomationActionSource.RAID_AUTOMATION,
            sourceTargetKey = "RaidGoblin",
        )

    private fun quest(code: String, state: QuestState, actionNo: String?) = QuestSnapshot(
        questKey = code,
        name = code,
        state = state,
        section = when (state) {
            QuestState.AVAILABLE -> QuestSection.AVAILABLE
            QuestState.COMPLETED -> QuestSection.COMPLETED
            QuestState.UNAVAILABLE -> QuestSection.WAITING
            QuestState.ACTIVE, QuestState.CLAIMABLE -> QuestSection.ACTIVE
        },
        sourceOrder = 0,
        missions = emptyList(),
        actionNo = actionNo,
    )

    private fun battleRequest(categoryId: String, mapCode: String, count: Int = 1) = RunBattleRequest(
        categoryId,
        mapCode,
        listOf("character-1"),
        listOf(BattlePatternLoadRequest("character-1", 1)),
        count,
    )
}
