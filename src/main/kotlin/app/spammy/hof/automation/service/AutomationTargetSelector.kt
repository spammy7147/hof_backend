package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.AutomationActionConvergenceModule
import app.spammy.hof.automation.convergence.AutomationActionEvidence
import app.spammy.hof.automation.convergence.AutomationConvergenceRollout
import app.spammy.hof.automation.convergence.AutomationConvergenceSelectionGuard
import app.spammy.hof.automation.convergence.AutomationIsolationScope
import app.spammy.hof.automation.convergence.ConvergenceDirective
import app.spammy.hof.automation.convergence.StoredActionConvergenceSelectionFactory
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidDecision
import app.spammy.hof.automation.raid.RaidDirective
import app.spammy.hof.automation.raid.RaidIntent
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.raid.RaidWaitReason
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionView
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

fun interface AutomationDecisionSource {
    fun select(accountId: Long): AutomationCoordination
}

@Service
class AutomationTargetSelector(
    private val typed: TypedAutomationQueryRepository,
    private val work: AutomationWorkSessionQueryRepository,
    private val loader: TypedAutomationSnapshotLoader,
    private val lifecycle: AutomationWorkLifecycle,
    private val timeProvider: TimeProvider,
    private val raidModule: RaidCycleModule,
    private val quest: QuestWorkCycleModule,
    private val battle: AutomationHandler<BattleMapAutomationSnapshot>,
    private val adventure: AutomationHandler<AdventureMapAutomationSnapshot>,
    private val union: AutomationHandler<UnionAutomationSnapshot>,
    private val fishing: AutomationHandler<FishingAutomationSnapshot>,
    private val homeQuest: AutomationHandler<HomeQuestAutomationSnapshot>,
    private val convergenceGuard: AutomationConvergenceSelectionGuard? = null,
    private val convergenceSelectionFactory: StoredActionConvergenceSelectionFactory? = null,
    private val convergenceRollout: AutomationConvergenceRollout? = null,
    private val convergenceModule: AutomationActionConvergenceModule? = null,
    private val progressTelemetry: AutomationProgressTelemetry? = null,
) : AutomationDecisionSource {

    override fun select(accountId: Long): AutomationCoordination {
        val decisionLoader = (loader as? DecisionScopedTypedAutomationSnapshotLoader)
            ?.openDecision(accountId)
            ?: loader
        val runningSession = work.findRunning(accountId)
        if (runningSession?.workType == AutomationWorkType.FISHING) {
            return selectSession(
                accountId = accountId,
                session = runningSession,
                snapshotLoader = decisionLoader,
            )
        }
        runningSession?.let { running ->
            val configuredEntries = typed.findEntries(accountId).orEmpty()
            if (configuredEntries.isNotEmpty()) {
                return selectConfigured(
                    accountId = accountId,
                    configuredEntries = configuredEntries,
                    runningSession = running,
                    snapshotLoader = decisionLoader,
                )
            }
            higherPriorityDueSession(accountId, running)?.let { due ->
                if (lifecycle.handoffForPriority(accountId, running.id, due.id)) {
                    recordDueSessionSafely(accountId, due)
                    return selectSession(
                        accountId = accountId,
                        session = reloadOwner(accountId, due),
                        snapshotLoader = decisionLoader,
                    )
                }
            }
            val higherEntries = typed.findEnabledEntriesBefore(
                accountId = accountId,
                priority = running.entryPriority,
                entryId = running.entryId,
            )
            if (higherEntries.isNotEmpty()) {
                return selectConfigured(
                    accountId = accountId,
                    configuredEntries = higherEntries,
                    fallbackSession = running,
                    snapshotLoader = decisionLoader,
                )
            }
            return selectSession(
                accountId = accountId,
                session = running,
                snapshotLoader = decisionLoader,
            )
        }
        return selectConfigured(
            accountId = accountId,
            snapshotLoader = decisionLoader,
        )
    }

    private fun higherPriorityDueSession(
        accountId: Long,
        running: AutomationWorkSessionView,
    ): AutomationWorkSessionView? {
        if (running.workType == AutomationWorkType.RAID) return null
        val now = timeProvider.now()
        return work.findWaiting(accountId)
            .asSequence()
            .filter { it.entryPriority < running.entryPriority }
            .filter { it.isDueForCheck(now) }
            .minWithOrNull(compareBy(AutomationWorkSessionView::entryPriority, AutomationWorkSessionView::id))
    }

    private fun selectSession(
        accountId: Long,
        session: AutomationWorkSessionView,
        initialWarnings: List<String> = emptyList(),
        initialTrace: List<AutomationEvaluationTrace> = emptyList(),
        evaluatedSessionIds: Set<Long> = emptySet(),
        evaluatedEntryIds: Set<Long> = emptySet(),
        snapshotLoader: TypedAutomationSnapshotLoader = loader,
    ): AutomationCoordination {
        val nextEvaluatedSessionIds = evaluatedSessionIds + session.id
        val nextEvaluatedEntryIds = evaluatedEntryIds + session.entryId
        if (session.workType == AutomationWorkType.RAID) {
            return selectRaidSession(
                accountId,
                session,
                initialWarnings,
                initialTrace,
                nextEvaluatedSessionIds,
                nextEvaluatedEntryIds,
                snapshotLoader,
            )
        }
        val entry = snapshotLoader.loadEntry(accountId, session.entryId, session.targetKey)
            .withQuestWorkSession(session)
        return when (val result = coordinate(accountId, entry)) {
            is AutomationCoordination.Runnable -> {
                result.withPrefix(initialWarnings, initialTrace)
            }
            is AutomationCoordination.Fatal -> result.withPrefix(initialWarnings, initialTrace)
            is AutomationCoordination.Unavailable -> {
                val contextual = result.copy(
                    trace = result.trace.map { item ->
                        if (item.reasonCode == QUEST_PROGRESS_STALE_REASON) {
                            item.copy(
                                targetKey = item.targetKey ?: session.targetKey,
                                workSessionId = session.id,
                                scope = "QUEST:${session.targetKey}",
                            )
                        } else {
                            item
                        }
                    },
                )
                val prefixed = contextual.withPrefix(initialWarnings, initialTrace)
                if (
                    contextual.waitScope == AutomationWaitScope.HOLD_CURRENT_WORK &&
                    session.workType !in setOf(AutomationWorkType.BATTLE_MAP, AutomationWorkType.ADVENTURE_MAP)
                ) {
                    prefixed
                } else {
                    lifecycle.waitForCooldown(accountId, session.id, contextual.nextRunAt)
                    selectConfigured(
                        accountId,
                        initialWarnings + contextual.warnings,
                        initialTrace + contextual.trace.resequenced(initialTrace.size),
                        nextEvaluatedSessionIds,
                        nextEvaluatedEntryIds,
                        snapshotLoader = snapshotLoader,
                    )
                }
            }
            is AutomationCoordination.Idle -> {
                if (result.convergenceBlocked()) {
                    lifecycle.waitForCooldown(
                        accountId,
                        session.id,
                        timeProvider.now().plusSeconds(SCOPE_SUPPRESSION_RECHECK_SECONDS),
                    )
                    return selectConfigured(
                        accountId,
                        initialWarnings + result.warnings,
                        initialTrace + result.trace.resequenced(initialTrace.size),
                        nextEvaluatedSessionIds,
                        nextEvaluatedEntryIds,
                        snapshotLoader = snapshotLoader,
                    )
                }
                result.workTransition?.let {
                    lifecycle.applyTransition(accountId, session.id, it)
                } ?: if (session.workType != AutomationWorkType.QUEST) {
                    lifecycle.applyTransition(accountId, session.id, AutomationWorkTransition.Complete)
                } else Unit
                selectConfigured(
                    accountId,
                    initialWarnings + result.warnings,
                    initialTrace + result.trace.resequenced(initialTrace.size),
                    nextEvaluatedSessionIds,
                    nextEvaluatedEntryIds,
                    snapshotLoader = snapshotLoader,
                )
            }
        }
    }

    private fun selectConfigured(
        accountId: Long,
        initialWarnings: List<String> = emptyList(),
        initialTrace: List<AutomationEvaluationTrace> = emptyList(),
        evaluatedSessionIds: Set<Long> = emptySet(),
        evaluatedEntryIds: Set<Long> = emptySet(),
        configuredEntries: List<AutomationEntryEntity>? = null,
        fallbackSession: AutomationWorkSessionView? = null,
        runningSession: AutomationWorkSessionView? = null,
        snapshotLoader: TypedAutomationSnapshotLoader = loader,
    ): AutomationCoordination {
        val warnings = initialWarnings.toMutableList()
        val trace = initialTrace.toMutableList()
        val evaluatedEntries = evaluatedEntryIds.toMutableSet()
        var earliest: Instant? = null
        val now = timeProvider.now()
        val waitsByEntry = work.findWaiting(accountId).groupBy { it.entryId }
        (configuredEntries ?: typed.findEntries(accountId))
            .asSequence()
            .filter { it.enabled }
            .filter { it.id !in evaluatedEntries }
            .forEach { entry ->
                evaluatedEntries += entry.id
                val waiting = waitsByEntry[entry.id].orEmpty()
                val blockedUntil = waiting.mapNotNull { it.nextCheckAt }.minOrNull()
                val due = waiting.firstOrNull {
                    it.id !in evaluatedSessionIds && it.isDueForCheck(now)
                }
                if (runningSession?.entryId == entry.id) {
                    return selectSession(
                        accountId,
                        runningSession,
                        warnings,
                        trace,
                        evaluatedSessionIds,
                        evaluatedEntries.toSet(),
                        snapshotLoader,
                    )
                }
                if (waiting.isNotEmpty() && due == null) {
                    waiting.mapNotNull(AutomationWorkSessionView::holdMessage).forEach { message ->
                        if (message !in warnings) warnings += message
                    }
                    if (blockedUntil != null && (earliest == null || blockedUntil < earliest)) earliest = blockedUntil
                    if (entry.type != AutomationType.QUEST) return@forEach
                }
                due?.let {
                    val activated = if (runningSession == null) {
                        resumeFresh(accountId, due)
                    } else if (lifecycle.handoffForPriority(accountId, runningSession.id, due.id)) {
                        reloadOwner(accountId, due)
                    } else {
                        null
                    }
                    if (activated == null) return@forEach
                    recordDueSessionSafely(accountId, due)
                    val resumed = selectSession(
                        accountId,
                        activated,
                        warnings,
                        trace,
                        evaluatedSessionIds,
                        evaluatedEntries.toSet(),
                        snapshotLoader,
                    )
                    if (fallbackSession == null || resumed !is AutomationCoordination.Unavailable) {
                        return resumed
                    }
                    return selectSession(
                        accountId = accountId,
                        session = fallbackSession,
                        initialWarnings = resumed.warnings,
                        initialTrace = resumed.trace,
                        evaluatedSessionIds = evaluatedSessionIds + due.id,
                        evaluatedEntryIds = evaluatedEntries.toSet(),
                        snapshotLoader = snapshotLoader,
                    ).withEarlierRetry(earliest ?: resumed.nextRunAt)
                }
                if (entry.type == AutomationType.RAID) {
                    val directive = decideRaid(accountId)
                    when (directive) {
                        is RaidDirective.Execute -> {
                            val action = directive.intent.toPreparedAction(accountId)
                            val block = selectionBlock(accountId, entry.id, action)
                            if (block != null) {
                                warnings += block.message
                                trace += AutomationEvaluationTrace(
                                    trace.size,
                                    entry.id,
                                    entry.type,
                                    AutomationDecisionOutcome.WAITING,
                                    block.reasonCode,
                                    block.message,
                                    observedAt = timeProvider.now(),
                                )
                                return@forEach
                            }
                            trace += directive.toTrace(entry.id, trace.size)
                            return AutomationCoordination.Runnable(
                                entry.id,
                                action,
                                warnings.toList() + listOfNotNull(
                                    directive.warning ?: directive.intent.recoveryWarning(),
                                ),
                                trace.toList(),
                            )
                        }
                        is RaidDirective.WaitUntil -> {
                            trace += directive.toTrace(entry.id, trace.size)
                            lifecycle.waitForRaid(
                                accountId,
                                directive.entryId,
                                directive.raidId,
                                directive.at,
                            )
                            if (earliest == null || directive.at < earliest) earliest = directive.at
                        }
                        is RaidDirective.Hold -> {
                            if (directive.isUserWarning()) warnings += directive.message
                            trace += directive.toTrace(entry.id, trace.size)
                            directive.raidId?.let { raidId ->
                                lifecycle.waitForRaid(
                                    accountId,
                                    directive.entryId ?: entry.id,
                                    raidId,
                                    directive.recheckAt,
                                    directive.message.takeIf { directive.isUserWarning() },
                                )
                            }
                            directive.recheckAt?.let { at ->
                                if (earliest == null || at < earliest) earliest = at
                            }
                        }
                        is RaidDirective.Complete -> trace += directive.toTrace(entry.id, trace.size)
                    }
                    return@forEach
                }
                val snapshot = snapshotLoader.loadEntry(accountId, entry.id).excludingWaitingQuests(
                    waiting.map(AutomationWorkSessionView::targetKey).toSet(),
                )
                when (val result = coordinate(accountId, snapshot)) {
                    is AutomationCoordination.Runnable -> return result.copy(
                        warnings = warnings + result.warnings,
                        trace = trace + result.trace.resequenced(trace.size),
                    )
                    is AutomationCoordination.Fatal -> return result.copy(
                        warnings = warnings + result.warnings,
                        trace = trace + result.trace.resequenced(trace.size),
                    )
                    is AutomationCoordination.Unavailable -> {
                        warnings += result.warnings
                        trace += result.trace.resequenced(trace.size)
                        if (earliest == null || result.nextRunAt < earliest) earliest = result.nextRunAt
                    }
                    is AutomationCoordination.Idle -> {
                        warnings += result.warnings
                        trace += result.trace.resequenced(trace.size)
                    }
                }
            }
        fallbackSession?.let { session ->
            return selectSession(
                accountId = accountId,
                session = session,
                initialWarnings = warnings,
                initialTrace = trace,
                evaluatedSessionIds = evaluatedSessionIds,
                evaluatedEntryIds = evaluatedEntries,
                snapshotLoader = snapshotLoader,
            ).withEarlierRetry(earliest)
        }
        if (runningSession != null && runningSession.entryId !in evaluatedEntries) {
            return selectSession(
                accountId,
                runningSession,
                warnings,
                trace,
                evaluatedSessionIds,
                evaluatedEntries,
                snapshotLoader,
            )
        }
        return earliest?.let { AutomationCoordination.Unavailable(it, warnings, trace) }
            ?: AutomationCoordination.Idle(warnings, trace)
    }

    private fun AutomationCoordination.withEarlierRetry(earlier: Instant?): AutomationCoordination =
        if (this is AutomationCoordination.Unavailable && earlier != null && earlier < nextRunAt) {
            copy(nextRunAt = earlier)
        } else {
            this
        }

    private fun AutomationWorkSessionView.isDueForCheck(now: Instant): Boolean =
        nextCheckAt?.isAfter(now) == false

    private fun resumeFresh(
        accountId: Long,
        parked: AutomationWorkSessionView,
    ): AutomationWorkSessionView {
        lifecycle.resumeForCheck(accountId, parked.id)
        return reloadOwner(accountId, parked)
    }

    private fun reloadOwner(
        accountId: Long,
        expected: AutomationWorkSessionView,
    ): AutomationWorkSessionView {
        return work.findRunning(accountId)?.takeIf { it.id == expected.id }
            ?: throw IllegalStateException(
                "Resumed automation work ${expected.id} is not the current owner for account $accountId.",
            )
    }

    private fun coordinate(
        accountId: Long,
        entry: AutomationEntrySnapshot,
    ): AutomationCoordination {
        val result = evaluateEntry(entry, quest, battle, adventure, union, fishing, homeQuest) { gap ->
            coordinateObservationGap(accountId, entry, gap)
        }
        val runnable = result as? AutomationCoordination.Runnable ?: return result
        val block = selectionBlock(accountId, entry.id, runnable.action) ?: return result
        return AutomationCoordination.Idle(
            warnings = listOf(block.message),
            trace = listOf(
                AutomationEvaluationTrace(
                    sequence = 0,
                    entryId = entry.id,
                    type = entry.type,
                    outcome = AutomationDecisionOutcome.WAITING,
                    reasonCode = block.reasonCode,
                    message = block.message,
                    observedAt = timeProvider.now(),
                ),
            ),
        )
    }

    private fun coordinateObservationGap(
        accountId: Long,
        entry: AutomationEntrySnapshot,
        gap: HandlerEvaluation.ObservationGap,
    ): AutomationCoordination {
        if (convergenceRollout?.active != true) return gap.toUnavailable(entry)
        val module = convergenceModule ?: return gap.toUnavailable(entry)
        val factory = convergenceSelectionFactory ?: return gap.toUnavailable(entry)
        val selection = factory.createObservationGap(
            entryId = entry.id,
            actionKind = gap.actionKind,
            scopeKind = gap.scopeKind,
            scopeKey = gap.scopeKey ?: entry.id.toString(),
            baseline = gap.baseline,
        )
        val directive = module.observeGap(
            accountId,
            selection,
            AutomationActionEvidence.IncompleteObservation(
                capturedAt = timeProvider.now(),
                reason = gap.reasonCode,
                authoritative = gap.authoritative,
            ),
        )
        return when (directive) {
            is ConvergenceDirective.WaitUntil -> gap.toUnavailable(entry, directive.at)
            ConvergenceDirective.ContinueSelection -> AutomationCoordination.Idle(
                warnings = listOf(gap.message),
                trace = listOf(
                    AutomationEvaluationTrace(
                        sequence = 0,
                        entryId = entry.id,
                        type = entry.type,
                        outcome = AutomationDecisionOutcome.WAITING,
                        reasonCode = OBSERVATION_GAP_HELD_REASON,
                        message = "${gap.message} 자동 관측 예산이 끝나 이 범위만 보류했습니다.",
                        actionKind = gap.actionKind.name,
                        targetKey = gap.scopeKey,
                        observedAt = timeProvider.now(),
                    ),
                ),
            )
            is ConvergenceDirective.BattleGateWait -> gap.toUnavailable(entry)
            is ConvergenceDirective.Probe,
            is ConvergenceDirective.Submit,
            -> error("Observation-only convergence returned a submission directive.")
        }
    }

    private fun HandlerEvaluation.ObservationGap.toUnavailable(
        entry: AutomationEntrySnapshot,
        at: Instant = nextRunAt,
    ) = AutomationCoordination.Unavailable(
        nextRunAt = at,
        warnings = emptyList(),
        trace = listOf(
            AutomationEvaluationTrace(
                sequence = 0,
                entryId = entry.id,
                type = entry.type,
                outcome = AutomationDecisionOutcome.WAITING,
                reasonCode = reasonCode,
                message = message,
                nextRunAt = at,
                actionKind = actionKind.name,
                targetKey = scopeKey,
            ),
        ),
        waitScope = AutomationWaitScope.RELEASE_OTHER_AUTOMATIONS,
    )

    private fun selectionBlock(
        accountId: Long,
        entryId: Long,
        action: PreparedAutomationAction,
    ): SelectionBlock? {
        val guard = convergenceGuard ?: return null
        val factory = convergenceSelectionFactory ?: return null
        val preview = factory.preview(entryId, action)
        preview.baselineFingerprint?.let { baselineFingerprint ->
            observeAuthoritativeBaseline(accountId, preview.scope, baselineFingerprint)
        }
        val constraints = guard.constraints(accountId)
        // Captcha gates and terminal legacy baseline suppression are safety controls, not policy rollout decisions.
        if (constraints.battleGateActive && preview.actionKind.battle) {
            return SelectionBlock(CAPTCHA_BATTLE_GATE_REASON, CAPTCHA_BATTLE_GATE_MESSAGE)
        }
        if (
            preview.baselineFingerprint?.let {
                it in constraints.suppressedBaselines[preview.scope].orEmpty()
            } == true
        ) return SelectionBlock(CONVERGENCE_BLOCKED_REASON, CONVERGENCE_BLOCKED_MESSAGE)
        if (convergenceRollout?.active == false) return null
        if (convergenceRollout?.active == true) {
            convergenceModule?.resolveObservationGap(accountId, preview.scope, timeProvider.now())
        }
        return if (guard.constraints(accountId).blocks(preview)) {
            SelectionBlock(CONVERGENCE_BLOCKED_REASON, CONVERGENCE_BLOCKED_MESSAGE)
        } else {
            null
        }
    }

    private fun decideRaid(accountId: Long): RaidDirective {
        val decision = raidModule.decide(accountId)
        observeRaidAuthoritativeState(accountId, decision)
        return decision.directive
    }

    private fun observeRaidAuthoritativeState(accountId: Long, decision: RaidDecision) {
        val state = decision.authoritativeState ?: return
        val baseline = convergenceSelectionFactory?.authoritativeRaidBaseline(state) ?: return
        observeAuthoritativeBaseline(accountId, baseline.scope, baseline.fingerprint)
    }

    private fun observeAuthoritativeBaseline(
        accountId: Long,
        scope: AutomationIsolationScope,
        baselineFingerprint: String,
    ) {
        val released = convergenceModule?.observeAuthoritativeBaseline(
            accountId,
            scope,
            baselineFingerprint,
            timeProvider.now(),
        ) ?: 0
        if (released > 0) {
            log.info(
                "Automation convergence suppression released accountId={} scopeKind={} scopeKey={} count={}",
                accountId,
                scope.kind,
                scope.key,
                released,
            )
        }
    }

    private fun AutomationCoordination.Idle.convergenceBlocked(): Boolean =
        trace.any {
            it.reasonCode in setOf(
                CONVERGENCE_BLOCKED_REASON,
                OBSERVATION_GAP_HELD_REASON,
                CAPTCHA_BATTLE_GATE_REASON,
            )
        }

    private fun recordDueSessionSafely(accountId: Long, due: AutomationWorkSessionView) {
        try {
            progressTelemetry?.recordDueSession(due.workType, due.nextCheckAt)
        } catch (error: RuntimeException) {
            log.warn(
                "Automation due-session telemetry failed accountId={} sessionId={} workType={}",
                accountId,
                due.id,
                due.workType,
                error,
            )
        }
    }

    private fun selectRaidSession(
        accountId: Long,
        session: AutomationWorkSessionView,
        initialWarnings: List<String>,
        initialTrace: List<AutomationEvaluationTrace>,
        evaluatedSessionIds: Set<Long>,
        evaluatedEntryIds: Set<Long>,
        snapshotLoader: TypedAutomationSnapshotLoader,
    ): AutomationCoordination {
        val directive = decideRaid(accountId)
        return when (directive) {
            is RaidDirective.Execute -> {
                val action = directive.intent.toPreparedAction(accountId)
                val block = selectionBlock(accountId, session.entryId, action)
                if (block != null) {
                    lifecycle.waitForCooldown(
                        accountId,
                        session.id,
                        timeProvider.now().plusSeconds(SCOPE_SUPPRESSION_RECHECK_SECONDS),
                    )
                    selectConfigured(
                        accountId,
                        initialWarnings + block.message,
                        initialTrace + AutomationEvaluationTrace(
                            initialTrace.size,
                            session.entryId,
                            AutomationType.RAID,
                            AutomationDecisionOutcome.WAITING,
                            block.reasonCode,
                            block.message,
                            observedAt = timeProvider.now(),
                        ),
                        evaluatedSessionIds,
                        evaluatedEntryIds,
                        snapshotLoader = snapshotLoader,
                    )
                } else {
                    AutomationCoordination.Runnable(
                        session.entryId,
                        action,
                        initialWarnings + listOfNotNull(directive.warning ?: directive.intent.recoveryWarning()),
                        initialTrace + directive.toTrace(session.entryId, initialTrace.size),
                    )
                }
            }
            is RaidDirective.WaitUntil -> {
                lifecycle.waitForCooldown(accountId, session.id, directive.at)
                selectConfigured(
                    accountId,
                    initialWarnings,
                    initialTrace + directive.toTrace(session.entryId, initialTrace.size),
                    evaluatedSessionIds,
                    evaluatedEntryIds,
                    snapshotLoader = snapshotLoader,
                )
            }
            is RaidDirective.Hold -> {
                lifecycle.waitForRaid(
                    accountId,
                    directive.entryId ?: session.entryId,
                    directive.raidId ?: session.targetKey,
                    directive.recheckAt,
                    directive.message.takeIf { directive.isUserWarning() },
                )
                selectConfigured(
                    accountId,
                    initialWarnings + listOfNotNull(directive.message.takeIf { directive.isUserWarning() }),
                    initialTrace + directive.toTrace(session.entryId, initialTrace.size),
                    evaluatedSessionIds,
                    evaluatedEntryIds,
                    snapshotLoader = snapshotLoader,
                )
            }
            is RaidDirective.Complete -> {
                lifecycle.applyTransition(accountId, session.id, AutomationWorkTransition.Complete)
                selectConfigured(
                    accountId,
                    initialWarnings,
                    initialTrace + directive.toTrace(session.entryId, initialTrace.size),
                    evaluatedSessionIds,
                    evaluatedEntryIds,
                    snapshotLoader = snapshotLoader,
                )
            }
        }
    }

    private fun RaidDirective.toTrace(entryId: Long, sequence: Int): AutomationEvaluationTrace = when (this) {
        is RaidDirective.Execute -> AutomationEvaluationTrace(
            sequence = sequence,
            entryId = entryId,
            type = AutomationType.RAID,
            outcome = AutomationDecisionOutcome.SELECTED,
            reasonCode = reasonCode ?: if ((intent as? RaidIntent.Battle)?.recoveryChainId != null) {
                "RAID_BATTLE_RETRANSMIT"
            } else {
                "RUNNABLE"
            },
            message = message ?: "레이드 자동화 단계를 실행합니다.",
        )
        is RaidDirective.WaitUntil -> AutomationEvaluationTrace(
            sequence,
            entryId,
            AutomationType.RAID,
            AutomationDecisionOutcome.WAITING,
            reasonCode ?: reason.name,
            message,
            at,
            actionKind = "WAIT",
            targetKey = raidId,
            diagnosticKind = waitDiagnosticKind(),
            cooldownSource = cooldownSource,
            impactScope = impactScope,
            releaseCondition = releaseCondition,
        )
        is RaidDirective.Hold -> AutomationEvaluationTrace(
            sequence,
            entryId,
            AutomationType.RAID,
            if (recheckAt == null) AutomationDecisionOutcome.CONFIGURATION_WARNING else AutomationDecisionOutcome.WAITING,
            reasonCode ?: reason.name,
            message,
            recheckAt,
            actionKind = "HOLD",
            targetKey = raidId,
            diagnosticKind = diagnosticKind,
            impactScope = impactScope,
            releaseCondition = releaseCondition,
        )
        is RaidDirective.Complete -> AutomationEvaluationTrace(
            sequence,
            entryId,
            AutomationType.RAID,
            if (outcome.kind == RaidCycleOutcomeKind.COMPLETED) {
                AutomationDecisionOutcome.CYCLE_COMPLETED
            } else {
                AutomationDecisionOutcome.CYCLE_ABORTED
            },
            reasonCode ?: outcome.kind.name,
            message ?: "레이드 사이클을 ${outcome.kind.name} 상태로 마쳤습니다.",
            targetKey = outcome.raidId,
        )
    }

    private fun RaidDirective.WaitUntil.waitDiagnosticKind(): AutomationDiagnosticKind? = when (reason) {
        RaidWaitReason.BATTLE_COOLDOWN,
        RaidWaitReason.BATTLE_APPLIED_COOLDOWN -> when (cooldownSource) {
            app.spammy.hof.automation.raid.RaidCooldownSource.HOF_DIRECT ->
                AutomationDiagnosticKind.RAID_HOF_COOLDOWN
            app.spammy.hof.automation.raid.RaidCooldownSource.HOF_SINGLE_TARGET_INFERENCE ->
                AutomationDiagnosticKind.RAID_SINGLE_TARGET_TIMER
            app.spammy.hof.automation.raid.RaidCooldownSource.LOCAL_FALLBACK ->
                AutomationDiagnosticKind.RAID_LOCAL_SAFETY_GATE
            app.spammy.hof.automation.raid.RaidCooldownSource.DEPLOYMENT_FALLBACK ->
                AutomationDiagnosticKind.RAID_DEPLOYMENT_SAFETY_GATE
            null -> AutomationDiagnosticKind.RAID_EXPLICIT_COOLDOWN_WAIT
        }
        RaidWaitReason.BATTLE_RECOVERY_RECHECK ->
            AutomationDiagnosticKind.RAID_COOLDOWN_OBSERVATION_AMBIGUOUS
        RaidWaitReason.REWARD_CONFIRMATION ->
            AutomationDiagnosticKind.RAID_REWARD_CONFIRMATION_WAIT
        RaidWaitReason.POST_REWARD_CHECK ->
            AutomationDiagnosticKind.RAID_REWARD_RESULT_RECHECK
        RaidWaitReason.REGISTRATION_COOLDOWN,
        RaidWaitReason.WAITING_TO_START -> null
    }

    private fun RaidDirective.Hold.isUserWarning(): Boolean =
        recheckAt == null ||
            reason == app.spammy.hof.automation.raid.RaidHoldReason.MANUAL_RAID_ACTIVE ||
            diagnosticKind in setOf(
                AutomationDiagnosticKind.RAID_BATTLE_RESULT_UNKNOWN,
                AutomationDiagnosticKind.RAID_COOLDOWN_OBSERVATION_HELD,
                AutomationDiagnosticKind.RAID_REWARD_OBSERVATION_HELD,
                AutomationDiagnosticKind.RAID_REWARD_RESULT_HELD,
            )

    private fun List<AutomationEvaluationTrace>.resequenced(offset: Int): List<AutomationEvaluationTrace> =
        mapIndexed { index, item -> item.copy(sequence = offset + index) }

    private fun AutomationCoordination.withPrefix(
        warnings: List<String>,
        trace: List<AutomationEvaluationTrace>,
    ): AutomationCoordination = when (this) {
        is AutomationCoordination.Runnable -> copy(
            warnings = warnings + this.warnings,
            trace = trace + this.trace.resequenced(trace.size),
        )
        is AutomationCoordination.Fatal -> copy(
            warnings = warnings + this.warnings,
            trace = trace + this.trace.resequenced(trace.size),
        )
        is AutomationCoordination.Unavailable -> copy(
            warnings = warnings + this.warnings,
            trace = trace + this.trace.resequenced(trace.size),
        )
        is AutomationCoordination.Idle -> copy(
            warnings = warnings + this.warnings,
            trace = trace + this.trace.resequenced(trace.size),
        )
    }

    private fun RaidIntent.toPreparedAction(accountId: Long): PreparedAutomationAction = when (this) {
        is RaidIntent.Town -> RaidTownAutomationAction(
            accountId = accountId,
            action = kind.toTownAction(),
            raidId = requestRaidId,
            targetRaidId = raidId,
            raidName = raidName,
            observedStatus = observedStatus,
        )
        is RaidIntent.Battle -> BattleMapAutomationAction(
            accountId = accountId,
            progressDate = timeProvider.now().atZone(SEOUL).toLocalDate(),
            categoryId = categoryId,
            mapCode = mapCode,
            presetMode = presetMode,
            presetId = presetId,
            battleCount = 1,
            executionIdentity = UUID.randomUUID().toString(),
            source = BattleAutomationActionSource.RAID_AUTOMATION,
            resolvedParty = party,
            mapName = raidName,
            sourceTargetKey = raidId,
            recoveryChainId = recoveryChainId,
            raidRetransmissionCount = retransmissionCount,
            raidSubmittedFromRunnable = submittedFromRunnable,
        )
    }

    private fun RaidIntent.recoveryWarning(): String? =
        (this as? RaidIntent.Battle)?.takeIf { it.recoveryChainId != null }?.let {
            "레이드 전투 결과 복구 중 · 재전송 ${it.retransmissionCount}회 실행"
        }

    private fun RaidIntentKind.toTownAction() = when (this) {
        RaidIntentKind.RESET -> app.spammy.hof.town.raid.model.RaidAction.RESET
        RaidIntentKind.REGISTER -> app.spammy.hof.town.raid.model.RaidAction.REGISTER
        RaidIntentKind.START -> app.spammy.hof.town.raid.model.RaidAction.START
        RaidIntentKind.REWARD -> app.spammy.hof.town.raid.model.RaidAction.REWARD
        RaidIntentKind.REFRESH -> app.spammy.hof.town.raid.model.RaidAction.REFRESH
        RaidIntentKind.BATTLE -> error("Battle intents use the common battle action.")
    }

    private fun AutomationEntrySnapshot.excludingWaitingQuests(
        targetKeys: Set<String>,
    ): AutomationEntrySnapshot {
        if (type != AutomationType.QUEST || targetKeys.isEmpty()) return this
        return copy(
            quest = quest?.copy(
                selections = quest.selections.filterNot { it.questKey in targetKeys },
            ),
        )
    }

    private fun AutomationEntrySnapshot.withQuestWorkSession(
        session: AutomationWorkSessionView,
    ): AutomationEntrySnapshot {
        if (session.workType != AutomationWorkType.QUEST) return this
        return copy(
            quest = quest?.copy(
                workSessionId = session.id,
                workSessionRevision = session.revision,
            ),
        )
    }

    private data class SelectionBlock(
        val reasonCode: String,
        val message: String,
    )

    private companion object {
        val log = LoggerFactory.getLogger(AutomationTargetSelector::class.java)
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        const val CONVERGENCE_BLOCKED_REASON = "CONVERGENCE_SCOPE_BLOCKED"
        const val CONVERGENCE_BLOCKED_MESSAGE = "이전 행동 결과를 확인 중이라 해당 범위만 잠시 건너뜁니다."
        const val CAPTCHA_BATTLE_GATE_REASON = "CAPTCHA_BATTLE_GATE_BLOCKED"
        const val CAPTCHA_BATTLE_GATE_MESSAGE = "캡차 해결 전까지 전투 범위만 잠시 건너뜁니다."
        const val OBSERVATION_GAP_HELD_REASON = "OBSERVATION_GAP_HELD"
        const val QUEST_PROGRESS_STALE_REASON = "QUEST_PROGRESS_STALE"
        const val SCOPE_SUPPRESSION_RECHECK_SECONDS = 30L
    }
}

private fun evaluateEntry(
    entry: AutomationEntrySnapshot,
    quest: QuestWorkCycleModule,
    battle: AutomationHandler<BattleMapAutomationSnapshot>,
    adventure: AutomationHandler<AdventureMapAutomationSnapshot>,
    union: AutomationHandler<UnionAutomationSnapshot>,
    fishing: AutomationHandler<FishingAutomationSnapshot>,
    homeQuest: AutomationHandler<HomeQuestAutomationSnapshot>,
    observationGap: ((HandlerEvaluation.ObservationGap) -> AutomationCoordination)? = null,
): AutomationCoordination {
    val evaluation = when (entry.type) {
        AutomationType.QUEST -> entry.quest?.let { quest.decideNext(it).toEntryEvaluation() }
        AutomationType.HOME_QUEST -> entry.homeQuest?.let(homeQuest::evaluate)
        AutomationType.BATTLE_MAP -> entry.battle?.let(battle::evaluate)
        AutomationType.ADVENTURE_MAP -> entry.adventure?.let(adventure::evaluate)
        AutomationType.RAID -> HandlerEvaluation.Skipped
        AutomationType.UNION -> entry.union?.let(union::evaluate)
        AutomationType.FISHING -> entry.fishing?.let(fishing::evaluate)
    } ?: HandlerEvaluation.ConfigurationWarning("${entry.type} automation snapshot is missing.")
    val trace = when (evaluation) {
        is HandlerEvaluation.Runnable -> AutomationEvaluationTrace(
            0,
            entry.id,
            entry.type,
            AutomationDecisionOutcome.SELECTED,
            "RUNNABLE",
            "자동화 행동을 선택했습니다.",
        )
        is HandlerEvaluation.Fatal -> AutomationEvaluationTrace(
            0,
            entry.id,
            entry.type,
            AutomationDecisionOutcome.FATAL,
            evaluation.reason.name,
            evaluation.message,
        )
        is HandlerEvaluation.ConfigurationWarning -> AutomationEvaluationTrace(
            0,
            entry.id,
            entry.type,
            AutomationDecisionOutcome.CONFIGURATION_WARNING,
            evaluation.reasonCode,
            evaluation.message,
        )
        is HandlerEvaluation.Unavailable -> {
            val detail = entry.waitingEntryTrace(evaluation)
            AutomationEvaluationTrace(
                0,
                entry.id,
                entry.type,
                AutomationDecisionOutcome.WAITING,
                evaluation.reasonCode,
                detail?.message ?: evaluation.message,
                evaluation.nextRunAt,
                detail?.actionKind,
                detail?.targetKey,
                detail?.targetName,
                detail?.presetId,
            )
        }
        is HandlerEvaluation.WorkTransition -> AutomationEvaluationTrace(
            sequence = 0,
            entryId = entry.id,
            type = entry.type,
            outcome = when (evaluation.transition) {
                AutomationWorkTransition.Complete -> AutomationDecisionOutcome.CYCLE_COMPLETED
                is AutomationWorkTransition.WaitForConfiguration ->
                    AutomationDecisionOutcome.CONFIGURATION_WARNING
                is AutomationWorkTransition.WaitForResource,
                AutomationWorkTransition.WaitForUnknownCooldown,
                -> AutomationDecisionOutcome.WAITING
            },
            reasonCode = evaluation.reasonCode,
            message = evaluation.message,
            actionKind = if (evaluation.transition == AutomationWorkTransition.Complete) "COMPLETE" else "WAIT",
        )
        is HandlerEvaluation.ObservationGap -> AutomationEvaluationTrace(
            0,
            entry.id,
            entry.type,
            AutomationDecisionOutcome.WAITING,
            evaluation.reasonCode,
            evaluation.message,
            evaluation.nextRunAt,
            evaluation.actionKind.name,
            evaluation.scopeKey,
        )
        HandlerEvaluation.Skipped -> AutomationEvaluationTrace(
            0,
            entry.id,
            entry.type,
            AutomationDecisionOutcome.SKIPPED,
            HandlerEvaluation.Skipped.reasonCode,
            HandlerEvaluation.Skipped.message,
        )
    }
    return when (evaluation) {
        is HandlerEvaluation.Runnable -> AutomationCoordination.Runnable(
            entry.id,
            evaluation.action,
            emptyList(),
            listOf(trace),
        )
        is HandlerEvaluation.Fatal -> AutomationCoordination.Fatal(
            evaluation.reason,
            evaluation.message,
            emptyList(),
            listOf(trace),
        )
        is HandlerEvaluation.ConfigurationWarning -> AutomationCoordination.Idle(
            listOf(evaluation.message),
            listOf(trace),
        )
        is HandlerEvaluation.Unavailable -> AutomationCoordination.Unavailable(
            evaluation.nextRunAt,
            emptyList(),
            listOf(trace),
            evaluation.waitScope,
        )
        is HandlerEvaluation.WorkTransition -> AutomationCoordination.Idle(
            warnings = if (evaluation.transition is AutomationWorkTransition.WaitForConfiguration) {
                listOf(evaluation.message)
            } else {
                emptyList()
            },
            trace = listOf(trace),
            workTransition = evaluation.transition,
        )
        is HandlerEvaluation.ObservationGap -> observationGap?.invoke(evaluation)
            ?: AutomationCoordination.Unavailable(
                evaluation.nextRunAt,
                emptyList(),
                listOf(trace),
                AutomationWaitScope.RELEASE_OTHER_AUTOMATIONS,
            )
        HandlerEvaluation.Skipped -> AutomationCoordination.Idle(emptyList(), listOf(trace))
    }
}

private fun QuestDirective.toEntryEvaluation(): HandlerEvaluation = when (this) {
    is QuestDirective.Execute -> HandlerEvaluation.Runnable(action)
    is QuestDirective.WaitUntil -> HandlerEvaluation.Unavailable(nextRunAt, reasonCode, message)
    is QuestDirective.Recheck -> HandlerEvaluation.Unavailable(
        at,
        reasonCode,
        message,
        AutomationWaitScope.RELEASE_OTHER_AUTOMATIONS,
    )
    is QuestDirective.WaitForResource -> HandlerEvaluation.WorkTransition(
        AutomationWorkTransition.WaitForResource(resourceName, missingCount),
        "QUEST_RESOURCE_WAIT",
        "퀘스트 완료에 필요한 재료를 기다립니다.",
    )
    QuestDirective.WaitForUnknownCooldown -> HandlerEvaluation.WorkTransition(
        AutomationWorkTransition.WaitForUnknownCooldown,
        "QUEST_COOLDOWN_UNKNOWN",
        "반복 퀘스트의 다음 시작 가능 상태를 기다립니다.",
    )
    is QuestDirective.WaitForConfiguration -> HandlerEvaluation.WorkTransition(
        AutomationWorkTransition.WaitForConfiguration(message),
        reasonCode,
        message,
    )
    QuestDirective.CompleteWork -> HandlerEvaluation.WorkTransition(
        AutomationWorkTransition.Complete,
        "QUEST_WORK_COMPLETE",
        "현재 퀘스트 작업 사이클을 완료했습니다.",
    )
    is QuestDirective.Hold -> HandlerEvaluation.ConfigurationWarning(message, reasonCode)
    is QuestDirective.Fatal -> HandlerEvaluation.Fatal(reason, message)
    QuestDirective.Skip -> HandlerEvaluation.Skipped
}

private fun AutomationEntrySnapshot.waitingEntryTrace(
    evaluation: HandlerEvaluation.Unavailable,
): EntryWaitingTrace? {
    fishing?.let { snapshot ->
        val observations = listOfNotNull(
            snapshot.state.primaryAction.name.let { "현재 동작 $it" },
            snapshot.state.remainingCasts?.let { "남은 낚시 ${it}회" },
            snapshot.state.escapeSeconds?.let { "도망까지 ${it}초" },
            snapshot.state.lastOutcome?.name?.let { "직전 결과 $it" },
        ).joinToString(" · ")
        return EntryWaitingTrace(
            actionKind = "WAIT",
            message = "${evaluation.message}${if (observations.isBlank()) "" else " · $observations"}",
        )
    }
    union?.let { snapshot ->
        val target = snapshot.settings.sortedBy(UnionAutomationSetting::executionOrder).firstOrNull()
        return EntryWaitingTrace(
            actionKind = "WAIT",
            message = "${evaluation.message} · 설정 맵 ${snapshot.settings.size}개 · 현재 순환 기준 ${snapshot.currentTargetKey ?: "첫 대상"}",
            targetKey = target?.let { "${it.categoryId}/${it.mapCode}" },
            presetId = target?.presetId,
        )
    }
    return null
}

private data class EntryWaitingTrace(
    val actionKind: String,
    val message: String,
    val targetKey: String? = null,
    val targetName: String? = null,
    val presetId: Long? = null,
)
