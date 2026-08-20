package app.spammy.hof.automation.service

import app.spammy.hof.automation.raid.HofRaidObservationAdapter
import app.spammy.hof.automation.raid.RaidAttempt
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.raid.RaidRecordResult
import app.spammy.hof.automation.raid.RaidResultObservation
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.town.fishing.service.FishingService
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.model.HomeQuestState
import app.spammy.hof.town.home.service.HomeService
import org.springframework.stereotype.Service

@Service
class DefaultAutomationAmbiguousActionReconciler(
    private val questGateway: QuestGatewayService,
    private val battleMapService: BattleMapService,
    private val battleHandler: BattleMapAutomationHandler,
    private val battleOutcomeReconciler: BattleOutcomeReconciler,
    private val sessionRecovery: HofSessionRecoveryExecutor,
    private val timeProvider: TimeProvider,
    private val workLifecycle: AutomationWorkLifecycle,
    private val raidCycleModule: RaidCycleModule,
    private val raidObservationAdapter: HofRaidObservationAdapter,
    private val fishingService: FishingService? = null,
    private val homeService: HomeService? = null,
) : AutomationAmbiguousActionReconciler {
    override fun reconcile(
        accountId: Long,
        action: StoredTypedAutomationAction,
    ): AmbiguousActionResolution = when (val payload = action.payload) {
        is StoredTypedActionPayload.QuestAccept -> reconcileQuestAccept(accountId, payload)
        is StoredTypedActionPayload.QuestClaim -> reconcileQuestClaim(accountId, payload)
        is StoredTypedActionPayload.QuestBattle -> reconcileQuestBattle(accountId, payload)
        is StoredTypedActionPayload.HomeQuest -> reconcileHomeQuest(accountId, payload)
        is StoredTypedActionPayload.AdventureMap -> reconcileAdventure(accountId, action.entryId, payload)
        is StoredTypedActionPayload.BattleMap -> {
            if (payload.source == BattleAutomationActionSource.RAID_AUTOMATION) {
                reconcileRaidBattle(accountId, action.entryId, action.executionIdentity, payload)
            } else {
                reconcileBattleMap(accountId, action.entryId, action.executionIdentity, payload)
            }
        }
        is StoredTypedActionPayload.FishingTown -> reconcileFishing(accountId, payload)
        is StoredTypedActionPayload.RaidTown -> reconcileRaid(accountId, action.entryId, payload)
        is StoredTypedActionPayload.RaidCycleAbort -> reconcileRaidAbort(accountId, action.entryId, payload)
    }

    private fun reconcileHomeQuest(accountId: Long, payload: StoredTypedActionPayload.HomeQuest): AmbiguousActionResolution {
        val latest = homeService?.load(accountId, HomeMode.HOME)
            ?: return AmbiguousActionResolution.VerifyLater(retryAt(), "자택 퀘스트 상태 조회 연결을 기다립니다.")
        val quest = latest.quests.singleOrNull { it.id == payload.questId }
        if (quest == null) {
            return if (payload.action == HomeQuestAutomationActionType.CLAIM) AmbiguousActionResolution.Applied()
            else AmbiguousActionResolution.VerifyLater(retryAt(), "수락한 자택 퀘스트가 아직 관측되지 않습니다.")
        }
        val originalState = if (payload.action == HomeQuestAutomationActionType.ACCEPT) HomeQuestState.AVAILABLE else HomeQuestState.CLAIMABLE
        return if (quest.state != originalState) AmbiguousActionResolution.Applied()
        else if (quest.actionId == payload.actionId) AmbiguousActionResolution.Resubmit
        else AmbiguousActionResolution.VerifyLater(retryAt(), "자택 퀘스트 실행 결과를 아직 확정할 수 없습니다.")
    }

    private fun reconcileFishing(accountId: Long, payload: StoredTypedActionPayload.FishingTown): AmbiguousActionResolution {
        val latest = fishingService?.load(accountId)
            ?: return AmbiguousActionResolution.VerifyLater(retryAt(), "낚시 상태 조회 연결을 기다립니다.")
        val changed = latest.primaryAction != payload.observedPrimaryAction ||
            (latest.remainingCasts != null && payload.observedRemainingCasts != null && latest.remainingCasts < payload.observedRemainingCasts)
        return if (changed) AmbiguousActionResolution.Applied()
        else AmbiguousActionResolution.Resubmit
    }

    private fun reconcileRaid(accountId: Long, entryId: Long, payload: StoredTypedActionPayload.RaidTown): AmbiguousActionResolution {
        val targetRaidId = payload.targetRaidId ?: payload.raidId
            ?: return AmbiguousActionResolution.VerifyLater(retryAt(), "저장된 레이드 대상이 없습니다.")
        val attempt = RaidAttempt(entryId, payload.action.toRaidIntentKind(), targetRaidId, payload.raidId)
        val observation = RaidResultObservation.Page(raidObservationAdapter.read(accountId))
        return recordRaid(accountId, attempt, observation)
    }

    private fun reconcileRaidAbort(
        accountId: Long,
        entryId: Long,
        payload: StoredTypedActionPayload.RaidCycleAbort,
    ): AmbiguousActionResolution {
        val outcome = when (payload.reason) {
            RaidCycleAbortReason.CLOSED -> RaidCycleOutcomeKind.ABORTED_CLOSED
            RaidCycleAbortReason.REGISTRATION_LOST -> RaidCycleOutcomeKind.ABORTED_REGISTRATION_LOST
        }
        return recordRaid(
            accountId,
            RaidAttempt(entryId, RaidIntentKind.REFRESH, payload.raidId, null),
            RaidResultObservation.LegacyCycleAbort(outcome),
        )
    }

    private fun recordRaid(
        accountId: Long,
        attempt: RaidAttempt,
        observation: RaidResultObservation,
        execution: TypedAutomationExecution = TypedAutomationExecution.Completed,
    ): AmbiguousActionResolution {
        return when (val result = raidCycleModule.recordObservedResult(accountId, attempt, observation)) {
            is RaidRecordResult.Recorded -> {
                result.completion?.let { workLifecycle.completeRaidCycle(accountId, attempt.entryId) }
                AmbiguousActionResolution.Applied(
                    result.completion?.let(TypedAutomationExecution::RaidCycleFinished) ?: execution,
                )
            }
            is RaidRecordResult.NotApplied -> AmbiguousActionResolution.Resubmit
            is RaidRecordResult.NeedsRecheck -> AmbiguousActionResolution.VerifyLater(result.at, result.message)
        }
    }

    private fun reconcileRaidBattle(
        accountId: Long,
        entryId: Long,
        executionIdentity: String,
        payload: StoredTypedActionPayload.BattleMap,
    ): AmbiguousActionResolution {
        val raidId = payload.sourceTargetKey
            ?: return AmbiguousActionResolution.VerifyLater(retryAt(), "저장된 레이드 전투 대상이 없습니다.")
        val action = BattleMapAutomationAction(
            accountId = accountId,
            progressDate = payload.progressDate,
            categoryId = payload.categoryId,
            mapCode = payload.mapCode,
            presetMode = payload.presetMode,
            presetId = payload.presetId,
            battleCount = payload.battleCount,
            executionIdentity = executionIdentity,
            source = payload.source,
            sourceTargetKey = raidId,
        )
        return when (val reconciliation = battleOutcomeReconciler.reloadRecentAuthoritativeEvidence(action)) {
            is BattleOutcomeReconciliation.Proven -> {
                if (!reconciliation.evidence.binds(action) || !reconciliation.evidence.isCompleteTerminal()) {
                    AmbiguousActionResolution.VerifyLater(
                        retryAt(),
                        "다시 읽은 레이드 전투 결과가 저장 행동과 정확히 일치하지 않습니다.",
                    )
                } else {
                    recordRaid(
                        accountId,
                        RaidAttempt(entryId, RaidIntentKind.BATTLE, raidId, null),
                        RaidResultObservation.BattleCompleted,
                        TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode),
                    )
                }
            }
            is BattleOutcomeReconciliation.Unproven -> AmbiguousActionResolution.VerifyLater(
                retryAt(),
                reconciliation.message,
            )
        }
    }

    private fun reconcileQuestAccept(
        accountId: Long,
        payload: StoredTypedActionPayload.QuestAccept,
    ): AmbiguousActionResolution {
        val quest = loadQuests(accountId).singleOrNull { it.questKey == payload.questKey }
            ?: return AmbiguousActionResolution.VerifyLater(
                retryAt(),
                "Quest ${payload.questKey} is absent from the authoritative page.",
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
        val quest = loadQuests(accountId).singleOrNull { it.questKey == payload.questKey }
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
        val quest = loadQuests(accountId).singleOrNull { it.questKey == payload.questKey }
            ?: return AmbiguousActionResolution.VerifyLater(
                retryAt(),
                "Quest ${payload.questKey} is absent from the authoritative page.",
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

    private fun reconcileAdventure(
        accountId: Long,
        entryId: Long,
        payload: StoredTypedActionPayload.AdventureMap,
    ): AmbiguousActionResolution {
        val current = sessionRecovery.execute(accountId) {
            battleMapService.findMaps(accountId, payload.categoryId, HofRequestOrigin.AUTOMATION)
        }.singleOrNull { it.mapCode == payload.mapCode }
            ?: return AmbiguousActionResolution.VerifyLater(
                retryAt(),
                "Adventure map ${payload.categoryId}/${payload.mapCode} is absent.",
            )
        val decreased = listOf(
            payload.observedAttemptRemaining to current.attemptCount,
            payload.observedWinRemaining to current.winCount,
            payload.observedAvailableCount to current.availableCount,
        ).any { (before, after) -> before != null && after != null && after < before }
        val cooldownStarted = current.cooldownRemainingSeconds?.let { it > 0 } == true
        if (decreased || cooldownStarted) {
            return completeAdventure(accountId, entryId, payload)
        }
        val exhausted = listOf(current.attemptCount, current.winCount, current.availableCount)
            .any { it != null && it <= 0 }
        val runnable = current.resolved && current.enabled && !exhausted &&
            current.keyCount != 0 && (current.cooldownRemainingSeconds ?: 0) <= 0
        if (runnable) return AmbiguousActionResolution.Resubmit
        // The action was prepared only while this map was runnable. If an authoritative reload now
        // resolves the same map as unavailable, do not replay a possibly successful battle.
        if (current.resolved && !current.enabled) {
            return completeAdventure(accountId, entryId, payload)
        }
        val retryAt = current.cooldownRemainingSeconds
            ?.takeIf { it > 0 }
            ?.let { timeProvider.now().plusSeconds(it) }
            ?: retryAt()
        return AmbiguousActionResolution.VerifyLater(
            retryAt,
            "Adventure map outcome is not yet authoritative.",
        )
    }

    private fun completeAdventure(
        accountId: Long,
        entryId: Long,
        payload: StoredTypedActionPayload.AdventureMap,
    ): AmbiguousActionResolution.Applied {
        workLifecycle.completeAdventureAction(accountId, entryId, payload.categoryId, payload.mapCode)
        return AmbiguousActionResolution.Applied(
            TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode),
        )
    }

    private fun reconcileBattleMap(
        accountId: Long,
        entryId: Long,
        executionIdentity: String,
        payload: StoredTypedActionPayload.BattleMap,
    ): AmbiguousActionResolution {
        val action = BattleMapAutomationAction(
            accountId = accountId,
            progressDate = payload.progressDate,
            categoryId = payload.categoryId,
            mapCode = payload.mapCode,
            presetMode = payload.presetMode,
            presetId = payload.presetId,
            battleCount = payload.battleCount,
            executionIdentity = executionIdentity,
        )
        battleHandler.confirmAmbiguousSuccess(action)
        workLifecycle.completeBattleMapAction(
            accountId,
            entryId,
            payload.categoryId,
            payload.mapCode,
        )
        return AmbiguousActionResolution.Applied(
            TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode),
        )
    }

    private fun retryAt() = timeProvider.now().plusSeconds(10)
}
