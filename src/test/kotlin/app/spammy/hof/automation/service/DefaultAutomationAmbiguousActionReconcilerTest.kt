package app.spammy.hof.automation.service

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
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.town.raid.model.RaidAction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertIs
import org.mockito.Mockito

class DefaultAutomationAmbiguousActionReconcilerTest {
    private val now = Instant.parse("2026-07-25T00:00:00Z")
    private val battleHandler = Mockito.mock(BattleMapAutomationHandler::class.java)
    private val battleOutcomeReconciler = Mockito.mock(BattleOutcomeReconciler::class.java)
    private val workLifecycle = Mockito.mock(AutomationWorkLifecycle::class.java)
    private val defaultRaidModule = Mockito.mock(RaidCycleModule::class.java)
    private val defaultRaidAdapter = Mockito.mock(HofRaidObservationAdapter::class.java)
    private val reconciler = DefaultAutomationAmbiguousActionReconciler(
        battleHandler,
        battleOutcomeReconciler,
        TimeProvider { now },
        workLifecycle,
        defaultRaidModule,
        defaultRaidAdapter,
    )

    @Test
    fun `ambiguous raid action reuses the module result rule and never guesses a transition`() {
        val raidModule = Mockito.mock(RaidCycleModule::class.java)
        val adapter = Mockito.mock(HofRaidObservationAdapter::class.java)
        val localWorkLifecycle = Mockito.mock(AutomationWorkLifecycle::class.java)
        val raidReconciler = DefaultAutomationAmbiguousActionReconciler(
            battleHandler,
            battleOutcomeReconciler,
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
            battleHandler,
            battleOutcomeReconciler,
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

    private fun battleRequest(categoryId: String, mapCode: String, count: Int = 1) = RunBattleRequest(
        categoryId,
        mapCode,
        listOf("character-1"),
        listOf(BattlePatternLoadRequest("character-1", 1)),
        count,
    )
}
