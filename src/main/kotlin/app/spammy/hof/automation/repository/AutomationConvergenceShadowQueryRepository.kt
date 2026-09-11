package app.spammy.hof.automation.repository

import app.spammy.hof.automation.convergence.ActionConvergenceResult
import app.spammy.hof.automation.convergence.AutomationIsolationScope
import app.spammy.hof.automation.convergence.QActionConvergenceEntity.actionConvergenceEntity
import app.spammy.hof.automation.convergence.QAutomationActionAttemptEntity.automationActionAttemptEntity
import app.spammy.hof.automation.convergence.QAutomationConvergenceShadowEvaluationEntity
import app.spammy.hof.automation.convergence.QAutomationConvergenceShadowEvaluationEntity.automationConvergenceShadowEvaluationEntity
import app.spammy.hof.automation.convergence.SelectedAutomationAction
import app.spammy.hof.automation.convergence.ShadowConvergenceCheckpoint
import com.querydsl.jpa.JPAExpressions
import com.querydsl.jpa.impl.JPAQueryFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import org.springframework.stereotype.Repository

@Repository
class AutomationConvergenceShadowQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun restore(
        accountId: Long,
        executionIdentityHash: String,
        scope: AutomationIsolationScope,
    ): List<ShadowConvergenceCheckpoint> {
        val shadow = automationConvergenceShadowEvaluationEntity
        val newer = QAutomationConvergenceShadowEvaluationEntity("newerShadow")
        val records = queryFactory.selectFrom(shadow).where(
            shadow.accountId.eq(accountId),
            shadow.successfulObservationCount.isNotNull,
            shadow.executionIdentityHash.eq(executionIdentityHash).or(
                shadow.scopeKind.eq(scope.kind).and(shadow.scopeKeyHash.eq(scope.key))
                    .and(shadow.newResult.`in`(ActionConvergenceResult.PENDING, ActionConvergenceResult.HELD,
                        ActionConvergenceResult.RESULT_UNOBSERVED)),
            ),
            JPAExpressions.selectOne().from(newer).where(
                newer.accountId.eq(shadow.accountId),
                newer.executionIdentityHash.eq(shadow.executionIdentityHash),
                newer.successfulObservationCount.isNotNull,
                newer.recordedSequence.gt(shadow.recordedSequence),
            ).notExists(),
        ).orderBy(shadow.recordedSequence.asc()).fetch()
        if (records.isEmpty()) return emptyList()

        // 해제는 비교 행보다 늦게 발생하고 항목 삭제 뒤에도 남으므로 계정·불변 실행 식별자로 대응한다.
        val released = queryFactory.select(automationActionAttemptEntity.executionIdentity)
            .from(actionConvergenceEntity)
            .join(actionConvergenceEntity.attempt, automationActionAttemptEntity)
            .where(
                actionConvergenceEntity.accountId.eq(accountId),
                actionConvergenceEntity.suppressionReleasedAt.isNotNull,
            ).fetch().mapTo(mutableSetOf()) {
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(it.toByteArray(StandardCharsets.UTF_8)))
            }
        return records.map {
            ShadowConvergenceCheckpoint(
                SelectedAutomationAction(
                    requireNotNull(it.selectionEntryId), it.executionIdentityHash, it.actionKind,
                    AutomationIsolationScope(it.scopeKind, it.scopeKeyHash), it.policyVersion,
                    requireNotNull(it.baselineFingerprintHash), requireNotNull(it.observationOnly),
                ),
                it.newResult, it.newReasonCode, requireNotNull(it.successfulObservationCount),
                it.firstPendingAt, it.submittedAt, it.nextProbeAt, it.finishedAt, requireNotNull(it.checkpointUpdatedAt),
                suppressionReleased = it.executionIdentityHash in released,
            )
        }
    }
}
