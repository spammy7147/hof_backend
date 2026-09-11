package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationRotationStateEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.automation.repository.AutomationRotationStateCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UnionAutomationProgressService(
    private val query: TypedAutomationQueryRepository,
    private val rotations: AutomationRotationStateCommandRepository,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun battleCompleted(accountId: Long, entryId: Long, categoryId: String, mapCode: String, executionIdentity: String) {
        val entry = requireEntry(accountId, entryId)
        val action = requireNotNull(query.findTypedActionByExecutionIdentity(accountId, executionIdentity))
        require(action.entry?.id == entryId && action.actionKind == "BATTLE_MAP")
        // 완료 때 맵이 삭제돼 순환 표식이 없더라도, 같은 결과를 새 설정에 다시 반영하지 않는다.
        if (action.status == TypedAutomationActionStatus.SUCCEEDED) return
        val state = query.findRotationState(entryId)
        // 생성 순서가 같은 계정의 행동 순서다. 재적용과 늦은 과거 결과는 최신 순환을 바꾸지 않는다.
        if (state?.lastCompletedActionId?.let { action.id <= it } == true) return
        val settings = query.findUnionSettings(entryId)
        val currentIndex = settings.indexOfFirst { it.categoryId == categoryId && it.mapCode == mapCode }
        if (currentIndex < 0 || settings.isEmpty()) return
        val next = settings[(currentIndex + 1) % settings.size]
        val now = timeProvider.now()
        val rotation = state ?: AutomationRotationStateEntity(
            entry = entry, currentTargetKey = "${next.categoryId}:${next.mapCode}", updatedAt = now,
        )
        rotation.currentTargetKey = "${next.categoryId}:${next.mapCode}"
        rotation.lastCompletedActionId = action.id
        rotation.updatedAt = now
        rotations.save(rotation)
    }

    private fun requireEntry(accountId: Long, entryId: Long) =
        query.findEntry(accountId, entryId)?.takeIf { it.type == AutomationType.UNION }
            ?: throw AutomationConfigurationException("Automation entry is missing or has an invalid type.")
}
