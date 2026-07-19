package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.service.BattleRunService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.quest.service.QuestGatewayService
import java.io.IOException
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
    private val executor = DefaultAutomationActionExecutor(
        questGateway,
        battleRun,
        questHandler,
        battleHandler,
        reconciler,
        HofSessionRecoveryExecutor(HofSessionRecoveryService(accountService)),
    )

    @Test
    fun `quest accept posts once and records its exact execution identity`() {
        val action = StoredTypedAutomationActionV1(
            entryId = 11L,
            executionIdentity = "quest-accept-1",
            payload = StoredTypedActionPayload.QuestAccept(
                "Q-1", "accept-no", StoredActionDisplay(questName = "표시용 이름", mapName = "잘못된 실행 맵"),
            ),
        )

        executor.execute(7L, action)

        Mockito.verify(questGateway, Mockito.times(1)).accept(7L, "accept-no")
        Mockito.verify(questHandler).onAcceptSucceeded(
            7L,
            "quest-accept-1",
            QuestAction.Accept("Q-1", "accept-no"),
        )
    }

    @Test
    fun `expired session reauthenticates then replays the exact action once`() {
        val action = StoredTypedAutomationActionV1(
            entryId = 11L,
            executionIdentity = "quest-accept-recovery",
            payload = StoredTypedActionPayload.QuestAccept("Q-1", "accept-no"),
        )
        Mockito.`when`(questGateway.accept(7L, "accept-no"))
            .thenThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))
            .thenReturn(emptyList())

        executor.execute(7L, action)

        Mockito.verify(accountService).reauthenticate(7L)
        Mockito.verify(questGateway, Mockito.times(2)).accept(7L, "accept-no")
        Mockito.verify(questHandler, Mockito.times(1)).onAcceptSucceeded(
            7L,
            "quest-accept-recovery",
            QuestAction.Accept("Q-1", "accept-no"),
        )
    }

    @Test
    fun `invalid stored credentials surface authentication without replay`() {
        val action = StoredTypedAutomationActionV1(
            entryId = 11L,
            executionIdentity = "quest-accept-auth",
            payload = StoredTypedActionPayload.QuestAccept("Q-1", "accept-no"),
        )
        Mockito.`when`(questGateway.accept(7L, "accept-no"))
            .thenThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))
        Mockito.`when`(accountService.reauthenticate(7L))
            .thenThrow(ApiException(ErrorCode.HOF_LOGIN_FAILED, "rejected"))

        assertFailsWith<AutomationLoginRequiredException> {
            executor.execute(7L, action)
        }

        Mockito.verify(questGateway, Mockito.times(1)).accept(7L, "accept-no")
        Mockito.verifyNoInteractions(questHandler)
    }

    @Test
    fun `ambiguous quest accept transport failure is never replayed`() {
        val action = StoredTypedAutomationActionV1(
            entryId = 11L,
            executionIdentity = "quest-accept-ambiguous",
            payload = StoredTypedActionPayload.QuestAccept("Q-1", "accept-no"),
        )
        Mockito.`when`(questGateway.accept(7L, "accept-no"))
            .thenThrow(RuntimeException("transport wrapper", IOException("connection reset")))

        assertFailsWith<AmbiguousAutomationSubmissionException> {
            executor.execute(7L, action)
        }

        Mockito.verify(questGateway, Mockito.times(1)).accept(7L, "accept-no")
        Mockito.verifyNoInteractions(accountService, questHandler)
    }

    @Test
    fun `ambiguous quest claim request failure is never replayed`() {
        val action = StoredTypedAutomationActionV1(
            entryId = 11L,
            executionIdentity = "quest-claim-ambiguous",
            payload = StoredTypedActionPayload.QuestClaim("Q-1", "claim-no"),
        )
        Mockito.`when`(questGateway.claim(7L, "claim-no"))
            .thenThrow(ApiException(ErrorCode.HOF_REQUEST_FAILED, "upstream result unknown"))

        assertFailsWith<AmbiguousAutomationSubmissionException> {
            executor.execute(7L, action)
        }

        Mockito.verify(questGateway, Mockito.times(1)).claim(7L, "claim-no")
        Mockito.verifyNoInteractions(accountService, questHandler)
    }

    @Test
    fun `post success bookkeeping failure never replays the external quest action`() {
        val action = StoredTypedAutomationActionV1(
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

        Mockito.verify(questGateway, Mockito.times(1)).accept(7L, "accept-no")
        Mockito.verifyNoInteractions(accountService)
    }

    @Test
    fun `captcha battle is classified without ambiguous wrapping or replay`() {
        val request = battleRequest()
        val action = StoredTypedAutomationActionV1(
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
        Mockito.`when`(battleRun.runBattle(7L, request))
            .thenThrow(ApiException(ErrorCode.CAPTCHA_REQUIRED, "captcha"))

        val error = assertFailsWith<ApiException> {
            executor.execute(7L, action)
        }

        assertEquals(ErrorCode.CAPTCHA_REQUIRED, error.errorCode)
        Mockito.verify(battleRun, Mockito.times(1)).runBattle(7L, request)
        Mockito.verifyNoInteractions(accountService, battleHandler)
    }

    @Test
    fun `ambiguous battle submission is surfaced and never blindly retried`() {
        val request = battleRequest()
        val action = StoredTypedAutomationActionV1(
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
        Mockito.`when`(battleRun.runBattle(7L, request)).thenThrow(RuntimeException("connection reset"))

        assertFailsWith<AmbiguousAutomationSubmissionException> {
            executor.execute(7L, action)
        }

        Mockito.verify(battleRun, Mockito.times(1)).runBattle(7L, request)
        Mockito.verifyNoInteractions(accountService)
        Mockito.verifyNoInteractions(battleHandler)
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
        Mockito.`when`(result.rounds).thenReturn(listOf(round1, round2, round3))
        Mockito.`when`(battleRun.runBattle(7L, request)).thenReturn(result)
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
        val action = StoredTypedAutomationActionV1(
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

        executor.execute(7L, action)

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
