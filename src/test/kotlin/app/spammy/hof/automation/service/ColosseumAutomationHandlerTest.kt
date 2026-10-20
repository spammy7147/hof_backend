package app.spammy.hof.automation.service

import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.pvp.parser.ColosseumParser
import app.spammy.hof.town.pvp.dto.ColosseumBattleResponse
import app.spammy.hof.town.pvp.service.ColosseumAutomationObservation
import java.time.Instant
import kotlin.test.*

class ColosseumAutomationHandlerTest {
    private val now = Instant.parse("2026-10-20T00:00:00Z")
    private val handler = ColosseumAutomationHandler()
    private fun observation(): ColosseumAutomationObservation {
        val html = requireNotNull(javaClass.getResource("/fixtures/town/pvp/rank.html")).readText()
        val url = "https://hof.zerosic.com/index.php?menu=rank"
        val parsed = ColosseumParser().parseBattle(html, url, HofFormParser().parse(html, url))
        return ColosseumAutomationObservation(ColosseumBattleResponse.from(parsed), true, null)
    }
    @Test fun `저장된 5인 팀으로 한 번 도전을 선택한다`() {
        val state = observation()
        val action = assertIs<ColosseumAutomationAction>(assertIs<HandlerEvaluation.Runnable>(handler.evaluate(ColosseumAutomationSnapshot(7, state, now))).action)
        assertEquals(state.response.selectedTeam.sorted(), action.teamIds)
    }
    @Test fun `쿨다운에는 다음 시각을 남기고 팀 누락은 설정 안내로 건너뛴다`() {
        val state = observation()
        val waiting = handler.evaluate(ColosseumAutomationSnapshot(7, state.copy(cooldownUntil = now.plusSeconds(30)), now))
        assertEquals(now.plusSeconds(30), assertIs<HandlerEvaluation.SkippedUntil>(waiting).nextRunAt)
        val missing = state.copy(response = state.response.copy(selectedTeam = emptyList()))
        assertIs<HandlerEvaluation.ConfigurationWarning>(handler.evaluate(ColosseumAutomationSnapshot(7, missing, now)))
        assertIs<HandlerEvaluation.SkippedReason>(handler.evaluate(ColosseumAutomationSnapshot(7, state.copy(complete = false), now)))
    }
}
