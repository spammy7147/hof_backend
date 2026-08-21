package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.raid.HofRaidObservationAdapter
import app.spammy.hof.automation.raid.RaidAttempt
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.raid.RaidObservation
import app.spammy.hof.automation.raid.RaidRecordResult
import app.spammy.hof.automation.raid.RaidResultObservation
import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.service.BattleRunService
import app.spammy.hof.battle.service.SharedBattleCooldownRejectedException
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.town.raid.dto.RaidPubActionRequest
import app.spammy.hof.town.raid.dto.RaidPubRaidResponse
import app.spammy.hof.town.raid.dto.RaidPubResponse
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.model.RaidStatus
import app.spammy.hof.town.raid.service.RaidPubService
import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class DefaultAutomationActionExecutorTest {
    private val accountService = Mockito.mock(HofAccountService::class.java)
    private val battleRun = Mockito.mock(BattleRunService::class.java)
    private val battleHandler = Mockito.mock(BattleMapAutomationHandler::class.java)
    private val reconciler = Mockito.mock(BattleOutcomeReconciler::class.java)
    private val executionSignals = Mockito.mock(AutomationExecutionSignals::class.java)
    private val workLifecycle = Mockito.mock(AutomationWorkLifecycle::class.java)
    private val defaultRaidPub = Mockito.mock(RaidPubService::class.java)
    private val defaultRaidModule = Mockito.mock(RaidCycleModule::class.java)
    private val defaultRaidAdapter = Mockito.mock(HofRaidObservationAdapter::class.java)
    private val sessionRecovery = HofSessionRecoveryExecutor(HofSessionRecoveryService(accountService))
    private val battleSubmission = AutomationBattleSubmission(battleRun, sessionRecovery, reconciler)
    private val executor = DefaultAutomationActionExecutor(
        battleSubmission,
        battleHandler,
        reconciler,
        executionSignals,
        workLifecycle,
        defaultRaidPub,
        defaultRaidModule,
        defaultRaidAdapter,
    )

    @Test
    fun `raid executor forwards the POST observation to the raid module instead of changing cycle state itself`() {
        val raidPub = Mockito.mock(RaidPubService::class.java)
        val raidModule = Mockito.mock(RaidCycleModule::class.java)
        val adapter = Mockito.mock(HofRaidObservationAdapter::class.java)
        val response = RaidPubResponse(emptyList(), false, false, null, null, emptySet(), null)
        val observation = RaidObservation(emptyList(), false, false)
        val raidExecutor = DefaultAutomationActionExecutor(
            battleSubmission,
            battleHandler,
            reconciler,
            executionSignals,
            workLifecycle,
            raidPubService = raidPub,
            raidCycleModule = raidModule,
            raidObservationAdapter = adapter,
        )
        Mockito.`when`(raidPub.action(7L, RaidPubActionRequest(RaidAction.RESET, "RaidGoblin")))
            .thenReturn(response)
        Mockito.`when`(adapter.from(response)).thenReturn(observation)
        Mockito.`when`(raidModule.recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.RESET, "RaidGoblin"),
            RaidResultObservation.Page(observation),
        )).thenReturn(RaidRecordResult.Recorded())
        val action = StoredTypedAutomationAction(
            entryId = 13L,
            executionIdentity = "raid-reset-1",
            payload = StoredTypedActionPayload.RaidTown(RaidAction.RESET, "RaidGoblin"),
        )

        assertEquals(TypedAutomationExecution.Completed, raidExecutor.execute(7L, action))

        Mockito.verify(raidModule).recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.RESET, "RaidGoblin"),
            RaidResultObservation.Page(observation),
        )
        Mockito.verify(workLifecycle, Mockito.never()).completeRaidCycle(Mockito.anyLong(), Mockito.anyLong())
    }

    @Test
    fun `raid executor keeps an unproven POST result in reconciliation`() {
        val raidPub = Mockito.mock(RaidPubService::class.java)
        val raidModule = Mockito.mock(RaidCycleModule::class.java)
        val adapter = Mockito.mock(HofRaidObservationAdapter::class.java)
        val response = RaidPubResponse(emptyList(), false, false, null, null, emptySet(), null)
        val observation = RaidObservation(emptyList(), false, false)
        val raidExecutor = DefaultAutomationActionExecutor(
            battleSubmission,
            battleHandler,
            reconciler,
            executionSignals,
            workLifecycle,
            raidPubService = raidPub,
            raidCycleModule = raidModule,
            raidObservationAdapter = adapter,
        )
        Mockito.`when`(raidPub.action(7L, RaidPubActionRequest(RaidAction.REGISTER, "RaidGoblin")))
            .thenReturn(response)
        Mockito.`when`(adapter.from(response)).thenReturn(observation)
        Mockito.`when`(raidModule.recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.REGISTER, "RaidGoblin"),
            RaidResultObservation.Page(observation),
        )).thenReturn(RaidRecordResult.NeedsRecheck(Instant.parse("2026-08-20T00:00:30Z"), "확인 필요"))
        val action = StoredTypedAutomationAction(
            entryId = 13L,
            executionIdentity = "raid-register-unproven",
            payload = StoredTypedActionPayload.RaidTown(RaidAction.REGISTER, "RaidGoblin"),
        )

        assertFailsWith<AmbiguousAutomationSubmissionException> {
            raidExecutor.execute(7L, action)
        }
    }

    @Test
    fun `raid executor closes the work session when the module records cycle completion`() {
        val raidPub = Mockito.mock(RaidPubService::class.java)
        val raidModule = Mockito.mock(RaidCycleModule::class.java)
        val adapter = Mockito.mock(HofRaidObservationAdapter::class.java)
        val response = RaidPubResponse(emptyList(), true, true, 10_000, null, emptySet(), null)
        val observation = RaidObservation(emptyList(), true, true, 10_000)
        val completion = RaidCycleOutcome(13L, "RaidGoblin", RaidCycleOutcomeKind.COMPLETED)
        val raidExecutor = DefaultAutomationActionExecutor(
            battleSubmission,
            battleHandler,
            reconciler,
            executionSignals,
            workLifecycle,
            raidPubService = raidPub,
            raidCycleModule = raidModule,
            raidObservationAdapter = adapter,
        )
        Mockito.`when`(raidPub.action(7L, RaidPubActionRequest(RaidAction.REWARD, null))).thenReturn(response)
        Mockito.`when`(adapter.from(response)).thenReturn(observation)
        Mockito.`when`(raidModule.recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.REWARD, "RaidGoblin", null),
            RaidResultObservation.Page(observation),
        )).thenReturn(RaidRecordResult.Recorded(completion))
        val action = StoredTypedAutomationAction(
            entryId = 13L,
            executionIdentity = "raid-reward-complete",
            payload = StoredTypedActionPayload.RaidTown(RaidAction.REWARD, null, "RaidGoblin"),
        )

        assertEquals(TypedAutomationExecution.RaidCycleFinished(completion), raidExecutor.execute(7L, action))

        Mockito.verify(workLifecycle).completeRaidCycle(7L, 13L)
    }

    @Test
    fun `captcha battle is classified without ambiguous wrapping or replay`() {
        val request = battleRequest()
        val action = StoredTypedAutomationAction(
            entryId = 12L,
            executionIdentity = "battle-captcha",
            payload = StoredTypedActionPayload.BattleMap(
                progressDate = java.time.LocalDate.parse("2026-07-16"),
                categoryId = request.categoryId,
                mapCode = request.mapCode,
                presetMode = PresetSelectionMode.PRIMARY,
                presetId = 301L,
                battleCount = 3,
                battleRequest = request,
            ),
        )
        Mockito.`when`(battleRun.runBattle(7L, request, HofRequestOrigin.AUTOMATION))
            .thenThrow(ApiException(ErrorCode.CAPTCHA_REQUIRED, "captcha"))

        val error = assertFailsWith<ApiException> {
            executor.execute(7L, action)
        }

        assertEquals(ErrorCode.CAPTCHA_REQUIRED, error.errorCode)
        Mockito.verify(battleRun, Mockito.times(1)).runBattle(7L, request, HofRequestOrigin.AUTOMATION)
        Mockito.verifyNoInteractions(accountService, battleHandler)
    }

    @Test
    fun `ambiguous battle submission is surfaced and never blindly retried`() {
        val request = battleRequest()
        val action = StoredTypedAutomationAction(
            entryId = 12L,
            executionIdentity = "battle-1",
            payload = StoredTypedActionPayload.BattleMap(
                progressDate = java.time.LocalDate.parse("2026-07-16"),
                categoryId = request.categoryId,
                mapCode = request.mapCode,
                presetMode = PresetSelectionMode.PRIMARY,
                presetId = 301L,
                battleCount = 3,
                battleRequest = request,
            ),
        )
        Mockito.`when`(battleRun.runBattle(7L, request, HofRequestOrigin.AUTOMATION)).thenThrow(RuntimeException("connection reset"))

        assertFailsWith<AmbiguousAutomationSubmissionException> {
            executor.execute(7L, action)
        }

        Mockito.verify(battleRun, Mockito.times(1)).runBattle(7L, request, HofRequestOrigin.AUTOMATION)
        Mockito.verifyNoInteractions(accountService)
        Mockito.verifyNoInteractions(battleHandler)
    }

    @Test
    fun `proven adventure battle completes its one battle work unit`() {
        val request = battleRequest().copy(categoryId = "sp_hunt", mapCode = "map-1", battleCount = 1)
        val result = Mockito.mock(app.spammy.hof.battle.dto.BattleResultResponse::class.java)
        val round = Mockito.mock(app.spammy.hof.battle.dto.BattleRoundResponse::class.java)
        Mockito.`when`(round.outcome).thenReturn(BattleAutomationRoundOutcome.VICTORY.name)
        Mockito.`when`(result.rounds).thenReturn(listOf(round))
        Mockito.`when`(battleRun.runBattle(7L, request, HofRequestOrigin.AUTOMATION)).thenReturn(result)
        val action = StoredTypedAutomationAction(
            entryId = 13L,
            executionIdentity = "adventure-1",
            payload = StoredTypedActionPayload.AdventureMap(
                categoryId = request.categoryId,
                mapCode = request.mapCode,
                presetMode = PresetSelectionMode.PRIMARY,
                presetId = 301L,
                battleCount = 1,
                settingIdentity = 99L,
                battleRequest = request,
            ),
        )

        val execution = executor.execute(7L, action)

        assertEquals(TypedAutomationExecution.BattleCompleted("sp_hunt", "map-1"), execution)
        Mockito.verify(workLifecycle).completeAdventureAction(7L, 13L, "sp_hunt", "map-1")
    }

    @Test
    fun `all automation battle payloads return shared cooldown without progress callbacks`() {
        val request = battleRequest()
        val retryAt = Instant.parse("2026-07-24T00:00:56Z")
        Mockito.`when`(battleRun.runBattle(7L, request, HofRequestOrigin.AUTOMATION))
            .thenThrow(SharedBattleCooldownRejectedException(retryAt))
        val payloads = listOf<StoredTypedActionPayload>(
            StoredTypedActionPayload.BattleMap(
                LocalDate.parse("2026-07-24"), request.categoryId, request.mapCode,
                PresetSelectionMode.PRIMARY, 301L, 3, request,
            ),
            StoredTypedActionPayload.AdventureMap(
                request.categoryId, request.mapCode, PresetSelectionMode.PRIMARY,
                301L, 3, 99L, request,
            ),
        )

        payloads.forEachIndexed { index, payload ->
            val result = executor.execute(
                7L,
                StoredTypedAutomationAction(11L, "cooldown-$index", payload),
            )

            assertEquals(
                TypedAutomationExecution.SharedCooldown(request.categoryId, request.mapCode, retryAt),
                result,
            )
        }

        Mockito.verify(battleRun, Mockito.times(2))
            .runBattle(7L, request, HofRequestOrigin.AUTOMATION)
        Mockito.verifyNoInteractions(battleHandler, reconciler, executionSignals)
        Mockito.verify(workLifecycle, Mockito.never()).completeBattleMapAction(
            Mockito.anyLong(),
            Mockito.anyLong(),
            Mockito.anyString(),
            Mockito.anyString(),
        )
    }

    @Test
    fun `battle map terminal rounds are handed to the required progress handler`() {
        val request = battleRequest()
        val result = Mockito.mock(app.spammy.hof.battle.dto.BattleResultResponse::class.java)
        val round1 = Mockito.mock(app.spammy.hof.battle.dto.BattleRoundResponse::class.java)
        val round2 = Mockito.mock(app.spammy.hof.battle.dto.BattleRoundResponse::class.java)
        val round3 = Mockito.mock(app.spammy.hof.battle.dto.BattleRoundResponse::class.java)
        Mockito.`when`(round1.outcome).thenReturn("VICTORY")
        Mockito.`when`(round2.outcome).thenReturn("DEFEAT")
        Mockito.`when`(round3.outcome).thenReturn("DRAW")
        Mockito.`when`(round1.loots).thenReturn(listOf(app.spammy.hof.battle.dto.BattleLootResponse("Steel Ingot x 2")))
        Mockito.`when`(round2.loots).thenReturn(emptyList())
        Mockito.`when`(round3.loots).thenReturn(emptyList())
        Mockito.`when`(round1.quest).thenReturn("퀘스트 진행 4/5")
        Mockito.`when`(result.rounds).thenReturn(listOf(round1, round2, round3))
        Mockito.`when`(battleRun.runBattle(7L, request, HofRequestOrigin.AUTOMATION)).thenReturn(result)
        Mockito.`when`(
            battleHandler.onBattleCompleted(
                anyBattleAction(),
                eqValue(BattleAutomationActionSource.BATTLE_MAP_AUTOMATION),
                eqValue("battle-1"),
                eqValue(
                    listOf(
                        BattleAutomationRoundOutcome.VICTORY,
                        BattleAutomationRoundOutcome.DEFEAT,
                        BattleAutomationRoundOutcome.DRAW,
                    ),
                ),
                eqValue(reconciler),
            ),
        ).thenReturn(BattleOutcomeResolution.Applied("battle-1", 1))
        val action = StoredTypedAutomationAction(
            entryId = 12L,
            executionIdentity = "battle-1",
            payload = StoredTypedActionPayload.BattleMap(
                java.time.LocalDate.parse("2026-07-16"),
                request.categoryId,
                request.mapCode,
                PresetSelectionMode.PRIMARY,
                301L,
                3,
                request,
            ),
        )

        val execution = executor.execute(7L, action)

        assertEquals(
            TypedAutomationExecution.BattleCompleted(request.categoryId, request.mapCode),
            execution,
        )
        Mockito.verify(battleHandler).onBattleCompleted(
            anyBattleAction(),
            eqValue(BattleAutomationActionSource.BATTLE_MAP_AUTOMATION),
            eqValue("battle-1"),
            eqValue(
                listOf(
                    BattleAutomationRoundOutcome.VICTORY,
                    BattleAutomationRoundOutcome.DEFEAT,
                    BattleAutomationRoundOutcome.DRAW,
                ),
            ),
            eqValue(reconciler),
        )
        Mockito.verify(executionSignals).afterBattle(
            7L,
            BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
            listOf(
                BattleAutomationRoundOutcome.VICTORY,
                BattleAutomationRoundOutcome.DEFEAT,
                BattleAutomationRoundOutcome.DRAW,
            ),
            listOf("Steel Ingot x 2"),
            listOf("퀘스트 진행 4/5"),
        )
        Mockito.verify(workLifecycle).completeBattleMapAction(
            7L,
            12L,
            request.categoryId,
            request.mapCode,
        )
    }

    private fun battleRequest() = RunBattleRequest(
        categoryId = "battle_map",
        mapCode = "gb0",
        characterIds = listOf("character-1"),
        patternLoads = listOf(BattlePatternLoadRequest("character-1", 1)),
        battleCount = 3,
    )

    private fun anyBattleAction(): BattleMapAutomationAction =
        Mockito.any(BattleMapAutomationAction::class.java)
            ?: BattleMapAutomationAction(
                7L,
                java.time.LocalDate.parse("2026-07-16"),
                "battle_map",
                "gb0",
                PresetSelectionMode.PRIMARY,
                301L,
                3,
                "matcher",
            )

    private fun <T> eqValue(value: T): T = Mockito.eq(value) ?: value
}
