package app.spammy.hof.automation.service

import org.slf4j.LoggerFactory
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.automation.history.AutomationActionTrace
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import java.time.Instant

internal fun automationActionTrace(
    action: StoredTypedAutomationAction,
    kind: AutomationHistoryEventKind,
    code: String,
    message: String,
    nextRunAt: Instant? = null,
    descriptor: AutomationActionDescriptor,
    diagnosticKind: AutomationDiagnosticKind? = null,
    cooldownSource: app.spammy.hof.automation.raid.RaidCooldownSource? = null,
    impactScope: AutomationImpactScope? = null,
    releaseCondition: String? = null,
    diagnosticContext: String? = null,
    observedAt: Instant,
) = AutomationActionTrace(
    kind = kind,
    reasonCode = code,
    message = "${descriptor.context} · $message",
    entryId = action.entryId,
    type = descriptor.source,
    actionKind = descriptor.actionKind,
    targetKey = descriptor.targetKey,
    targetName = descriptor.targetName,
    presetId = descriptor.presetId,
    nextRunAt = nextRunAt,
    diagnosticKind = diagnosticKind,
    cooldownSource = cooldownSource,
    impactScope = impactScope,
    releaseCondition = releaseCondition,
    diagnosticContext = if (descriptor.source == app.spammy.hof.automation.entity.AutomationType.FISHING) {
        AutomationDecisionDiagnostics.actionResult(
            diagnosticContext ?: AutomationDecisionDiagnostics.fishingAction(action, null, "UNOBSERVED", observedAt),
            code, nextRunAt,
        )
    } else diagnosticContext,
)


private val log = LoggerFactory.getLogger("app.spammy.hof.automation.service.AutomationActionHistory")

internal fun recordPreparationRecovery(
    decisionJournal: AutomationDecisionJournal?,
    accountId: Long, cycleId: Long?, stored: StoredTypedAutomationAction, descriptor: AutomationActionDescriptor,
) {
    if (cycleId == null) return
    val target = preparationDescriptor(stored, descriptor)
    if (decisionJournal?.preparationFailures(accountId)?.any { it.blocks(stored.entryId, target.targetKey) } != true) return
    runCatching { decisionJournal.appendActionResult(cycleId, AutomationActionTrace(
        AutomationHistoryEventKind.EVALUATED, "ACTION_PREPARATION_RECOVERED", "행동 준비를 마쳐 기존 준비 오류를 해제했습니다.",
        entryId = stored.entryId, type = target.source, targetKey = target.targetKey, targetName = target.targetName))
    }.onFailure { log.warn("Could not record preparation recovery accountId={}", accountId, it) }
}

internal fun preparationDescriptor(stored: StoredTypedAutomationAction, descriptor: AutomationActionDescriptor) =
    descriptor.copy(targetKey = when (val payload = stored.payload) {
        is StoredTypedActionPayload.QuestAccept -> payload.questKey
        is StoredTypedActionPayload.QuestClaim -> payload.questKey
        is StoredTypedActionPayload.QuestBattle -> payload.questKey
        is StoredTypedActionPayload.FishingTown -> FISHING_CYCLE_TARGET
        is StoredTypedActionPayload.BattleMap -> when (payload.source) {
            BattleAutomationActionSource.FISHING_AUTOMATION -> FISHING_CYCLE_TARGET
            BattleAutomationActionSource.RAID_AUTOMATION -> payload.sourceTargetKey
            else -> descriptor.targetKey
        }
        else -> descriptor.targetKey
    })
