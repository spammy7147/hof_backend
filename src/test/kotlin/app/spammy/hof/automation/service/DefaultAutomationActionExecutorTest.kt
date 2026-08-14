package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.service.BattleRunService
import app.spammy.hof.battle.service.SharedBattleCooldownRejectedException
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.town.raid.dto.RaidPubActionRequest
import app.spammy.hof.town.raid.dto.RaidPubRaidResponse
import app.spammy.hof.town.raid.dto.RaidPubResponse
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.model.RaidStatus
import app.spammy.hof.town.raid.service.RaidPubService
import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class DefaultAutomationActionExecutorTest {
    private val accountService = Mockito.mock(HofAccountService::class.java)
    private val questGateway = Mockito.mock(QuestGatewayService::class.java)
    private val battleRun = Mockito.mock(BattleRunService::class.java)
    private val questHandler = Mockito.mock(QuestAutomationHandler::class.java)
    private val battleHandler = Mockito.mock(BattleMapAutomationHandler::class.java)
    private val reconciler = Mockito.mock(BattleOutcomeReconciler::class.java)
    private val executionSignals = Mockito.mock(AutomationExecutionSignals::class.java)
    private val workLifecycle = Mockito.mock(AutomationWorkLifecycle::class.java)
    private val executor = DefaultAutomationActionExecutor(
        questGateway,
        battleRun,
        questHandler,
        battleHandler,
        reconciler,
        HofSessionRecoveryExecutor(HofSessionRecoveryService(accountService)),
        executionSignals,
        workLifecycle,
    )

    @Test
    fun `quest accept posts once and records its exact execution identity`() {
        val action = StoredTypedAutomationAction(
            entryId = 11L,
            executionIdentity = "quest-accept-1",
            payload = StoredTypedActionPayload.QuestAccept(
                "Q-1", "accept-no", StoredActionDisplay(questName = "표시용 이름", mapName = "잘못된 실행 맵"),
            ),
        )

        val execution = executor.execute(7L, action)

        assertEquals(TypedAutomationExecution.Completed, execution)
        Mockito.verify(questGateway, Mockito.times(1)).accept(7L, "accept-no", HofRequestOrigin.AUTOMATION)
        Mockito.verify(questHandler).onAcceptSucceeded(
            7L,
            "quest-accept-1",
            QuestAction.Accept("Q-1", "accept-no"),
        )
    }

    @Test
    fun `raid reset closes both the persisted cycle and its work session`() {
        val raidPub = Mockito.mock(RaidPubService::class.java)
        val progress = Mockito.mock(AutomationContentProgressService::class.java)
        val raidExecutor = DefaultAutomationActionExecutor(
            questGateway,
            battleRun,
            questHandler,
            battleHandler,
            reconciler,
            HofSessionRecoveryExecutor(HofSessionRecoveryService(accountService)),
            executionSignals,
            workLifecycle,
            raidPubService = raidPub,
            contentProgress = progress,
        )
        Mockito.`when`(raidPub.action(7L, RaidPubActionRequest(RaidAction.RESET, "RaidGoblin")))
            .thenReturn(RaidPubResponse(
                emptyList(), false, false, null, null, emptySet(),
                TownActionResultResponse("SUCCESS", listOf("전투가 신청 가능 상태로 바뀌었습니다."), emptyList()),
            ))
        val action = StoredTypedAutomationAction(
            entryId = 13L,
            executionIdentity = "raid-reset-1",
            payload = StoredTypedActionPayload.RaidTown(RaidAction.RESET, "RaidGoblin"),
        )

        assertEquals(TypedAutomationExecution.Completed, raidExecutor.execute(7L, action))

        Mockito.verify(progress).raidReset(7L, "RaidGoblin")
        Mockito.verify(workLifecycle).completeRaidCycle(7L, 13L)
    }

    @Test
    fun `raid reset without a success message or registerable target remains ambiguous`() {
        val raidPub = Mockito.mock(RaidPubService::class.java)
        val progress = Mockito.mock(AutomationContentProgressService::class.java)
        val raidExecutor = DefaultAutomationActionExecutor(
            questGateway,
            battleRun,
            questHandler,
            battleHandler,
            reconciler,
            HofSessionRecoveryExecutor(HofSessionRecoveryService(accountService)),
            executionSignals,
            workLifecycle,
            raidPubService = raidPub,
            contentProgress = progress,
        )
        Mockito.`when`(raidPub.action(7L, RaidPubActionRequest(RaidAction.RESET, "RaidGoblin")))
            .thenReturn(RaidPubResponse(emptyList(), false, false, null, null, emptySet(), null))
        val action = StoredTypedAutomationAction(
            entryId = 13L,
            executionIdentity = "raid-reset-unproven",
            payload = StoredTypedActionPayload.RaidTown(RaidAction.RESET, "RaidGoblin"),
        )

        assertFailsWith<AmbiguousAutomationSubmissionException> {
            raidExecutor.execute(7L, action)
        }

        Mockito.verifyNoInteractions(progress)
        Mockito.verify(workLifecycle, Mockito.never()).completeRaidCycle(Mockito.anyLong(), Mockito.anyLong())
    }

    @Test
    fun `fixed register button does not prove raid reset succeeded while reset is still required`() {
        val raidPub = Mockito.mock(RaidPubService::class.java)
        val progress = Mockito.mock(AutomationContentProgressService::class.java)
        val raidExecutor = DefaultAutomationActionExecutor(
            questGateway,
            battleRun,
            questHandler,
            battleHandler,
            reconciler,
            HofSessionRecoveryExecutor(HofSessionRecoveryService(accountService)),
            executionSignals,
            workLifecycle,
            raidPubService = raidPub,
            contentProgress = progress,
        )
        val unchanged = RaidPubRaidResponse(
            "RaidGoblin", "고블린 전투 마차", true, null, null, null,
            RaidStatus.COMPLETED, "보상 확인 종료(리셋 가능)", null,
            emptyList(), false, setOf(RaidAction.REGISTER, RaidAction.RESET), null,
        )
        Mockito.`when`(raidPub.action(7L, RaidPubActionRequest(RaidAction.RESET, "RaidGoblin")))
            .thenReturn(RaidPubResponse(listOf(unchanged), false, false, null, null, emptySet(), null))
        val action = StoredTypedAutomationAction(
            entryId = 13L,
            executionIdentity = "raid-reset-fixed-buttons",
            payload = StoredTypedActionPayload.RaidTown(RaidAction.RESET, "RaidGoblin"),
        )

        assertFailsWith<AmbiguousAutomationSubmissionException> {
            raidExecutor.execute(7L, action)
        }

        Mockito.verifyNoInteractions(progress)
        Mockito.verify(workLifecycle, Mockito.never()).completeRaidCycle(Mockito.anyLong(), Mockito.anyLong())
    }

    @Test
    fun `explicit recruitment state transition proves raid reset succeeded`() {
        val raidPub = Mockito.mock(RaidPubService::class.java)
        val progress = Mockito.mock(AutomationContentProgressService::class.java)
        val raidExecutor = DefaultAutomationActionExecutor(
            questGateway,
            battleRun,
            questHandler,
            battleHandler,
            reconciler,
            HofSessionRecoveryExecutor(HofSessionRecoveryService(accountService)),
            executionSignals,
            workLifecycle,
            raidPubService = raidPub,
            contentProgress = progress,
        )
        val registerable = RaidPubRaidResponse(
            "RaidGoblin", "고블린 전투 마차", true, null, null, null,
            RaidStatus.RECRUITING, "파티 모집 중 (신청 안됨)", null,
            emptyList(), false, setOf(RaidAction.REGISTER, RaidAction.RESET), null,
        )
        Mockito.`when`(raidPub.action(7L, RaidPubActionRequest(RaidAction.RESET, "RaidGoblin")))
            .thenReturn(RaidPubResponse(listOf(registerable), false, false, null, null, emptySet(), null))
        val action = StoredTypedAutomationAction(
            entryId = 13L,
            executionIdentity = "raid-reset-state-transition",
            payload = StoredTypedActionPayload.RaidTown(RaidAction.RESET, "RaidGoblin"),
        )

        assertEquals(TypedAutomationExecution.Completed, raidExecutor.execute(7L, action))

        Mockito.verify(progress).raidReset(7L, "RaidGoblin")
        Mockito.verify(workLifecycle).completeRaidCycle(7L, 13L)
    }

    @Test
    fun `raid refresh schedules the next status check without closing the cycle`() {
        val raidPub = Mockito.mock(RaidPubService::class.java)
        val progress = Mockito.mock(AutomationContentProgressService::class.java)
        val raidExecutor = DefaultAutomationActionExecutor(
            questGateway,
            battleRun,
            questHandler,
            battleHandler,
            reconciler,
            HofSessionRecoveryExecutor(HofSessionRecoveryService(accountService)),
            executionSignals,
            workLifecycle,
            raidPubService = raidPub,
            contentProgress = progress,
        )
        Mockito.`when`(raidPub.action(7L, RaidPubActionRequest(RaidAction.REFRESH, null)))
            .thenReturn(RaidPubResponse(emptyList(), false, false, null, null, emptySet(), null))
        val action = StoredTypedAutomationAction(
            entryId = 13L,
            executionIdentity = "raid-refresh-1",
            payload = StoredTypedActionPayload.RaidTown(RaidAction.REFRESH, null),
        )

        assertEquals(TypedAutomationExecution.Completed, raidExecutor.execute(7L, action))

        Mockito.verify(progress).raidStatusRefreshed(7L)
        Mockito.verify(workLifecycle, Mockito.never()).completeRaidCycle(Mockito.anyLong(), Mockito.anyLong())
    }

    @Test
    fun `closed raid abort closes both the persisted cycle and its work session`() {
        val progress = Mockito.mock(AutomationContentProgressService::class.java)
        val raidExecutor = DefaultAutomationActionExecutor(
            questGateway,
            battleRun,
            questHandler,
            battleHandler,
            reconciler,
            HofSessionRecoveryExecutor(HofSessionRecoveryService(accountService)),
            executionSignals,
            workLifecycle,
            contentProgress = progress,
        )
        val action = StoredTypedAutomationAction(
            entryId = 13L,
            executionIdentity = "raid-abort-1",
            payload = StoredTypedActionPayload.RaidCycleAbort("RaidGoblin"),
        )

        assertEquals(TypedAutomationExecution.Completed, raidExecutor.execute(7L, action))

        Mockito.verify(progress).raidClosed(7L, "RaidGoblin")
        Mockito.verify(workLifecycle).completeRaidCycle(7L, 13L)
    }

    @Test
    fun `lost raid registration closes the stale cycle and its work session`() {
        val progress = Mockito.mock(AutomationContentProgressService::class.java)
        val raidExecutor = DefaultAutomationActionExecutor(
            questGateway,
            battleRun,
            questHandler,
            battleHandler,
            reconciler,
            HofSessionRecoveryExecutor(HofSessionRecoveryService(accountService)),
            executionSignals,
            workLifecycle,
            contentProgress = progress,
        )
        val action = StoredTypedAutomationAction(
            entryId = 13L,
            executionIdentity = "raid-registration-lost-1",
            payload = StoredTypedActionPayload.RaidCycleAbort(
                "RaidGoblin",
                RaidCycleAbortReason.REGISTRATION_LOST,
            ),
        )

        assertEquals(TypedAutomationExecution.Completed, raidExecutor.execute(7L, action))

        Mockito.verify(progress).raidRegistrationLost(7L, "RaidGoblin")
        Mockito.verify(progress, Mockito.never()).raidClosed(Mockito.anyLong(), Mockito.anyString())
        Mockito.verify(workLifecycle).completeRaidCycle(7L, 13L)
    }

    @Test
    fun `expired session reauthenticates then replays the exact action once`() {
        val action = StoredTypedAutomationAction(
            entryId = 11L,
            executionIdentity = "quest-accept-recovery",
            payload = StoredTypedActionPayload.QuestAccept("Q-1", "accept-no"),
        )
        Mockito.`when`(questGateway.accept(7L, "accept-no", HofRequestOrigin.AUTOMATION))
            .thenThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))
            .thenReturn(emptyList())

        executor.execute(7L, action)

        Mockito.verify(accountService).reauthenticate(7L, HofRequestOrigin.AUTOMATION)
        Mockito.verify(questGateway, Mockito.times(2)).accept(7L, "accept-no", HofRequestOrigin.AUTOMATION)
        Mockito.verify(questHandler, Mockito.times(1)).onAcceptSucceeded(
            7L,
            "quest-accept-recovery",
            QuestAction.Accept("Q-1", "accept-no"),
        )
    }

    @Test
    fun `invalid stored credentials surface authentication without replay`() {
        val action = StoredTypedAutomationAction(
            entryId = 11L,
            executionIdentity = "quest-accept-auth",
            payload = StoredTypedActionPayload.QuestAccept("Q-1", "accept-no"),
        )
        Mockito.`when`(questGateway.accept(7L, "accept-no", HofRequestOrigin.AUTOMATION))
            .thenThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))
        Mockito.`when`(accountService.reauthenticate(7L, HofRequestOrigin.AUTOMATION))
            .thenThrow(ApiException(ErrorCode.HOF_LOGIN_FAILED, "rejected"))

        assertFailsWith<AutomationLoginRequiredException> {
            executor.execute(7L, action)
        }

        Mockito.verify(questGateway, Mockito.times(1)).accept(7L, "accept-no", HofRequestOrigin.AUTOMATION)
        Mockito.verifyNoInteractions(questHandler)
    }

    @Test
    fun `ambiguous quest accept transport failure is never replayed`() {
        val action = StoredTypedAutomationAction(
            entryId = 11L,
            executionIdentity = "quest-accept-ambiguous",
            payload = StoredTypedActionPayload.QuestAccept("Q-1", "accept-no"),
        )
        Mockito.`when`(questGateway.accept(7L, "accept-no", HofRequestOrigin.AUTOMATION))
            .thenThrow(RuntimeException("transport wrapper", IOException("connection reset")))

        assertFailsWith<AmbiguousAutomationSubmissionException> {
            executor.execute(7L, action)
        }

        Mockito.verify(questGateway, Mockito.times(1)).accept(7L, "accept-no", HofRequestOrigin.AUTOMATION)
        Mockito.verifyNoInteractions(accountService, questHandler)
    }

    @Test
    fun `ambiguous quest claim request failure is never replayed`() {
        val action = StoredTypedAutomationAction(
            entryId = 11L,
            executionIdentity = "quest-claim-ambiguous",
            payload = StoredTypedActionPayload.QuestClaim("Q-1", "claim-no"),
        )
        Mockito.`when`(questGateway.claim(7L, "claim-no", HofRequestOrigin.AUTOMATION))
            .thenThrow(ApiException(ErrorCode.HOF_REQUEST_FAILED, "upstream result unknown"))

        assertFailsWith<AmbiguousAutomationSubmissionException> {
            executor.execute(7L, action)
        }

        Mockito.verify(questGateway, Mockito.times(1)).claim(7L, "claim-no", HofRequestOrigin.AUTOMATION)
        Mockito.verifyNoInteractions(accountService, questHandler)
    }

    @Test
    fun `post success bookkeeping failure never replays the external quest action`() {
        val action = StoredTypedAutomationAction(
            entryId = 11L,
            executionIdentity = "quest-accept-bookkeeping",
            payload = StoredTypedActionPayload.QuestAccept("Q-1", "accept-no"),
        )
        Mockito.doThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "unexpected bookkeeping failure"))
            .`when`(questHandler).onAcceptSucceeded(
                7L,
                "quest-accept-bookkeeping",
                QuestAction.Accept("Q-1", "accept-no"),
            )

        assertFailsWith<ApiException> {
            executor.execute(7L, action)
        }

        Mockito.verify(questGateway, Mockito.times(1)).accept(7L, "accept-no", HofRequestOrigin.AUTOMATION)
        Mockito.verifyNoInteractions(accountService)
    }

    @Test
    fun `quest battle forwards every terminal round to quest progress`() {
        val request = battleRequest()
        val result = Mockito.mock(app.spammy.hof.battle.dto.BattleResultResponse::class.java)
        val round1 = Mockito.mock(app.spammy.hof.battle.dto.BattleRoundResponse::class.java)
        val round2 = Mockito.mock(app.spammy.hof.battle.dto.BattleRoundResponse::class.java)
        val round3 = Mockito.mock(app.spammy.hof.battle.dto.BattleRoundResponse::class.java)
        Mockito.`when`(round1.outcome).thenReturn("VICTORY")
        Mockito.`when`(round2.outcome).thenReturn("DEFEAT")
        Mockito.`when`(round3.outcome).thenReturn("VICTORY")
        Mockito.`when`(result.rounds).thenReturn(listOf(round1, round2, round3))
        Mockito.`when`(battleRun.runBattle(7L, request, HofRequestOrigin.AUTOMATION)).thenReturn(result)
        val action = StoredTypedAutomationAction(
            entryId = 11L,
            executionIdentity = "quest-battle-3",
            payload = StoredTypedActionPayload.QuestBattle(
                questKey = "Q-1",
                questCycle = "2",
                missionKey = "kill",
                missionType = QuestMissionType.MONSTER_KILL,
                categoryId = request.categoryId,
                mapCode = request.mapCode,
                presetMode = PresetSelectionMode.PRIMARY,
                presetId = 301L,
                battleCount = 3,
                battleRequest = request,
            ),
        )

        val execution = executor.execute(7L, action)

        assertEquals(
            TypedAutomationExecution.BattleCompleted(request.categoryId, request.mapCode),
            execution,
        )
        Mockito.verify(questHandler).onBattleCompleted(
            7L,
            "quest-battle-3",
            QuestAction.Battle(
                "Q-1",
                "2",
                "kill",
                QuestMissionType.MONSTER_KILL,
                request.categoryId,
                request.mapCode,
                request.mapCode,
                QuestPresetSelection(PresetSelectionMode.PRIMARY, 301L),
                3,
            ),
            listOf(
                BattleAutomationRoundOutcome.VICTORY,
                BattleAutomationRoundOutcome.DEFEAT,
                BattleAutomationRoundOutcome.VICTORY,
            ),
        )
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
            StoredTypedActionPayload.QuestBattle(
                "Q-1", "2", "kill", QuestMissionType.MONSTER_KILL,
                request.categoryId, request.mapCode, PresetSelectionMode.PRIMARY, 301L, 3, request,
            ),
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

        Mockito.verify(battleRun, Mockito.times(3))
            .runBattle(7L, request, HofRequestOrigin.AUTOMATION)
        Mockito.verifyNoInteractions(questHandler, battleHandler, reconciler, executionSignals)
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
