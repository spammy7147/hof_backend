package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationRotationStateEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.repository.AutomationRotationStateCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UnionAutomationProgressService(
    private val query: TypedAutomationQueryRepository,
    private val rotations: AutomationRotationStateCommandRepository,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun battleCompleted(accountId: Long, entryId: Long, categoryId: String, mapCode: String) {
        val entry = requireEntry(accountId, entryId)
        val settings = query.findUnionSettings(entryId)
        val currentIndex = settings.indexOfFirst { it.categoryId == categoryId && it.mapCode == mapCode }
        if (currentIndex < 0 || settings.isEmpty()) return
        val next = settings[(currentIndex + 1) % settings.size]
        saveRotation(entry, "${next.categoryId}:${next.mapCode}")
    }

    private fun requireEntry(accountId: Long, entryId: Long) =
        query.findEntry(accountId, entryId)?.takeIf { it.type == AutomationType.UNION }
            ?: throw AutomationConfigurationException("Automation entry is missing or has an invalid type.")

    private fun saveRotation(entry: AutomationEntryEntity, key: String, now: Instant = timeProvider.now()) {
        val state = query.findRotationState(entry.id)
        if (state == null) rotations.save(
            AutomationRotationStateEntity(entry = entry, currentTargetKey = key, updatedAt = now),
        ) else {
            state.currentTargetKey = key
            state.updatedAt = now
            rotations.save(state)
        }
    }
}
