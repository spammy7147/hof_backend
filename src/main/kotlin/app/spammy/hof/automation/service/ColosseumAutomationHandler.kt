package app.spammy.hof.automation.service

import app.spammy.hof.town.pvp.service.ColosseumAutomationObservation
import java.time.Instant

internal const val COLOSSEUM_TARGET = "RANKING"

data class ColosseumAutomationSnapshot(val accountId: Long, val observation: ColosseumAutomationObservation, val now: Instant)
data class ColosseumAutomationAction(val accountId: Long, val teamIds: List<String>) : PreparedAutomationAction

class ColosseumAutomationHandler : AutomationHandler<ColosseumAutomationSnapshot> {
    override fun evaluate(context: ColosseumAutomationSnapshot): HandlerEvaluation {
        val state = context.observation
        if (!state.complete) return HandlerEvaluation.SkippedReason("COLOSSEUM_OBSERVATION_INCOMPLETE", "콜로세움 상태를 완전히 읽지 못해 이번 판단에서 건너뜁니다.")
        if (state.response.selectedTeam.size != 5) return HandlerEvaluation.ConfigurationWarning("마을의 콜로세움 전투에서 출전할 5명을 먼저 저장해 주세요.", "COLOSSEUM_TEAM_REQUIRED")
        state.cooldownUntil?.takeIf { it > context.now }?.let {
            return HandlerEvaluation.SkippedUntil(it, "COLOSSEUM_COOLDOWN", "콜로세움 도전 쿨다운 중입니다.")
        }
        if (!state.response.challengeAvailable) return HandlerEvaluation.SkippedReason("COLOSSEUM_UNAVAILABLE", "현재 콜로세움에 도전할 수 없습니다.")
        return HandlerEvaluation.Runnable(ColosseumAutomationAction(context.accountId, state.response.selectedTeam.sorted()))
    }
}
