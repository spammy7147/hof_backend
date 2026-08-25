package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class UnionAutomationSetting(
    val targetKey: String, val categoryId: String, val mapCode: String,
    val presetMode: PresetSelectionMode, val presetId: Long?, val executionOrder: Int,
    val resolvedParty: ResolvedAutomationParty?,
)
data class UnionAutomationSnapshot(
    val accountId: Long, val settings: List<UnionAutomationSetting>,
    val states: List<BattleMapRunnableState>, val currentTargetKey: String?, val now: Instant,
)

@Service
class UnionAutomationHandler : AutomationHandler<UnionAutomationSnapshot> {
    override fun evaluate(context: UnionAutomationSnapshot): HandlerEvaluation {
        val ordered = rotate(context.settings.sortedWith(compareBy(UnionAutomationSetting::executionOrder, UnionAutomationSetting::targetKey)), context.currentTargetKey)
        if (ordered.isEmpty()) return HandlerEvaluation.ConfigurationWarning("유니온 맵을 하나 이상 선택해 주세요.", "UNION_TARGET_MISSING")
        val states = context.states.associateBy { "${it.categoryId}:${it.mapCode}" }
        var cooldownObserved = false
        for (setting in ordered) {
            val state = states[setting.targetKey] ?: continue
            state.cooldownUntil?.takeIf { it > context.now }?.let {
                cooldownObserved = true
                continue
            }
            if (!state.visible || !state.enabled) continue
            val presetId = setting.presetId ?: return HandlerEvaluation.ConfigurationWarning("유니온 전투 프리셋을 선택해 주세요.", "UNION_PRESET_MISSING")
            val party = setting.resolvedParty ?: return HandlerEvaluation.ConfigurationWarning("유니온 전투 프리셋 구성을 확인해 주세요.", "UNION_PARTY_INVALID")
            return HandlerEvaluation.Runnable(BattleMapAutomationAction(
                context.accountId, context.now.atZone(SEOUL).toLocalDate(), setting.categoryId, setting.mapCode,
                setting.presetMode, presetId, 1, UUID.randomUUID().toString(),
                BattleAutomationActionSource.UNION_AUTOMATION, party, state.mapName,
            ))
        }
        return if (cooldownObserved) {
            HandlerEvaluation.SkippedReason(
                "UNION_SHARED_COOLDOWN",
                "유니온 쿨타임 중이라 이번 판단에서 건너뜁니다.",
            )
        } else {
            HandlerEvaluation.SkippedReason(
                "UNION_MAP_ABSENT",
                "현재 관측된 유니온 맵이 없어 건너뜁니다.",
            )
        }
    }

    private fun rotate(values: List<UnionAutomationSetting>, key: String?): List<UnionAutomationSetting> {
        val index = values.indexOfFirst { it.targetKey == key }
        return if (index <= 0) values else values.drop(index) + values.take(index)
    }
    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
