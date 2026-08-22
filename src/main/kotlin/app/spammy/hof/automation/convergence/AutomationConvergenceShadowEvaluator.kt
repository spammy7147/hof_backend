package app.spammy.hof.automation.convergence

import app.spammy.hof.common.time.TimeProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

enum class LegacyConvergenceDecision {
    APPLIED,
    RECONCILING,
    RESUBMIT,
    HELD,
    RESULT_UNOBSERVED,
}

data class ShadowConvergenceEvaluation(
    val actionKind: AutomationActionKind,
    val evidenceKind: String,
    val legacyDecision: LegacyConvergenceDecision,
    val newResult: ActionConvergenceResult,
    val differs: Boolean,
)

data class ShadowConvergenceAggregate(
    val actionKind: AutomationActionKind,
    val evidenceKind: String,
    val legacyDecision: LegacyConvergenceDecision,
    val newResult: ActionConvergenceResult,
    val differs: Boolean,
    val count: Long,
)

interface AutomationConvergenceShadowEvaluator {
    fun selected(accountId: Long, selection: SelectedAutomationAction)

    fun observe(
        accountId: Long,
        executionIdentity: String,
        evidence: AutomationActionEvidence,
        legacyDecision: LegacyConvergenceDecision,
    ): ShadowConvergenceEvaluation?

    fun snapshot(): List<ShadowConvergenceAggregate>
}

/**
 * production runtime/store와 분리된 메모리 evaluator다. 실제 실행이 만든 evidence만 소비하고
 * HOF 요청, runtime 전이, production convergence row를 만들지 않는다.
 */
@Service
class DefaultAutomationConvergenceShadowEvaluator(
    timeProvider: TimeProvider,
) : AutomationConvergenceShadowEvaluator {
    private val log = LoggerFactory.getLogger(javaClass)
    private val store = InMemoryConvergenceStore()
    private val engine = DefaultAutomationActionConvergenceModule(store, timeProvider)
    private val attempts = ConcurrentHashMap<Pair<Long, String>, Long>()
    private val aggregate = ConcurrentHashMap<ShadowKey, AtomicLong>()

    override fun selected(accountId: Long, selection: SelectedAutomationAction) {
        val directive = engine.prepare(accountId, selection)
        if (directive is ConvergenceDirective.Submit) {
            attempts[accountId to selection.executionIdentity] = directive.attemptId
        }
    }

    override fun observe(
        accountId: Long,
        executionIdentity: String,
        evidence: AutomationActionEvidence,
        legacyDecision: LegacyConvergenceDecision,
    ): ShadowConvergenceEvaluation? {
        val key = accountId to executionIdentity
        val attemptId = attempts[key] ?: return null
        val before = store.get(attemptId) ?: return null
        if (!before.active) return null
        engine.record(attemptId, evidence)
        val after = requireNotNull(store.get(attemptId))
        val newResult = after.result ?: ActionConvergenceResult.PENDING
        val evidenceKind = evidence.javaClass.simpleName
        val differs = legacyDecision.expectedNewResult() != newResult
        val shadowKey = ShadowKey(before.selection.actionKind, evidenceKind, legacyDecision, newResult, differs)
        val count = aggregate.computeIfAbsent(shadowKey) { AtomicLong() }.incrementAndGet()
        log.info(
            "automation_convergence_shadow actionKind={} evidenceKind={} legacyDecision={} newResult={} differs={} count={}",
            before.selection.actionKind,
            evidenceKind,
            legacyDecision,
            newResult,
            differs,
            count,
        )
        if (!after.active) attempts.remove(key, attemptId)
        return ShadowConvergenceEvaluation(before.selection.actionKind, evidenceKind, legacyDecision, newResult, differs)
    }

    override fun snapshot(): List<ShadowConvergenceAggregate> = aggregate.entries.map { (key, value) ->
        ShadowConvergenceAggregate(
            key.actionKind,
            key.evidenceKind,
            key.legacyDecision,
            key.newResult,
            key.differs,
            value.get(),
        )
    }.sortedWith(compareBy({ it.actionKind.name }, { it.evidenceKind }, { it.legacyDecision.name }, { it.newResult.name }))

    private fun LegacyConvergenceDecision.expectedNewResult(): ActionConvergenceResult = when (this) {
        LegacyConvergenceDecision.APPLIED -> ActionConvergenceResult.APPLIED
        LegacyConvergenceDecision.RECONCILING -> ActionConvergenceResult.PENDING
        LegacyConvergenceDecision.RESUBMIT -> ActionConvergenceResult.NOT_APPLIED
        LegacyConvergenceDecision.HELD -> ActionConvergenceResult.HELD
        LegacyConvergenceDecision.RESULT_UNOBSERVED -> ActionConvergenceResult.RESULT_UNOBSERVED
    }

    private data class ShadowKey(
        val actionKind: AutomationActionKind,
        val evidenceKind: String,
        val legacyDecision: LegacyConvergenceDecision,
        val newResult: ActionConvergenceResult,
        val differs: Boolean,
    )
}
