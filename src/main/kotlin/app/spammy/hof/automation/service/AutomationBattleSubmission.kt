package app.spammy.hof.automation.service

import app.spammy.hof.battle.dto.BattleResultResponse
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.service.BattleRunService
import app.spammy.hof.battle.service.SharedBattleCooldownRejectedException
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.external.model.HofRequestOrigin
import java.time.LocalDate
import org.springframework.stereotype.Service

sealed interface AutomationBattleSubmissionResult {
    data class Completed(
        val response: BattleResultResponse,
        val resultIdentity: String,
        val outcomes: List<BattleAutomationRoundOutcome>,
    ) : AutomationBattleSubmissionResult

    data class SharedCooldown(
        val retryAt: java.time.Instant,
    ) : AutomationBattleSubmissionResult
}

/**
 * 모든 자동화 전투가 공유하는 제출과 단말 결과 증명 구현이다.
 *
 * 호출자는 전투 family별 진행 규칙만 적용하고, 인증 복구·공유 쿨다운·불명확한
 * 제출 분류와 execution identity에 결합된 결과 증명은 이 구현에 맡긴다.
 */
@Service
class AutomationBattleSubmission(
    private val battleRunService: BattleRunService,
    private val sessionRecovery: HofSessionRecoveryExecutor,
    private val battleOutcomeReconciler: BattleOutcomeReconciler,
) {
    fun submit(
        accountId: Long,
        executionIdentity: String,
        request: RunBattleRequest,
        source: BattleAutomationActionSource,
    ): AutomationBattleSubmissionResult {
        val response = try {
            runBattle(accountId, request)
        } catch (cooldown: SharedBattleCooldownRejectedException) {
            return AutomationBattleSubmissionResult.SharedCooldown(cooldown.retryAt)
        }
        val proof = exactTerminalProof(accountId, executionIdentity, request, response, source)
        return AutomationBattleSubmissionResult.Completed(response, proof.resultIdentity, proof.outcomes)
    }

    private fun runBattle(accountId: Long, request: RunBattleRequest) =
        sessionRecovery.execute(accountId) {
            try {
                battleRunService.runBattle(accountId, request, HofRequestOrigin.AUTOMATION)
            } catch (error: Exception) {
                generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<SharedBattleCooldownRejectedException>()
                    .firstOrNull()
                    ?.let { throw it }
                generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<HofAutomationDeferredException>()
                    .firstOrNull()
                    ?.let { throw it }
                generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<ApiException>()
                    .firstOrNull()
                    ?.takeIf {
                        it.errorCode in setOf(
                            ErrorCode.HOF_SESSION_EXPIRED,
                            ErrorCode.HOF_LOGIN_FAILED,
                            ErrorCode.CAPTCHA_REQUIRED,
                        )
                    }
                    ?.let { throw it }
                throw AmbiguousAutomationSubmissionException(
                    "Battle submission outcome is not provable; it will not be resent.",
                    error,
                )
            }
        }

    private fun exactTerminalProof(
        accountId: Long,
        executionIdentity: String,
        request: RunBattleRequest,
        response: BattleResultResponse,
        source: BattleAutomationActionSource,
    ): TerminalProof {
        val outcomes = response.rounds.mapNotNull {
            runCatching { BattleAutomationRoundOutcome.valueOf(it.outcome) }.getOrNull()
        }
        if (outcomes.size == request.resolvedBattleCount() && outcomes.all { it.isTerminal() }) {
            return TerminalProof(executionIdentity, outcomes)
        }
        val probe = BattleMapAutomationAction(
            accountId = accountId,
            progressDate = LocalDate.now(),
            categoryId = request.categoryId,
            mapCode = request.mapCode,
            presetMode = app.spammy.hof.automation.entity.PresetSelectionMode.EXPLICIT,
            presetId = null,
            battleCount = request.resolvedBattleCount(),
            executionIdentity = executionIdentity,
            source = source,
        )
        return when (val reconciliation = battleOutcomeReconciler.reloadRecentAuthoritativeEvidence(probe)) {
            is BattleOutcomeReconciliation.Proven -> reconciliation.evidence
                .takeIf { it.binds(probe) && it.isCompleteTerminal() }
                ?.let { TerminalProof(it.resultIdentity, it.outcomes) }
                ?: throw AmbiguousAutomationSubmissionException(
                    "Reloaded battle evidence did not bind the submitted action.",
                )
            is BattleOutcomeReconciliation.Unproven -> throw AmbiguousAutomationSubmissionException(
                "Battle response did not prove every requested terminal round.",
            )
        }
    }

    private fun BattleAutomationRoundOutcome.isTerminal() = this in setOf(
        BattleAutomationRoundOutcome.VICTORY,
        BattleAutomationRoundOutcome.DEFEAT,
        BattleAutomationRoundOutcome.DRAW,
    )

    private data class TerminalProof(
        val resultIdentity: String,
        val outcomes: List<BattleAutomationRoundOutcome>,
    )
}
