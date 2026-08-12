package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.town.fishing.dto.FishingResponse
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class FishingAutomationSnapshot(
    val accountId: Long,
    val state: FishingResponse,
    val presetMode: PresetSelectionMode,
    val presetId: Long?,
    val resolvedParty: ResolvedAutomationParty?,
    val now: Instant,
)

data class FishingTownAutomationAction(
    val accountId: Long, val action: FishingAction,
    val observedPrimaryAction: FishingPrimaryAction, val observedRemainingCasts: Int?,
) : PreparedAutomationAction

@Service
class FishingAutomationHandler : AutomationHandler<FishingAutomationSnapshot> {
    override fun evaluate(context: FishingAutomationSnapshot): HandlerEvaluation {
        if (context.state.remainingCasts == 0) {
            val next = context.now.atZone(SEOUL).toLocalDate().plusDays(1).atStartOfDay(SEOUL).toInstant()
            return HandlerEvaluation.Unavailable(next, "FISHING_DAILY_LIMIT", "오늘의 낚시 횟수를 모두 사용했습니다.")
        }
        if (context.state.blockedByBattle) {
            val target = context.state.battleTarget ?: return retry(context, "FISHING_BATTLE_TARGET_MISSING", "낚시 전투 대상을 다시 확인합니다.")
            val presetId = context.presetId ?: return HandlerEvaluation.ConfigurationWarning("낚시 전투 프리셋을 선택해 주세요.", "FISHING_PRESET_MISSING")
            val party = context.resolvedParty ?: return HandlerEvaluation.ConfigurationWarning("낚시 전투 프리셋 구성을 확인해 주세요.", "FISHING_PARTY_INVALID")
            return HandlerEvaluation.Runnable(BattleMapAutomationAction(
                context.accountId, context.now.atZone(SEOUL).toLocalDate(), target.categoryId, target.mapCode,
                context.presetMode, presetId, 1, UUID.randomUUID().toString(),
                BattleAutomationActionSource.FISHING_AUTOMATION, party, target.name,
            ))
        }
        return when (context.state.primaryAction) {
            FishingPrimaryAction.START -> HandlerEvaluation.Runnable(FishingTownAutomationAction(context.accountId, FishingAction.START, context.state.primaryAction, context.state.remainingCasts))
            FishingPrimaryAction.CATCH -> HandlerEvaluation.Runnable(FishingTownAutomationAction(context.accountId, FishingAction.CATCH, context.state.primaryAction, context.state.remainingCasts))
            FishingPrimaryAction.NONE -> retry(context, "FISHING_STATE_INCOMPLETE", "낚시 상태를 다시 확인합니다.")
        }
    }

    private fun retry(context: FishingAutomationSnapshot, code: String, message: String) =
        HandlerEvaluation.Unavailable(context.now.plusSeconds(30), code, message)

    private companion object { val SEOUL: ZoneId = ZoneId.of("Asia/Seoul") }
}
