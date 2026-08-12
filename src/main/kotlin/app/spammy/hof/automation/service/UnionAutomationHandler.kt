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
        val waits = mutableListOf<Instant>()
        for (setting in ordered) {
            val state = states[setting.targetKey] ?: continue
            state.cooldownUntil?.takeIf { it > context.now }?.let { waits += it; continue }
            if (!state.visible || !state.enabled) continue
            val presetId = setting.presetId ?: return HandlerEvaluation.ConfigurationWarning("유니온 전투 프리셋을 선택해 주세요.", "UNION_PRESET_MISSING")
            val party = setting.resolvedParty ?: return HandlerEvaluation.ConfigurationWarning("유니온 전투 프리셋 구성을 확인해 주세요.", "UNION_PARTY_INVALID")
            return HandlerEvaluation.Runnable(BattleMapAutomationAction(
                context.accountId, context.now.atZone(SEOUL).toLocalDate(), setting.categoryId, setting.mapCode,
                setting.presetMode, presetId, 1, UUID.randomUUID().toString(),
                BattleAutomationActionSource.UNION_AUTOMATION, party, state.mapName,
            ))
        }
        return waits.minOrNull()?.let { HandlerEvaluation.Unavailable(it, "UNION_SHARED_COOLDOWN", "유니온 공유 쿨다운을 기다립니다.") }
            ?: HandlerEvaluation.ConfigurationWarning("현재 실행 가능한 유니온 맵이 없습니다.", "UNION_MAP_NOT_VISIBLE")
    }

    private fun rotate(values: List<UnionAutomationSetting>, key: String?): List<UnionAutomationSetting> {
        val index = values.indexOfFirst { it.targetKey == key }
        return if (index <= 0) values else values.drop(index) + values.take(index)
    }
    private companion object { val SEOUL: ZoneId = ZoneId.of("Asia/Seoul") }
}
