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
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestSection
import app.spammy.hof.quest.model.QuestState
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Service

fun interface AutomationDecisionSource {
    fun select(accountId: Long): AutomationCoordination
}

@Service
class AutomationTargetSelector(
    private val typed: TypedAutomationQueryRepository,
    private val work: AutomationWorkSessionQueryRepository,
    private val loader: TypedAutomationSnapshotLoader,
    private val coordinator: AutomationCoordinator,
    private val lifecycle: AutomationWorkLifecycle,
    private val lootSignals: AutomationLootSignalService,
    private val timeProvider: TimeProvider,
    private val raidModule: RaidCycleModule,
) : AutomationDecisionSource {
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
            .withQuestWorkProgress(session)
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
                    val selectedQuest = entry.quest?.quests
                        ?.singleOrNull { it.questKey == session.targetKey }
                    val material = selectedQuest
                        ?.missions
                        ?.firstOrNull { it.type == QuestMissionType.ITEM_TURN_IN && !it.completable }
                    if (
                        selectedQuest?.state == QuestState.UNAVAILABLE ||
                        selectedQuest?.section == QuestSection.WAITING
                    ) {
                        lifecycle.waitForUnknownCooldown(accountId, session.id)
                    } else if (material?.target?.isNotBlank() == true) {
                        val missing = material.progress?.let { (it.required - it.current).coerceAtLeast(0) }
                        lifecycle.waitForResource(
                            accountId,
                            session.id,
                            lootSignals.normalize(requireNotNull(material.target)),
                            missing,
                        )
                    } else {
                        lifecycle.complete(accountId, session.id)
                    }
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
                                warnings.toList(),
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
        coordinator.coordinate(AutomationCoordinatorSnapshot(listOf(entry)))

    private fun selectRaidSession(
        accountId: Long,
        session: AutomationWorkSessionView,
        initialWarnings: List<String>,
        initialTrace: List<AutomationEvaluationTrace>,
    ): AutomationCoordination = when (val directive = raidModule.decideNext(accountId)) {
        is RaidDirective.Execute -> AutomationCoordination.Runnable(
            session.entryId,
            directive.intent.toPreparedAction(accountId),
            initialWarnings,
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
            lifecycle.complete(accountId, session.id)
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
            reasonCode = "RUNNABLE",
            message = "레이드 ${intent.kind.name} 단계를 실행합니다.",
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
            reason.name,
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
            outcome.kind.name,
            "레이드 사이클을 ${outcome.kind.name} 상태로 마쳤습니다.",
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
        )
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

    private fun AutomationCoordinatorEntry.withQuestWorkProgress(
        session: AutomationWorkSessionView,
    ): AutomationCoordinatorEntry {
        val current = session.observedCurrent ?: return this
        val required = session.observedRequired ?: return this
        val cycle = session.questCycle ?: return this
        val mission = session.missionKey ?: return this
        if (session.workType != AutomationWorkType.QUEST || session.missionType != QuestMissionType.MAP_CLEAR.name) return this
        return copy(
            quest = quest?.copy(
                workProgress = QuestWorkProgressSnapshot(
                    sessionId = session.id,
                    questKey = session.targetKey,
                    questCycle = cycle,
                    missionKey = mission,
                    current = current,
                    required = required,
                    authoritative = true,
                ),
            ),
        )
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
