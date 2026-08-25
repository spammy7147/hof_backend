package app.spammy.hof.automation.service

import app.spammy.hof.town.home.dto.HomeQuestResponse
import app.spammy.hof.town.home.model.HomeQuestState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import java.time.Instant

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
    fun `claims an open home quest before accepting a new one`() {
        val result = handler.evaluate(snapshot(
            quest("first", HomeQuestState.AVAILABLE, "accept-1"),
            quest("second", HomeQuestState.CLAIMABLE, "claim-2"),
        ))

        val action = assertIs<HomeQuestAutomationAction>(assertIs<HandlerEvaluation.Runnable>(result).action)
        assertEquals("second", action.questId)
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

    @Test
    fun `unsafe Time claim stays open and does not hide a new acceptable home quest`() {
        val result = handler.evaluate(snapshot(
            quest("first", HomeQuestState.CLAIMABLE, "claim-1").copy(reward = "Time +2,000"),
            quest("second", HomeQuestState.AVAILABLE, "accept-2"),
            timeCurrent = 4_001,
        ))

        val action = assertIs<HomeQuestAutomationAction>(assertIs<HandlerEvaluation.Runnable>(result).action)
        assertEquals("second", action.questId)
        assertEquals(HomeQuestAutomationActionType.ACCEPT, action.action)
    }

    @Test
    fun `Time claim that exactly reaches max is selected before a new accept`() {
        val result = handler.evaluate(snapshot(
            quest("first", HomeQuestState.CLAIMABLE, "claim-1").copy(reward = "Time +2,000"),
            quest("second", HomeQuestState.AVAILABLE, "accept-2"),
            timeCurrent = 4_000,
        ))

        val action = assertIs<HomeQuestAutomationAction>(assertIs<HandlerEvaluation.Runnable>(result).action)
        assertEquals("first", action.questId)
        assertEquals(HomeQuestAutomationActionType.CLAIM, action.action)
    }

    @Test
    fun `claimable home quest missing an action does not hide a later runnable claim`() {
        val result = handler.evaluate(snapshot(
            quest("first", HomeQuestState.CLAIMABLE, null),
            quest("second", HomeQuestState.CLAIMABLE, "claim-2"),
        ))

        val action = assertIs<HomeQuestAutomationAction>(assertIs<HandlerEvaluation.Runnable>(result).action)
        assertEquals("second", action.questId)
        assertEquals(HomeQuestAutomationActionType.CLAIM, action.action)
    }

    @Test
    fun `available quest without action id becomes an authoritative observation gap`() {
        val result = assertIs<HandlerEvaluation.ObservationGap>(
            handler.evaluate(snapshot(quest("first", HomeQuestState.AVAILABLE, null))),
        )

        assertEquals("HOME_ACTION_ID_MISSING", result.reasonCode)
        assertEquals("first", result.scopeKey)
        assertEquals(true, result.authoritative)
    }

    @Test
    fun `running accepted home quest parks its open cycle until it becomes claimable`() {
        val result = handler.evaluate(snapshot(
            quest("first", HomeQuestState.ACTIVE, null),
            workSessionId = 41,
        ))

        val transition = assertIs<HandlerEvaluation.WorkTransition>(result)
        assertEquals(AutomationWorkTransition.WaitForUnknownCooldown, transition.transition)
    }

    @Test
    fun `running home quest completes its work cycle after claim enters waiting`() {
        val result = handler.evaluate(snapshot(
            quest("first", HomeQuestState.WAITING, null),
            workSessionId = 42,
        ))

        val transition = assertIs<HandlerEvaluation.WorkTransition>(result)
        assertEquals(AutomationWorkTransition.Complete, transition.transition)
    }

    private fun snapshot(
        vararg quests: HomeQuestResponse,
        timeCurrent: Int? = null,
        workSessionId: Long? = null,
    ) = HomeQuestAutomationSnapshot(
        1,
        quests.toList(),
        listOf(
            HomeQuestAutomationSelection("first", "첫 번째", true, 0),
            HomeQuestAutomationSelection("second", "두 번째", true, 1),
        ),
        Instant.parse("2026-08-22T00:00:00Z"),
        timeCurrent?.let {
            AutomationTimeSnapshot(it, 6_000, Instant.parse("2026-08-22T00:00:00Z"))
        },
        workSessionId,
        workSessionId?.let { 0 },
    )

    private fun quest(id: String, state: HomeQuestState, actionId: String?) =
        HomeQuestResponse(id, id, state, null, null, emptyList(), actionId)
}
