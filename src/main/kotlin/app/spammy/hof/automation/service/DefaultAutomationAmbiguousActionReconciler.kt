package app.spammy.hof.automation.service

import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.quest.service.QuestGatewayService
import org.springframework.stereotype.Service

@Service
class DefaultAutomationAmbiguousActionReconciler(
    private val questGateway: QuestGatewayService,
    private val battleMapService: BattleMapService,
    private val battleHandler: BattleMapAutomationHandler,
    private val sessionRecovery: HofSessionRecoveryExecutor,
    private val timeProvider: TimeProvider,
    private val workLifecycle: AutomationWorkLifecycle,
) : AutomationAmbiguousActionReconciler {
    override fun reconcile(
        accountId: Long,
        action: StoredTypedAutomationAction,
    ): AmbiguousActionResolution = when (val payload = action.payload) {
        is StoredTypedActionPayload.QuestAccept -> reconcileQuestAccept(accountId, payload)
        is StoredTypedActionPayload.QuestClaim -> reconcileQuestClaim(accountId, payload)
        is StoredTypedActionPayload.QuestBattle -> reconcileQuestBattle(accountId, payload)
        is StoredTypedActionPayload.AdventureMap -> reconcileAdventure(accountId, action.entryId, payload)
        is StoredTypedActionPayload.BattleMap -> reconcileBattleMap(
            accountId,
            action.entryId,
            action.executionIdentity,
            payload,
        )
    }

    private fun reconcileQuestAccept(
        accountId: Long,
        payload: StoredTypedActionPayload.QuestAccept,
    ): AmbiguousActionResolution {
        val quest = loadQuests(accountId).singleOrNull { it.questKey == payload.questKey }
            ?: return AmbiguousActionResolution.VerifyLater(
                retryAt(),
                "Quest ${payload.questKey} is absent from the authoritative page.",
            )
        return when {
            quest.state in setOf(QuestState.ACTIVE, QuestState.CLAIMABLE, QuestState.COMPLETED) ->
                AmbiguousActionResolution.Applied()
            quest.state == QuestState.AVAILABLE && quest.actionNo == payload.actionNo ->
                AmbiguousActionResolution.Resubmit
            else -> AmbiguousActionResolution.VerifyLater(
                retryAt(),
                "Quest accept outcome is not yet authoritative.",
            )
        }
    }

    private fun reconcileQuestClaim(
        accountId: Long,
        payload: StoredTypedActionPayload.QuestClaim,
    ): AmbiguousActionResolution {
        val quest = loadQuests(accountId).singleOrNull { it.questKey == payload.questKey }
            ?: return AmbiguousActionResolution.Applied()
        return when {
            quest.state == QuestState.COMPLETED -> AmbiguousActionResolution.Applied()
            quest.state == QuestState.CLAIMABLE && quest.actionNo == payload.actionNo ->
                AmbiguousActionResolution.Resubmit
            else -> AmbiguousActionResolution.VerifyLater(
                retryAt(),
                "Quest claim outcome is not yet authoritative.",
            )
        }
    }

    private fun reconcileQuestBattle(
        accountId: Long,
        payload: StoredTypedActionPayload.QuestBattle,
    ): AmbiguousActionResolution {
        val quest = loadQuests(accountId).singleOrNull { it.questKey == payload.questKey }
            ?: return AmbiguousActionResolution.VerifyLater(
                retryAt(),
                "Quest ${payload.questKey} is absent from the authoritative page.",
            )
        if (quest.state in setOf(QuestState.CLAIMABLE, QuestState.COMPLETED)) {
            return AmbiguousActionResolution.Applied(
                TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode),
            )
        }
        val mission = quest.missions.singleOrNull { it.key == payload.missionKey }
            ?: return AmbiguousActionResolution.VerifyLater(
                retryAt(),
                "Quest mission ${payload.missionKey} is absent from the authoritative page.",
            )
        val current = mission.progress?.current
            ?: return AmbiguousActionResolution.VerifyLater(
                retryAt(),
                "Quest mission progress is not authoritative yet.",
            )
        val baseline = payload.observedCurrent
            ?: return AmbiguousActionResolution.VerifyLater(
                retryAt(),
                "Stored quest mission has no pre-submit progress.",
            )
        return when {
            current > baseline -> AmbiguousActionResolution.Applied(
                TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode),
            )
            current == baseline && quest.state == QuestState.ACTIVE -> AmbiguousActionResolution.Resubmit
            else -> AmbiguousActionResolution.VerifyLater(
                retryAt(),
                "Quest battle outcome is not yet authoritative.",
            )
        }
    }

    private fun loadQuests(accountId: Long) = sessionRecovery.execute(accountId) {
        questGateway.load(accountId, HofRequestOrigin.AUTOMATION)
    }

    private fun reconcileAdventure(
        accountId: Long,
        entryId: Long,
        payload: StoredTypedActionPayload.AdventureMap,
    ): AmbiguousActionResolution {
        val current = sessionRecovery.execute(accountId) {
            battleMapService.findMaps(accountId, payload.categoryId, HofRequestOrigin.AUTOMATION)
        }.singleOrNull { it.mapCode == payload.mapCode }
            ?: return AmbiguousActionResolution.VerifyLater(
                retryAt(),
                "Adventure map ${payload.categoryId}/${payload.mapCode} is absent.",
            )
        val decreased = listOf(
            payload.observedAttemptRemaining to current.attemptCount,
            payload.observedWinRemaining to current.winCount,
            payload.observedAvailableCount to current.availableCount,
        ).any { (before, after) -> before != null && after != null && after < before }
        val cooldownStarted = current.cooldownRemainingSeconds?.let { it > 0 } == true
        if (decreased || cooldownStarted) {
            workLifecycle.completeAdventureAction(accountId, entryId, payload.categoryId, payload.mapCode)
            return AmbiguousActionResolution.Applied(
                TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode),
            )
        }
        val exhausted = listOf(current.attemptCount, current.winCount, current.availableCount)
            .any { it != null && it <= 0 }
        val runnable = current.resolved && current.enabled && !exhausted &&
            current.keyCount != 0 && (current.cooldownRemainingSeconds ?: 0) <= 0
        if (runnable) return AmbiguousActionResolution.Resubmit
        val retryAt = current.cooldownRemainingSeconds
            ?.takeIf { it > 0 }
            ?.let { timeProvider.now().plusSeconds(it) }
            ?: retryAt()
        return AmbiguousActionResolution.VerifyLater(
            retryAt,
            "Adventure map outcome is not yet authoritative.",
        )
    }

    private fun reconcileBattleMap(
        accountId: Long,
        entryId: Long,
        executionIdentity: String,
        payload: StoredTypedActionPayload.BattleMap,
    ): AmbiguousActionResolution {
        val action = BattleMapAutomationAction(
            accountId = accountId,
            progressDate = payload.progressDate,
            categoryId = payload.categoryId,
            mapCode = payload.mapCode,
            presetMode = payload.presetMode,
            presetId = payload.presetId,
            battleCount = payload.battleCount,
            executionIdentity = executionIdentity,
        )
        battleHandler.confirmAmbiguousSuccess(action)
        workLifecycle.completeBattleMapAction(
            accountId,
            entryId,
            payload.categoryId,
            payload.mapCode,
        )
        return AmbiguousActionResolution.Applied(
            TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode),
        )
    }

    private fun retryAt() = timeProvider.now().plusSeconds(10)
}
