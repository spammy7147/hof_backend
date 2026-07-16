package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.service.BattleRunService
import app.spammy.hof.quest.service.QuestGatewayService
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class DefaultAutomationActionExecutorTest {
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
    )

    @Test
    fun `quest accept posts once and records its exact execution identity`() {
        val action = StoredTypedAutomationActionV1(
            entryId = 11L,
            executionIdentity = "quest-accept-1",
            payload = StoredTypedActionPayload.QuestAccept("Q-1", "accept-no"),
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
