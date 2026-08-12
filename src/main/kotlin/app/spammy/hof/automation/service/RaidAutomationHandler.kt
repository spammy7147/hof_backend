package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.RaidAutomationCycleStatus
import app.spammy.hof.town.raid.dto.RaidPubResponse
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.model.RaidStatus
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class RaidAutomationTarget(
    val raidId: String, val name: String, val presetMode: PresetSelectionMode,
    val presetId: Long?, val executionOrder: Int, val resolvedParty: ResolvedAutomationParty?,
)
data class OpenRaidCycleSnapshot(val id: Long, val raidId: String, val status: RaidAutomationCycleStatus, val nextCheckAt: Instant?)
data class RaidAutomationSnapshot(
    val accountId: Long, val pub: RaidPubResponse, val targets: List<RaidAutomationTarget>,
    val currentTargetKey: String?, val openCycle: OpenRaidCycleSnapshot?, val now: Instant,
)
data class RaidTownAutomationAction(val accountId: Long, val action: RaidAction, val raidId: String? = null) : PreparedAutomationAction
data class RaidCycleAbortAutomationAction(val accountId: Long, val raidId: String) : PreparedAutomationAction

@Service
class RaidAutomationHandler : AutomationHandler<RaidAutomationSnapshot> {
    override fun evaluate(context: RaidAutomationSnapshot): HandlerEvaluation {
        if (RaidAction.REWARD in context.pub.globalActions) {
            return HandlerEvaluation.Runnable(RaidTownAutomationAction(context.accountId, RaidAction.REWARD))
        }
        val cycle = context.openCycle
        if (cycle != null) return evaluateCycle(context, cycle)
        val ordered = rotate(context.targets.sortedWith(compareBy(RaidAutomationTarget::executionOrder, RaidAutomationTarget::raidId)), context.currentTargetKey)
        val target = ordered.firstOrNull() ?: return HandlerEvaluation.ConfigurationWarning("레이드를 하나 이상 선택해 주세요.", "RAID_TARGET_MISSING")
        if (context.pub.applyWait) return HandlerEvaluation.Unavailable(
            context.now.plusSeconds((context.pub.applyWaitSeconds ?: 30).coerceAtLeast(5).toLong()), "RAID_SHARED_COOLDOWN", "레이드 등록 쿨다운을 기다립니다.",
        )
        val raid = context.pub.raids.singleOrNull { it.id == target.raidId }
            ?: return HandlerEvaluation.ConfigurationWarning("차례인 레이드가 현재 보이지 않아 이번 판단을 건너뜁니다.", "RAID_TARGET_NOT_VISIBLE")
        if (!raid.playable || RaidAction.REGISTER !in raid.actions) {
            return HandlerEvaluation.ConfigurationWarning("차례인 레이드는 현재 등록할 수 없습니다.", "RAID_REGISTER_UNAVAILABLE")
        }
        return HandlerEvaluation.Runnable(RaidTownAutomationAction(context.accountId, RaidAction.REGISTER, target.raidId))
    }

    private fun evaluateCycle(context: RaidAutomationSnapshot, cycle: OpenRaidCycleSnapshot): HandlerEvaluation {
        cycle.nextCheckAt?.takeIf { it > context.now }?.let {
            return HandlerEvaluation.Unavailable(it, "RAID_NEXT_CHECK", "진행 중인 레이드를 다음 확인 시각에 다시 확인합니다.")
        }
        val raid = context.pub.raids.singleOrNull { it.id == cycle.raidId }
            ?: return retry(context, "RAID_TARGET_TEMPORARILY_MISSING", "진행 중인 레이드 대상을 다시 확인합니다.")
        if (raid.status == RaidStatus.CLOSED) return HandlerEvaluation.Runnable(RaidCycleAbortAutomationAction(context.accountId, cycle.raidId))
        if (raid.status in setOf(RaidStatus.TESTING, RaidStatus.UNKNOWN)) return retry(context, "RAID_STATUS_UNCERTAIN", "레이드 상태를 다시 확인합니다.")
        if (RaidAction.START in raid.actions) return HandlerEvaluation.Runnable(RaidTownAutomationAction(context.accountId, RaidAction.START, raid.id))
        if (raid.status == RaidStatus.COMPLETED || RaidAction.REWARD in context.pub.globalActions) {
            return HandlerEvaluation.Runnable(RaidTownAutomationAction(context.accountId, RaidAction.REWARD))
        }
        if (raid.status == RaidStatus.IN_BATTLE) {
            val battle = raid.battleTarget ?: return retry(context, "RAID_BATTLE_TARGET_MISSING", "레이드 전투 대상을 다시 확인합니다.")
            val setting = context.targets.singleOrNull { it.raidId == cycle.raidId }
                ?: return HandlerEvaluation.ConfigurationWarning("진행 중 레이드 설정을 찾을 수 없습니다.", "RAID_ACTIVE_TARGET_REMOVED")
            val presetId = setting.presetId ?: return HandlerEvaluation.ConfigurationWarning("레이드 전투 프리셋을 선택해 주세요.", "RAID_PRESET_MISSING")
            val party = setting.resolvedParty ?: return HandlerEvaluation.ConfigurationWarning("레이드 전투 프리셋 구성을 확인해 주세요.", "RAID_PARTY_INVALID")
            return HandlerEvaluation.Runnable(BattleMapAutomationAction(
                context.accountId, context.now.atZone(SEOUL).toLocalDate(), battle.categoryId, battle.mapCode,
                setting.presetMode, presetId, 1, UUID.randomUUID().toString(), BattleAutomationActionSource.RAID_AUTOMATION, party, raid.name,
            ))
        }
        return retry(context, "RAID_WAITING_TO_START", "레이드 출발 가능 상태를 기다립니다.", raid.waitSeconds)
    }

    private fun retry(context: RaidAutomationSnapshot, code: String, message: String, seconds: Int? = null) =
        HandlerEvaluation.Unavailable(context.now.plusSeconds((seconds ?: 30).coerceAtLeast(5).toLong()), code, message)
    private fun rotate(values: List<RaidAutomationTarget>, key: String?): List<RaidAutomationTarget> {
        val index = values.indexOfFirst { it.raidId == key }
        return if (index <= 0) values else values.drop(index) + values.take(index)
    }
    private companion object { val SEOUL: ZoneId = ZoneId.of("Asia/Seoul") }
}
