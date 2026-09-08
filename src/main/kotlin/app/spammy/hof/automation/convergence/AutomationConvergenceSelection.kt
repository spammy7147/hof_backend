package app.spammy.hof.automation.convergence

import app.spammy.hof.automation.raid.RaidDecision
import app.spammy.hof.automation.raid.RaidDirective
import app.spammy.hof.automation.raid.RaidIntent
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.service.HandlerEvaluation
import app.spammy.hof.automation.service.PreparedAutomationAction
import app.spammy.hof.automation.service.QuestAction
import app.spammy.hof.automation.service.QuestAutomationSnapshot
import app.spammy.hof.automation.service.RaidTownAutomationAction
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.town.raid.model.RaidAction
import org.slf4j.LoggerFactory

/** 한 관측에서 후보를 탐색하는 동안 보류 해제와 허용 판정의 순서를 소유한다. */
class AutomationConvergenceSelection internal constructor(
    private val accountId: Long,
    private val entryId: Long,
    quest: QuestAutomationSnapshot?,
    private val mode: AutomationConvergenceMode?,
    private val convergence: AutomationActionConvergenceModule,
    private val store: ConvergenceStore,
    private val timeProvider: TimeProvider,
) {
    private val factory = StoredActionConvergenceSelectionFactory()
    private val observedScopes = mutableSetOf<AutomationIsolationScope>()
    private val questBaselines = quest?.let(factory::authoritativeQuestBattleBaselines).orEmpty()

    fun block(action: PreparedAutomationAction): ConvergenceSelectionBlock? {
        val preview = factory.preview(entryId, action)
        // 다른 미션을 탐색한 사실은 같은 퀘스트의 과거 미션이 바뀐 증거가 아니다.
        if (observedScopes.add(preview.scope) &&
            (action !is RaidTownAutomationAction || action.action != RaidAction.REFRESH)
        ) {
            preview.baselineFingerprint?.let { baseline ->
                val baselines = (action as? QuestAction.Battle)?.let { questBaselines[it.questKey] }
                    ?.takeIf { it.isNotEmpty() } ?: setOf(baseline)
                observeBaselines(preview.scope, baselines)
            }
        }
        fun blocked(reason: String, message: String) =
            ConvergenceSelectionBlock(reason, message, preview.scope, preview.actionKind)

        if (store.activeBattleGate(accountId) != null && preview.actionKind.battle) {
            return blocked("CAPTCHA_BATTLE_GATE_BLOCKED", "캡차 해결 전까지 전투 범위만 잠시 건너뜁니다.")
        }
        if (preview.baselineFingerprint in store.findSuppressedBaselines(accountId)[preview.scope].orEmpty()) {
            return blocked("CONVERGENCE_SCOPE_BLOCKED",
                "이전 행동 결과를 확정하지 못해 해당 범위를 보류했습니다. 최신 상태의 복구 조건을 확인하면 해제합니다.")
        }
        // 전투 관문과 영구 억제는 LEGACY/SHADOW에서도 적용한다.
        if (mode == AutomationConvergenceMode.LEGACY || mode == AutomationConvergenceMode.SHADOW) return null
        if (mode == AutomationConvergenceMode.ACTIVE) {
            convergence.resolveObservationGap(accountId, preview.scope, timeProvider.now())
        }
        val scopeBlocked = preview.scope in store.findActiveScopes(accountId) ||
            (preview.actionKind.battle && store.activeBattleGate(accountId) != null) ||
            preview.baselineFingerprint in store.findSuppressedBaselines(accountId)[preview.scope].orEmpty()
        return if (scopeBlocked) blocked("CONVERGENCE_SCOPE_BLOCKED", "이전 행동 결과를 확인 중이라 해당 범위만 잠시 건너뜁니다.") else null
    }

    fun observeRaidDecision(decision: RaidDecision): RaidDecision {
        val registration = (decision.directive as? RaidDirective.Execute)?.intent as? RaidIntent.Town
        if (registration?.kind == RaidIntentKind.REGISTER) {
            val preview = factory.preview(registration.entryId, RaidTownAutomationAction(
                accountId = accountId,
                action = RaidAction.REGISTER,
                raidId = registration.requestRaidId,
                targetRaidId = registration.raidId,
                raidName = registration.raidName,
                observedStatus = registration.observedStatus,
            ))
            if (preview.baselineFingerprint in store.findSuppressedBaselines(accountId)[preview.scope].orEmpty()) {
                return RaidDecision(RaidDirective.Execute(
                    registration.copy(kind = RaidIntentKind.REFRESH, requestRaidId = null),
                    reasonCode = "RAID_REGISTRATION_RECOVERY_REFRESH",
                    message = "기존 신청 보류를 재평가하기 위해 레이드 상태를 갱신합니다.",
                ), decision.authoritativeState)
            }
        }
        decision.authoritativeState?.let(factory::authoritativeRaidBaseline)?.let { baseline ->
            observeBaselines(baseline.scope, setOf(baseline.fingerprint))
        }
        return decision
    }

    private fun observeBaselines(scope: AutomationIsolationScope, baselines: Set<String>) {
        val released = convergence.observeAuthoritativeBaselines(accountId, scope, baselines, timeProvider.now())
        if (released > 0) log.info(
            "Automation convergence suppression released accountId={} scopeKind={} scopeKey={} count={}",
            accountId, scope.kind, scope.key, released,
        )
    }

    fun observeGap(gap: HandlerEvaluation.ObservationGap): ConvergenceDirective? {
        if (mode != AutomationConvergenceMode.ACTIVE) return null
        val selection = factory.createObservationGap(
            entryId, gap.actionKind, gap.scopeKind, gap.scopeKey ?: entryId.toString(), gap.baseline,
        )
        return convergence.observeGap(accountId, selection, AutomationActionEvidence.IncompleteObservation(
            capturedAt = timeProvider.now(), reason = gap.reasonCode, authoritative = gap.authoritative,
        ))
    }

    private companion object {
        val log = LoggerFactory.getLogger(AutomationConvergenceSelection::class.java)
    }
}

data class ConvergenceSelectionBlock(
    val reasonCode: String,
    val message: String,
    val scope: AutomationIsolationScope,
    val actionKind: AutomationActionKind,
)
