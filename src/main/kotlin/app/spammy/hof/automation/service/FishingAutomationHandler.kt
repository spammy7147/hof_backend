package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.AutomationActionKind
import app.spammy.hof.automation.convergence.AutomationIsolationScopeKind
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.town.fishing.dto.FishingResponse
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import app.spammy.hof.town.fishing.service.FishingAutomationObservation
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private val FISHING_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

data class FishingAutomationSnapshot(
    val accountId: Long,
    val state: FishingResponse,
    val maps: List<FishingAutomationMapSetting>,
    val primaryPreset: FishingAutomationPreset?,
    val now: Instant,
    val observation: FishingAutomationObservation? = null,
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
    val progressDate: LocalDate? = null,
    val observation: FishingAutomationObservation? = null,
) : PreparedAutomationAction

@Service
class FishingAutomationHandler : AutomationHandler<FishingAutomationSnapshot> {
    override fun evaluate(context: FishingAutomationSnapshot): HandlerEvaluation {
        if (!context.state.battleObservationComplete) return HandlerEvaluation.ObservationGap(
            actionKind = AutomationActionKind.FISHING_OBSTRUCTION_BATTLE,
            scopeKind = AutomationIsolationScopeKind.FISHING_ENTRY,
            baseline = "fishing|battle-observation-incomplete",
            nextRunAt = context.now.plusSeconds(10),
            reasonCode = "FISHING_BATTLE_OBSERVATION_INCOMPLETE",
            message = "현재 낚시 전투 목록을 완전하게 확인하지 못해 이 낚시 항목만 다시 확인합니다.",
            authoritative = false,
        )
        fishingObstructionEvaluation(context)?.let { return it }
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
            val next = context.now.atZone(FISHING_ZONE).toLocalDate().plusDays(1).atStartOfDay(FISHING_ZONE).toInstant()
            return HandlerEvaluation.Unavailable(next, "FISHING_DAILY_LIMIT", "오늘의 낚시 횟수를 모두 사용했습니다.")
        }
        return when (context.state.primaryAction) {
            FishingPrimaryAction.START -> HandlerEvaluation.Runnable(FishingTownAutomationAction(
                context.accountId,
                FishingAction.START,
                context.state.primaryAction,
                context.state.remainingCasts,
                context.now.atZone(FISHING_ZONE).toLocalDate(),
                context.observation,
            ))
            FishingPrimaryAction.CATCH -> HandlerEvaluation.Runnable(FishingTownAutomationAction(
                context.accountId,
                FishingAction.CATCH,
                context.state.primaryAction,
                context.state.remainingCasts,
                context.now.atZone(FISHING_ZONE).toLocalDate(),
                context.observation,
            ))
            FishingPrimaryAction.NONE -> retry(context, "FISHING_STATE_INCOMPLETE", "낚시 상태를 다시 확인합니다.")
        }
    }

    private fun retry(context: FishingAutomationSnapshot, code: String, message: String) =
        HandlerEvaluation.Unavailable(context.now.plusSeconds(30), code, message)

}

internal fun fishingObstructionEvaluation(context: FishingAutomationSnapshot): HandlerEvaluation? {
    if (!context.state.blockedByBattle) return null
    val target = context.state.battleTarget ?: return HandlerEvaluation.ObservationGap(
        actionKind = AutomationActionKind.FISHING_OBSTRUCTION_BATTLE,
        scopeKind = AutomationIsolationScopeKind.FISHING_ENTRY,
        baseline = "fishing|blocked|battle-target-missing",
        nextRunAt = context.now.plusSeconds(10),
        reasonCode = "FISHING_BATTLE_TARGET_MISSING",
        message = "낚시 전투 대상 식별자를 읽지 못해 최신 상태를 다시 확인합니다.",
        authoritative = true,
    )
    val setting = context.maps.singleOrNull { it.categoryId == target.categoryId && it.mapCode == target.mapCode }
    val selected = setting?.let { FishingAutomationPreset(it.presetMode, it.presetId, it.resolvedParty) }
        ?: context.primaryPreset
        ?: return HandlerEvaluation.ConfigurationWarning(
            "${target.name} 낚시 전투 프리셋을 선택해 주세요.",
            "FISHING_PRESET_MISSING",
        )
    val presetId = selected.presetId
        ?: return HandlerEvaluation.ConfigurationWarning(
            "${target.name} 낚시 전투 프리셋을 선택해 주세요.",
            "FISHING_PRESET_MISSING",
        )
    val party = selected.resolvedParty
        ?: return HandlerEvaluation.ConfigurationWarning(
            "${target.name} 낚시 전투 프리셋 구성을 확인해 주세요.",
            "FISHING_PARTY_INVALID",
        )
    return HandlerEvaluation.Runnable(BattleMapAutomationAction(
        context.accountId,
        context.now.atZone(FISHING_ZONE).toLocalDate(),
        target.categoryId,
        target.mapCode,
        selected.presetMode,
        presetId,
        1,
        UUID.randomUUID().toString(),
        BattleAutomationActionSource.FISHING_AUTOMATION,
        party,
        target.name,
    ))
}
