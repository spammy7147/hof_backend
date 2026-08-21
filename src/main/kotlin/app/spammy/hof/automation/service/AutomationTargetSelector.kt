package app.spammy.hof.automation.service

import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidDirective
import app.spammy.hof.automation.raid.RaidIntent
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionView
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

fun interface AutomationDecisionSource {
    fun select(accountId: Long): AutomationCoordination
}

@Service
class AutomationTargetSelector private constructor(
    private val typed: TypedAutomationQueryRepository,
    private val work: AutomationWorkSessionQueryRepository,
    private val loader: TypedAutomationSnapshotLoader,
    private val lifecycle: AutomationWorkLifecycle,
    private val timeProvider: TimeProvider,
    private val raidModule: RaidCycleModule,
    private val decideEntry: (AutomationCoordinatorEntry) -> AutomationCoordination,
) : AutomationDecisionSource {
    @Autowired
    constructor(
        typed: TypedAutomationQueryRepository,
        work: AutomationWorkSessionQueryRepository,
        loader: TypedAutomationSnapshotLoader,
        lifecycle: AutomationWorkLifecycle,
        timeProvider: TimeProvider,
        raidModule: RaidCycleModule,
        quest: QuestWorkCycleModule,
        battle: AutomationHandler<BattleMapAutomationSnapshot>,
        adventure: AutomationHandler<AdventureMapAutomationSnapshot>,
        union: AutomationHandler<UnionAutomationSnapshot>,
        fishing: AutomationHandler<FishingAutomationSnapshot>,
        homeQuest: AutomationHandler<HomeQuestAutomationSnapshot>,
    ) : this(
        typed,
        work,
        loader,
        lifecycle,
        timeProvider,
        raidModule,
        { entry -> evaluateEntry(entry, quest, battle, adventure, union, fishing, homeQuest) },
    )

    /** Expand 단계 동안 기존 테스트와 보조 runner 구성을 유지한다. */
    constructor(
        typed: TypedAutomationQueryRepository,
        work: AutomationWorkSessionQueryRepository,
        loader: TypedAutomationSnapshotLoader,
        coordinator: AutomationCoordinator,
        lifecycle: AutomationWorkLifecycle,
        timeProvider: TimeProvider,
        raidModule: RaidCycleModule,
    ) : this(
        typed,
        work,
        loader,
        lifecycle,
        timeProvider,
        raidModule,
        { entry -> coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(entry))) },
    )

    override fun select(accountId: Long): AutomationCoordination {
        work.findRunning(accountId)?.let { return selectSession(accountId, it) }
        return selectConfigured(accountId)
    }

    private fun selectSession(
        accountId: Long,
        session: AutomationWorkSessionView,
        initialWarnings: List<String> = emptyList(),
        initialTrace: List<AutomationEvaluationTrace> = emptyList(),
    ): AutomationCoordination {
        if (session.workType == AutomationWorkType.RAID) {
            return selectRaidSession(accountId, session, initialWarnings, initialTrace)
        }
        val entry = loader.loadEntry(accountId, session.entryId, session.targetKey)
            .withQuestWorkSession(session)
        return when (val result = coordinate(entry)) {
                is AutomationCoordination.Runnable -> {
                    result.withPrefix(initialWarnings, initialTrace)
                }
                is AutomationCoordination.Fatal -> result.withPrefix(initialWarnings, initialTrace)
                is AutomationCoordination.Unavailable -> {
                    val prefixed = result.withPrefix(initialWarnings, initialTrace)
                    if (result.waitScope == AutomationWaitScope.HOLD_CURRENT_WORK) {
                        prefixed
                    } else {
                        lifecycle.waitForCooldown(accountId, session.id, result.nextRunAt)
                        selectConfigured(
                            accountId,
                            initialWarnings + result.warnings,
                            initialTrace + result.trace.resequenced(initialTrace.size),
                        )
                    }
                }
                is AutomationCoordination.Idle -> {
                    result.workTransition?.let {
                        lifecycle.applyTransition(accountId, session.id, it)
                    } ?: if (session.workType != AutomationWorkType.QUEST) {
                        lifecycle.applyTransition(accountId, session.id, AutomationWorkTransition.Complete)
                    } else Unit
                    selectConfigured(
                        accountId,
                        initialWarnings + result.warnings,
                        initialTrace + result.trace.resequenced(initialTrace.size),
                    )
                }
            }
    }

    private fun selectConfigured(
        accountId: Long,
        initialWarnings: List<String> = emptyList(),
        initialTrace: List<AutomationEvaluationTrace> = emptyList(),
    ): AutomationCoordination {
        val warnings = initialWarnings.toMutableList()
        val trace = initialTrace.toMutableList()
        var earliest: Instant? = null
        val now = timeProvider.now()
        val waitsByEntry = work.findWaiting(accountId).groupBy { it.entryId }
        typed.findEntries(accountId)
            .asSequence()
            .filter { it.enabled }
            .forEach { entry ->
                val waiting = waitsByEntry[entry.id].orEmpty()
                val blockedUntil = waiting.mapNotNull { it.nextCheckAt }.minOrNull()
                val hasDueTarget = waiting.any {
                    it.status == app.spammy.hof.automation.entity.AutomationWorkStatus.YIELDED_PRIORITY ||
                        it.nextCheckAt?.isAfter(now) == false
                }
                if (waiting.isNotEmpty() && !hasDueTarget) {
                    waiting.mapNotNull(AutomationWorkSessionView::holdMessage).forEach { message ->
                        if (message !in warnings) warnings += message
                    }
                    if (blockedUntil != null && (earliest == null || blockedUntil < earliest)) earliest = blockedUntil
                    if (entry.type != AutomationType.QUEST) return@forEach
                }
                waiting.firstOrNull {
                    it.status == app.spammy.hof.automation.entity.AutomationWorkStatus.YIELDED_PRIORITY ||
                        it.nextCheckAt?.isAfter(now) == false
                }?.let { due ->
                    lifecycle.resumeForCheck(accountId, due.id)
                    return selectSession(accountId, due, warnings, trace)
                }
                if (entry.type == AutomationType.RAID) {
                    when (val directive = raidModule.decideNext(accountId)) {
                        is RaidDirective.Execute -> {
                            trace += directive.toTrace(entry.id, trace.size)
                            return AutomationCoordination.Runnable(
                                entry.id,
                                directive.intent.toPreparedAction(accountId),
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
                            warnings += directive.message
                            trace += directive.toTrace(entry.id, trace.size)
                            directive.raidId?.let { raidId ->
                                lifecycle.waitForRaid(
                                    accountId,
                                    directive.entryId ?: entry.id,
                                    raidId,
                                    directive.recheckAt,
                                    directive.message,
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
                val snapshot = loader.loadEntry(accountId, entry.id).excludingWaitingQuests(
                    waiting.map(AutomationWorkSessionView::targetKey).toSet(),
                )
                when (val result = coordinate(snapshot)) {
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
        return earliest?.let { AutomationCoordination.Unavailable(it, warnings, trace) }
            ?: AutomationCoordination.Idle(warnings, trace)
    }

    private fun coordinate(entry: AutomationCoordinatorEntry): AutomationCoordination =
        decideEntry(entry)

    private fun selectRaidSession(
        accountId: Long,
        session: AutomationWorkSessionView,
        initialWarnings: List<String>,
        initialTrace: List<AutomationEvaluationTrace>,
    ): AutomationCoordination = when (val directive = raidModule.decideNext(accountId)) {
        is RaidDirective.Execute -> AutomationCoordination.Runnable(
            session.entryId,
            directive.intent.toPreparedAction(accountId),
            initialWarnings + listOfNotNull(directive.warning ?: directive.intent.recoveryWarning()),
            initialTrace + directive.toTrace(session.entryId, initialTrace.size),
        )
        is RaidDirective.WaitUntil -> {
            lifecycle.waitForCooldown(accountId, session.id, directive.at)
            selectConfigured(
                accountId,
                initialWarnings,
                initialTrace + directive.toTrace(session.entryId, initialTrace.size),
            )
        }
        is RaidDirective.Hold -> {
            lifecycle.waitForRaid(
                accountId,
                directive.entryId ?: session.entryId,
                directive.raidId ?: session.targetKey,
                directive.recheckAt,
                directive.message,
            )
            selectConfigured(
                accountId,
                initialWarnings + directive.message,
                initialTrace + directive.toTrace(session.entryId, initialTrace.size),
            )
        }
        is RaidDirective.Complete -> {
            lifecycle.applyTransition(accountId, session.id, AutomationWorkTransition.Complete)
            selectConfigured(
                accountId,
                initialWarnings,
                initialTrace + directive.toTrace(session.entryId, initialTrace.size),
            )
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
            message = message ?: (intent as? RaidIntent.Battle)?.takeIf { it.recoveryChainId != null }?.let {
                "레이드 전투를 복구 체인으로 재전송합니다. 재전송 ${it.retransmissionCount}회"
            } ?: "레이드 ${intent.kind.name} 단계를 실행합니다.",
            actionKind = intent.kind.name,
            targetKey = intent.raidId,
            targetName = intent.raidName,
            presetId = (intent as? RaidIntent.Battle)?.presetId,
        )
        is RaidDirective.WaitUntil -> AutomationEvaluationTrace(
            sequence,
            entryId,
            AutomationType.RAID,
            AutomationDecisionOutcome.WAITING,
            reason.name,
            message,
            at,
            actionKind = "WAIT",
            targetKey = raidId,
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

    private fun AutomationCoordinatorEntry.excludingWaitingQuests(
        targetKeys: Set<String>,
    ): AutomationCoordinatorEntry {
        if (type != AutomationType.QUEST || targetKeys.isEmpty()) return this
        return copy(
            quest = quest?.copy(
                selections = quest.selections.filterNot { it.questKey in targetKeys },
            ),
        )
    }

    private fun AutomationCoordinatorEntry.withQuestWorkSession(
        session: AutomationWorkSessionView,
    ): AutomationCoordinatorEntry {
        if (session.workType != AutomationWorkType.QUEST) return this
        return copy(
            quest = quest?.copy(
                workSessionId = session.id,
                workSessionRevision = session.revision,
            ),
        )
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

private fun evaluateEntry(
    entry: AutomationCoordinatorEntry,
    quest: QuestWorkCycleModule,
    battle: AutomationHandler<BattleMapAutomationSnapshot>,
    adventure: AutomationHandler<AdventureMapAutomationSnapshot>,
    union: AutomationHandler<UnionAutomationSnapshot>,
    fishing: AutomationHandler<FishingAutomationSnapshot>,
    homeQuest: AutomationHandler<HomeQuestAutomationSnapshot>,
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
        AutomationWaitScope.HOLD_CURRENT_WORK,
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

private fun AutomationCoordinatorEntry.waitingEntryTrace(
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
