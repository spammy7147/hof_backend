package app.spammy.hof.automation.service

import app.spammy.hof.town.home.dto.HomeQuestResponse
import app.spammy.hof.town.home.model.HomeQuestState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HomeQuestAutomationHandlerTest {
    private val handler = HomeQuestAutomationHandler()

    @Test
    fun `accepts the first configured available home quest`() {
        val result = handler.evaluate(snapshot(
            quest("second", HomeQuestState.AVAILABLE, "accept-2"),
            quest("first", HomeQuestState.AVAILABLE, "accept-1"),
        ))

        val action = assertIs<HomeQuestAutomationAction>(assertIs<HandlerEvaluation.Runnable>(result).action)
        assertEquals("first", action.questId)
        assertEquals(HomeQuestAutomationActionType.ACCEPT, action.action)
    }

    @Test
    fun `claims after no configured quest is available`() {
        val result = handler.evaluate(snapshot(quest("first", HomeQuestState.CLAIMABLE, "claim-1")))

        val action = assertIs<HomeQuestAutomationAction>(assertIs<HandlerEvaluation.Runnable>(result).action)
        assertEquals(HomeQuestAutomationActionType.CLAIM, action.action)
    }

    @Test
    fun `skips active waiting and completed quests`() {
        val result = handler.evaluate(snapshot(
            quest("first", HomeQuestState.ACTIVE, null),
            quest("second", HomeQuestState.WAITING, null),
        ))
        assertIs<HandlerEvaluation.Skipped>(result)
    }

    private fun snapshot(vararg quests: HomeQuestResponse) = HomeQuestAutomationSnapshot(
        1,
        quests.toList(),
        listOf(
            HomeQuestAutomationSelection("first", "첫 번째", true, 0),
            HomeQuestAutomationSelection("second", "두 번째", true, 1),
        ),
    )

    private fun quest(id: String, state: HomeQuestState, actionId: String?) =
        HomeQuestResponse(id, id, state, null, null, emptyList(), actionId)
}
