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
    val maps: List<FishingAutomationMapSetting>,
    val primaryPreset: FishingAutomationPreset?,
    val now: Instant,
)

data class FishingAutomationMapSetting(
    val categoryId: String, val mapCode: String, val presetMode: PresetSelectionMode,
    val presetId: Long?, val resolvedParty: ResolvedAutomationParty?,
)
data class FishingAutomationPreset(
    val presetMode: PresetSelectionMode, val presetId: Long?, val resolvedParty: ResolvedAutomationParty?,
)

data class FishingTownAutomationAction(
    val accountId: Long, val action: FishingAction,
    val observedPrimaryAction: FishingPrimaryAction, val observedRemainingCasts: Int?,
) : PreparedAutomationAction

@Service
class FishingAutomationHandler : AutomationHandler<FishingAutomationSnapshot> {
    override fun evaluate(context: FishingAutomationSnapshot): HandlerEvaluation {
        if (context.state.blockedByBattle) {
            val target = context.state.battleTarget ?: return retry(context, "FISHING_BATTLE_TARGET_MISSING", "낚시 전투 대상을 다시 확인합니다.")
            val setting = context.maps.singleOrNull { it.categoryId == target.categoryId && it.mapCode == target.mapCode }
            val selected = setting?.let { FishingAutomationPreset(it.presetMode, it.presetId, it.resolvedParty) }
                ?: context.primaryPreset
                ?: return HandlerEvaluation.ConfigurationWarning("${target.name} 낚시 전투 프리셋을 선택해 주세요.", "FISHING_PRESET_MISSING")
            val presetId = selected.presetId
                ?: return HandlerEvaluation.ConfigurationWarning("${target.name} 낚시 전투 프리셋을 선택해 주세요.", "FISHING_PRESET_MISSING")
            val party = selected.resolvedParty
                ?: return HandlerEvaluation.ConfigurationWarning("${target.name} 낚시 전투 프리셋 구성을 확인해 주세요.", "FISHING_PARTY_INVALID")
            return HandlerEvaluation.Runnable(BattleMapAutomationAction(
                context.accountId, context.now.atZone(SEOUL).toLocalDate(), target.categoryId, target.mapCode,
                selected.presetMode, presetId, 1, UUID.randomUUID().toString(),
                BattleAutomationActionSource.FISHING_AUTOMATION, party, target.name,
            ))
        }
        if (
            context.state.primaryAction == FishingPrimaryAction.START &&
            context.state.lastOutcome == app.spammy.hof.town.fishing.model.FishingOutcome.STARTED
        ) {
            return HandlerEvaluation.Unavailable(
                context.now.plusSeconds(5),
                "FISHING_CATCH_TRANSITION_PENDING",
                "낚시 시작은 처리됐지만 잡기 동작이 아직 표시되지 않아 상태 전환을 다시 확인합니다.",
                AutomationWaitScope.HOLD_CURRENT_WORK,
            )
        }
        if (context.state.remainingCasts == 0 && context.state.primaryAction != FishingPrimaryAction.CATCH) {
            val next = context.now.atZone(SEOUL).toLocalDate().plusDays(1).atStartOfDay(SEOUL).toInstant()
            return HandlerEvaluation.Unavailable(next, "FISHING_DAILY_LIMIT", "오늘의 낚시 횟수를 모두 사용했습니다.")
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
