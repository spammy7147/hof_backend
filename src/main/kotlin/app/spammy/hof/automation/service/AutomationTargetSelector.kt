package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.AutomationConvergenceSelection
import app.spammy.hof.automation.convergence.AutomationActionConvergenceModule
import app.spammy.hof.automation.convergence.AutomationConvergenceRollout
import app.spammy.hof.automation.convergence.ConvergenceDirective
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.history.AutomationDecisionJournal
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
import app.spammy.hof.town.raid.model.RaidAction
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
    private val convergenceRollout: AutomationConvergenceRollout? = null,
    private val convergenceModule: AutomationActionConvergenceModule? = null,
    private val progressTelemetry: AutomationProgressTelemetry? = null,
    private val decisionJournal: AutomationDecisionJournal? = null,
) : AutomationDecisionSource {

    override fun select(accountId: Long): AutomationCoordination {
        val decisionLoader = (loader as? DecisionScopedTypedAutomationSnapshotLoader)
            ?.openDecision(accountId)
            ?: loader
        val runningSession = work.findRunning(accountId)
        if (runningSession != null) {
            return selectSession(
                accountId = accountId,
                session = runningSession,
                snapshotLoader = decisionLoader,
            )
        }
        return selectConfigured(
            accountId = accountId,
            snapshotLoader = decisionLoader,
        )
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
        return when (val result = loadAndCoordinate(
            accountId, session.entryId, AutomationType.valueOf(session.workType.name),
            snapshotLoader, initialTrace, session,
        )) {
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
                    AutomationCoordination.CycleBoundary(
                        warnings = prefixed.warnings,
                        trace = prefixed.trace,
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
                    val prefixed = result.withPrefix(initialWarnings, initialTrace)
                    return AutomationCoordination.CycleBoundary(
                        warnings = prefixed.warnings,
                        trace = prefixed.trace,
                    )
                }
                result.workTransition?.let {
                    lifecycle.applyTransition(accountId, session.id, it)
                } ?: if (session.workType != AutomationWorkType.QUEST) {
                    lifecycle.applyTransition(accountId, session.id, AutomationWorkTransition.Complete)
                } else {
                    lifecycle.yieldForPriority(accountId, session.id)
                }
                val prefixed = result.withPrefix(initialWarnings, initialTrace)
                AutomationCoordination.CycleBoundary(
                    warnings = prefixed.warnings,
                    trace = prefixed.trace,
                )
            }
            is AutomationCoordination.CycleBoundary -> result.withPrefix(initialWarnings, initialTrace)
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
                    if (entry.type !in CANDIDATE_ARBITRATED_TYPES) {
                        val parked = waiting.minBy { it.nextCheckAt ?: Instant.MAX }
                        trace += AutomationEvaluationTrace(
                            sequence = trace.size,
                            entryId = entry.id,
                            type = entry.type,
                            outcome = AutomationDecisionOutcome.SKIPPED,
                            reasonCode = if (blockedUntil != null) "WORK_RECHECK_NOT_DUE" else "WORK_RECHECK_UNSCHEDULED",
                            message = parked.holdMessage ?: if (blockedUntil != null) {
                                "다음 확인 시각 전이라 이번 판단에서 건너뜁니다."
                            } else {
                                "현재 작업의 재확인 조건이 충족되지 않아 이번 판단에서 건너뜁니다."
                            },
                            nextRunAt = blockedUntil,
                            targetKey = parked.targetKey,
                            workSessionId = parked.id,
                            observedAt = now,
                            diagnosticContext = AutomationDecisionDiagnostics.capture(
                                "WORK_RECHECK_GATE", now,
                                workSessionId = parked.id, targetKey = parked.targetKey,
                            ),
                        )
                        return@forEach
                    }
                }
                if (entry.type !in CANDIDATE_ARBITRATED_TYPES) {
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
                }
                if (entry.type == AutomationType.RAID) {
                    val decision = decideRaid(accountId, entry.id, trace)
                    val directive = decision.directive
                    val diagnosticContext = AutomationDecisionDiagnostics.capture(
                        "RAID_EVALUATION", now, raid = decision.authoritativeState,
                    )
                    when (directive) {
                        is RaidDirective.Execute -> {
                            val action = directive.intent.toPreparedAction(accountId)
                            val block = convergenceModule?.openSelection(accountId, entry.id, mode = convergenceRollout?.mode)?.block(action)
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
                                    targetKey = block.scope.key,
                                    scope = block.scope.kind.name,
                                    diagnosticContext = diagnosticContext,
                                )
                                return@forEach
                            }
                            trace += directive.toTrace(entry.id, trace.size, diagnosticContext)
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
                            trace += directive.toTrace(entry.id, trace.size, diagnosticContext)
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
                            trace += directive.toTrace(entry.id, trace.size, diagnosticContext)
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
                        is RaidDirective.Complete -> trace += directive.toTrace(entry.id, trace.size, diagnosticContext)
                    }
                    return@forEach
                }
                when (val result = loadAndCoordinate(accountId, entry.id, entry.type, snapshotLoader, trace)) {
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
                        result.nextRunAt?.let { at ->
                            if (earliest == null || at < earliest) earliest = at
                        }
                    }
                    is AutomationCoordination.CycleBoundary -> return result.copy(
                        warnings = warnings + result.warnings,
                        trace = trace + result.trace.resequenced(trace.size),
                    )
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
        return AutomationCoordination.Idle(warnings, trace)
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

    private fun loadAndCoordinate(
        accountId: Long,
        entryId: Long,
        type: AutomationType,
        snapshotLoader: TypedAutomationSnapshotLoader,
        previousTrace: List<AutomationEvaluationTrace>,
        session: AutomationWorkSessionView? = null,
    ): AutomationCoordination {
        var snapshot: AutomationEntrySnapshot? = null
        var stage = "SNAPSHOT_LOAD"
        try {
            val loaded = snapshotLoader.loadEntry(accountId, entryId, session?.targetKey)
            snapshot = session?.let { loaded.withWorkSession(it) } ?: loaded
            stage = "ENTRY_EVALUATION"
            val result = coordinate(accountId, snapshot)
            val trace = result.trace.map { original ->
                val item = if (session == null && type in CANDIDATE_ARBITRATED_TYPES &&
                    original.outcome == AutomationDecisionOutcome.WAITING
                ) original.copy(outcome = AutomationDecisionOutcome.SKIPPED) else original
                if (type == AutomationType.FISHING || item.outcome in DIAGNOSTIC_OUTCOMES || item.excludedCandidates.isNotEmpty()) item.copy(
                    diagnosticContext = AutomationDecisionDiagnostics.capture(
                        stage, timeProvider.now(), snapshot, workSessionId = session?.id,
                        targetKey = item.targetKey ?: session?.targetKey, scope = item.scope,
                        excludedCandidates = item.excludedCandidates,
                    ),
                ) else item
            }
            return result.withTrace(trace)
        } catch (error: Exception) {
            recordSelectionFailure(accountId, entryId, type, stage, previousTrace, error, snapshot, session)
            throw error
        }
    }

    private fun recordSelectionFailure(
        accountId: Long,
        entryId: Long,
        type: AutomationType,
        stage: String,
        previousTrace: List<AutomationEvaluationTrace>,
        error: Exception,
        snapshot: AutomationEntrySnapshot? = null,
        session: AutomationWorkSessionView? = null,
    ) {
        // 요청 간격 조절과 설정 변경은 실패가 아니며 기존 재판단 경로를 그대로 따른다.
        if (error is TypedAutomationConfigurationChangedException ||
            error is app.spammy.hof.external.client.HofAutomationDeferredException
        ) return
        var diagnosticContext: String? = null
        try {
            diagnosticContext = AutomationDecisionDiagnostics.capture(
                stage, timeProvider.now(), snapshot, error = error, workSessionId = session?.id,
                targetKey = session?.targetKey,
            )
            val trace = AutomationEvaluationTrace(
                sequence = previousTrace.size, entryId = entryId, type = type,
                outcome = AutomationDecisionOutcome.FATAL,
                reasonCode = "${stage}_FAILED",
                message = "자동화 상태 조회 또는 판단 중 오류가 발생했습니다. 당시 진단 정보를 저장했습니다.",
                targetKey = session?.targetKey,
                diagnosticContext = diagnosticContext,
            )
            // 선택 전 실패 이력이 전체 자동화 중지를 뜻하지는 않는다. 복구 여부는 runner가 결정한다.
            decisionJournal?.appendDecision(accountId, AutomationCoordination.Idle(emptyList(), previousTrace + trace))
        } catch (recordingError: Exception) {
            // 진단 저장 실패가 원래 예외의 재시도·로그인 복구 의미를 바꾸지 않는다.
            log.error(
                "Automation selection diagnostic write failed accountId={} entryId={} stage={} errorType={} recordingErrorType={} diagnosticContext={}",
                accountId, entryId, stage, error.javaClass.name, recordingError.javaClass.name, diagnosticContext,
            )
        }
    }

    private fun coordinate(
        accountId: Long,
        entry: AutomationEntrySnapshot,
    ): AutomationCoordination {
        val exclusions = linkedSetOf<AutomationCandidateExclusion>()
        val messages = linkedSetOf<String>()
        val selection = convergenceModule?.openSelection(accountId, entry.id, entry.quest, convergenceRollout?.mode)
        val accepts: (PreparedAutomationAction) -> Boolean = { action ->
            val block = selection?.block(action)

            if (block != null) {
                exclusions += AutomationCandidateExclusion(block.scope.key, block.scope.kind.name,
                    block.actionKind.name, block.reasonCode, timeProvider.now(), block.message)
                messages += block.message
            }
            block == null
        }
        val result = evaluateEntry(entry, quest, battle, adventure, union, fishing, homeQuest, accepts) { gap ->
            coordinateObservationGap(entry, gap, selection)
        }
        if (result is AutomationCoordination.Runnable && accepts(result.action)) return result.copy(
            warnings = result.warnings + messages,
            trace = result.trace.map { it.copy(excludedCandidates = exclusions.toList()) },
        )
        if (exclusions.isEmpty()) return result
        val running = entry.homeQuest?.workSessionId != null || entry.quest?.workSessionId != null
        // 다른 후보의 구체적인 관측·설정 사유는 보류 사유로 덮어쓰지 않는다.
        if (result is AutomationCoordination.Fatal || (!running && result !is AutomationCoordination.Runnable &&
                result.trace.any { it.reasonCode != HandlerEvaluation.Skipped.reasonCode })) {
            return result.withTrace(result.trace.map { it.copy(excludedCandidates = exclusions.toList()) })
        }
        val first = exclusions.first()
        return AutomationCoordination.Idle(
            warnings = result.warnings + messages,
            nextRunAt = (result as? AutomationCoordination.Unavailable)?.nextRunAt,
            trace = listOf(
                AutomationEvaluationTrace(
                    sequence = 0,
                    entryId = entry.id,
                    type = entry.type,
                    outcome = if (running || entry.type !in CANDIDATE_ARBITRATED_TYPES) {
                        AutomationDecisionOutcome.WAITING
                    } else AutomationDecisionOutcome.SKIPPED,
                    reasonCode = first.reasonCode,
                    message = messages.joinToString(" ").take(1000),
                    observedAt = timeProvider.now(),
                    targetKey = first.targetKey,
                    scope = first.scope,
                    excludedCandidates = exclusions.toList(),
                ),
            ),
        )
    }

    private fun coordinateObservationGap(
        entry: AutomationEntrySnapshot,
        gap: HandlerEvaluation.ObservationGap,
        selection: AutomationConvergenceSelection?,
    ): AutomationCoordination {
        val directive = selection?.observeGap(gap) ?: return gap.toUnavailable(entry)
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

    private fun decideRaid(
        accountId: Long,
        entryId: Long,
        previousTrace: List<AutomationEvaluationTrace>,
        session: AutomationWorkSessionView? = null,
    ): RaidDecision {
        return try {
            val decision = raidModule.decide(accountId)
            convergenceModule?.openSelection(accountId, entryId, mode = convergenceRollout?.mode)
                ?.observeRaidDecision(decision) ?: decision
        } catch (error: Exception) {
            recordSelectionFailure(accountId, entryId, AutomationType.RAID, "RAID_EVALUATION", previousTrace, error, session = session)
            throw error
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
        val decision = decideRaid(accountId, session.entryId, initialTrace, session)
        val directive = decision.directive
        val diagnosticContext = AutomationDecisionDiagnostics.capture(
            "RAID_EVALUATION", timeProvider.now(), raid = decision.authoritativeState,
            workSessionId = session.id, targetKey = session.targetKey,
        )
        return when (directive) {
            is RaidDirective.Execute -> {
                val action = directive.intent.toPreparedAction(accountId)
                val block = convergenceModule?.openSelection(accountId, session.entryId, mode = convergenceRollout?.mode)?.block(action)
                if (block != null) {
                    lifecycle.waitForCooldown(
                        accountId,
                        session.id,
                        timeProvider.now().plusSeconds(SCOPE_SUPPRESSION_RECHECK_SECONDS),
                    )
                    AutomationCoordination.CycleBoundary(
                        warnings = initialWarnings + block.message,
                        trace = initialTrace + AutomationEvaluationTrace(
                            initialTrace.size,
                            session.entryId,
                            AutomationType.RAID,
                            AutomationDecisionOutcome.WAITING,
                            block.reasonCode,
                            block.message,
                            observedAt = timeProvider.now(),
                            targetKey = block.scope.key,
                            scope = block.scope.kind.name,
                            diagnosticContext = diagnosticContext,
                        ),
                    )
                } else {
                    AutomationCoordination.Runnable(
                        session.entryId,
                        action,
                        initialWarnings + listOfNotNull(directive.warning ?: directive.intent.recoveryWarning()),
                        initialTrace + directive.toTrace(session.entryId, initialTrace.size, diagnosticContext),
                    )
                }
            }
            is RaidDirective.WaitUntil -> {
                lifecycle.waitForCooldown(accountId, session.id, directive.at)
                AutomationCoordination.CycleBoundary(
                    warnings = initialWarnings,
                    trace = initialTrace + directive.toTrace(session.entryId, initialTrace.size, diagnosticContext),
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
                AutomationCoordination.CycleBoundary(
                    warnings = initialWarnings + listOfNotNull(directive.message.takeIf { directive.isUserWarning() }),
                    trace = initialTrace + directive.toTrace(session.entryId, initialTrace.size, diagnosticContext),
                )
            }
            is RaidDirective.Complete -> {
                lifecycle.applyTransition(accountId, session.id, AutomationWorkTransition.Complete)
                AutomationCoordination.CycleBoundary(
                    warnings = initialWarnings,
                    trace = initialTrace + directive.toTrace(session.entryId, initialTrace.size, diagnosticContext),
                )
            }
        }
    }

    private fun RaidDirective.toTrace(entryId: Long, sequence: Int, diagnosticContext: String): AutomationEvaluationTrace = when (this) {
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
            when (reason) {
                RaidWaitReason.REGISTRATION_COOLDOWN,
                RaidWaitReason.WAITING_TO_START,
                RaidWaitReason.BATTLE_COOLDOWN,
                RaidWaitReason.BATTLE_APPLIED_COOLDOWN -> AutomationDecisionOutcome.SKIPPED
                RaidWaitReason.BATTLE_RECOVERY_RECHECK,
                RaidWaitReason.REWARD_CONFIRMATION,
                RaidWaitReason.POST_REWARD_CHECK -> AutomationDecisionOutcome.WAITING
            },
            reasonCode ?: reason.name,
            message,
            at,
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
    }.let { if (it.outcome in DIAGNOSTIC_OUTCOMES) it.copy(diagnosticContext = diagnosticContext) else it }

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

    private fun AutomationCoordination.withTrace(trace: List<AutomationEvaluationTrace>): AutomationCoordination = when (this) {
        is AutomationCoordination.Runnable -> copy(trace = trace)
        is AutomationCoordination.Unavailable -> copy(trace = trace)
        is AutomationCoordination.Idle -> copy(trace = trace)
        is AutomationCoordination.CycleBoundary -> copy(trace = trace)
        is AutomationCoordination.Fatal -> copy(trace = trace)
    }

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
        is AutomationCoordination.CycleBoundary -> copy(
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

    private fun AutomationEntrySnapshot.withWorkSession(
        session: AutomationWorkSessionView,
    ): AutomationEntrySnapshot {
        return when (session.workType) {
            AutomationWorkType.QUEST -> copy(
                quest = quest?.copy(
                    workSessionId = session.id,
                    workSessionRevision = session.revision,
                ),
            )
            AutomationWorkType.HOME_QUEST -> copy(
                homeQuest = homeQuest?.copy(
                    workSessionId = session.id,
                    workSessionRevision = session.revision,
                ),
            )
            else -> this
        }
    }

    private companion object {
        val DIAGNOSTIC_OUTCOMES = setOf(
            AutomationDecisionOutcome.SKIPPED, AutomationDecisionOutcome.WAITING,
            AutomationDecisionOutcome.CONFIGURATION_WARNING, AutomationDecisionOutcome.FATAL,
        )
        val log = LoggerFactory.getLogger(AutomationTargetSelector::class.java)
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        const val CONVERGENCE_BLOCKED_REASON = "CONVERGENCE_SCOPE_BLOCKED"
        const val CAPTCHA_BATTLE_GATE_REASON = "CAPTCHA_BATTLE_GATE_BLOCKED"
        const val OBSERVATION_GAP_HELD_REASON = "OBSERVATION_GAP_HELD"
        const val QUEST_PROGRESS_STALE_REASON = "QUEST_PROGRESS_STALE"
        const val SCOPE_SUPPRESSION_RECHECK_SECONDS = 30L
        val CANDIDATE_ARBITRATED_TYPES = setOf(AutomationType.QUEST, AutomationType.HOME_QUEST)
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
    accepts: (PreparedAutomationAction) -> Boolean = { true },
    observationGap: ((HandlerEvaluation.ObservationGap) -> AutomationCoordination)? = null,
): AutomationCoordination {
    val evaluation = when (entry.type) {
        AutomationType.QUEST -> entry.quest?.let {
            quest.decideNext(it, accepts).toEntryEvaluation(hasRunningWork = it.workSessionId != null)
        }
        AutomationType.HOME_QUEST -> entry.homeQuest?.let { homeQuest.evaluate(it, accepts) }
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
            val holdsCurrentWork = evaluation.waitScope == AutomationWaitScope.HOLD_CURRENT_WORK
            AutomationEvaluationTrace(
                0,
                entry.id,
                entry.type,
                if (holdsCurrentWork) AutomationDecisionOutcome.WAITING else AutomationDecisionOutcome.SKIPPED,
                evaluation.reasonCode,
                detail?.message ?: evaluation.message,
                evaluation.nextRunAt,
                detail?.actionKind.takeIf { holdsCurrentWork },
                detail?.targetKey,
                detail?.targetName,
                detail?.presetId,
            )
        }
        is HandlerEvaluation.SkippedUntil -> AutomationEvaluationTrace(
            0,
            entry.id,
            entry.type,
            AutomationDecisionOutcome.SKIPPED,
            evaluation.reasonCode,
            evaluation.message,
            evaluation.nextRunAt,
        )
        is HandlerEvaluation.SkippedReason -> AutomationEvaluationTrace(
            0,
            entry.id,
            entry.type,
            AutomationDecisionOutcome.SKIPPED,
            evaluation.reasonCode,
            evaluation.message,
        )
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
        is HandlerEvaluation.SkippedUntil -> AutomationCoordination.Idle(
            warnings = emptyList(),
            trace = listOf(trace),
            nextRunAt = evaluation.nextRunAt,
        )
        is HandlerEvaluation.SkippedReason -> AutomationCoordination.Idle(emptyList(), listOf(trace))
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

private fun QuestDirective.toEntryEvaluation(hasRunningWork: Boolean): HandlerEvaluation = when (this) {
    is QuestDirective.Execute -> HandlerEvaluation.Runnable(action)
    is QuestDirective.WaitUntil -> if (hasRunningWork) {
        HandlerEvaluation.Unavailable(nextRunAt, reasonCode, message)
    } else {
        HandlerEvaluation.SkippedUntil(nextRunAt, reasonCode, message)
    }
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
