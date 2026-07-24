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
) : AutomationAmbiguousActionReconciler {
    override fun reconcile(
        accountId: Long,
        action: StoredTypedAutomationActionV1,
    ): AmbiguousActionResolution = when (val payload = action.payload) {
        is StoredTypedActionPayload.QuestAccept -> reconcileQuestAccept(accountId, payload)
        is StoredTypedActionPayload.QuestClaim -> reconcileQuestClaim(accountId, payload)
        is StoredTypedActionPayload.QuestBattle -> reconcileQuestBattle(accountId, payload)
        else -> AmbiguousActionResolution.VerifyLater(
            retryAt(),
            "Authoritative reconciliation is not available for ${payload.kind()} yet.",
        )
    }

    private fun reconcileQuestAccept(
        accountId: Long,
        payload: StoredTypedActionPayload.QuestAccept,
    ): AmbiguousActionResolution {
        val quest = loadQuests(accountId).singleOrNull { it.questId == payload.questCode }
            ?: return AmbiguousActionResolution.VerifyLater(
                retryAt(),
                "Quest ${payload.questCode} is absent from the authoritative page.",
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
        val quest = loadQuests(accountId).singleOrNull { it.questId == payload.questCode }
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
        val quest = loadQuests(accountId).singleOrNull { it.questId == payload.questCode }
            ?: return AmbiguousActionResolution.VerifyLater(
                retryAt(),
                "Quest ${payload.questCode} is absent from the authoritative page.",
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

    private fun retryAt() = timeProvider.now().plusSeconds(10)
}
