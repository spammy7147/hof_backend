package app.spammy.hof.automation.service

import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.town.fishing.service.FishingService
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.model.HomeQuestState
import app.spammy.hof.town.home.service.HomeService
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.model.RaidStatus
import app.spammy.hof.town.raid.service.RaidPubService
import org.springframework.stereotype.Service

@Service
class DefaultAutomationAmbiguousActionReconciler(
    private val questGateway: QuestGatewayService,
    private val battleMapService: BattleMapService,
    private val battleHandler: BattleMapAutomationHandler,
    private val sessionRecovery: HofSessionRecoveryExecutor,
    private val timeProvider: TimeProvider,
    private val workLifecycle: AutomationWorkLifecycle,
    private val fishingService: FishingService? = null,
    private val raidPubService: RaidPubService? = null,
    private val contentProgress: AutomationContentProgressService? = null,
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
        is StoredTypedActionPayload.BattleMap -> reconcileBattleMap(
            accountId,
            action.entryId,
            action.executionIdentity,
            payload,
        )
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
        val latest = raidPubService?.load(accountId)
            ?: return AmbiguousActionResolution.VerifyLater(retryAt(), "레이드 상태 조회 연결을 기다립니다.")
        val progress = contentProgress ?: return AmbiguousActionResolution.VerifyLater(retryAt(), "레이드 사이클 저장 연결을 기다립니다.")
        return when (payload.action) {
            RaidAction.REGISTER -> {
                val id = requireNotNull(payload.raidId)
                val raid = latest.raids.singleOrNull { it.id == id }
                    ?: return AmbiguousActionResolution.VerifyLater(retryAt(), "등록한 레이드가 아직 관측되지 않습니다.")
                if (raid.joined || latest.applied) {
                    progress.raidRegistered(accountId, entryId, id, raid.name, raid.waitSeconds ?: latest.applyWaitSeconds)
                    AmbiguousActionResolution.Applied()
                } else if (RaidAction.REGISTER in raid.actions) AmbiguousActionResolution.Resubmit
                else AmbiguousActionResolution.VerifyLater(retryAt(), "레이드 등록 결과를 아직 확정할 수 없습니다.")
            }
            RaidAction.START -> {
                val id = requireNotNull(payload.raidId)
                val raid = latest.raids.singleOrNull { it.id == id }
                    ?: return AmbiguousActionResolution.VerifyLater(retryAt(), "시작한 레이드가 아직 관측되지 않습니다.")
                if (raid.status in setOf(RaidStatus.IN_BATTLE, RaidStatus.COMPLETED)) {
                    progress.raidStarted(accountId, id); AmbiguousActionResolution.Applied()
                } else if (RaidAction.START in raid.actions) AmbiguousActionResolution.Resubmit
                else AmbiguousActionResolution.VerifyLater(retryAt(), "레이드 시작 결과를 아직 확정할 수 없습니다.")
            }
            RaidAction.REWARD -> if (
                RaidAction.REWARD !in latest.globalActions ||
                (latest.applyWait && (latest.applyWaitSeconds ?: 0) >= RAID_REWARD_COOLDOWN_PROOF_SECONDS)
            ) {
                progress.raidRewarded(accountId); AmbiguousActionResolution.Applied()
            } else AmbiguousActionResolution.Resubmit
            RaidAction.REFRESH -> {
                progress.raidStatusRefreshed(accountId)
                AmbiguousActionResolution.Applied()
            }
            RaidAction.RESET -> {
                val id = requireNotNull(payload.raidId)
                val raid = latest.raids.singleOrNull { it.id == id }
                if (raid != null && !raid.joined && RaidAction.REGISTER in raid.actions) {
                    progress.raidReset(accountId, id)
                    workLifecycle.completeRaidCycle(accountId, entryId)
                    AmbiguousActionResolution.Applied()
                } else if (raid != null && RaidAction.RESET in raid.actions) {
                    AmbiguousActionResolution.Resubmit
                } else {
                    AmbiguousActionResolution.VerifyLater(retryAt(), "레이드 리셋 결과를 아직 확정할 수 없습니다.")
                }
            }
            else -> AmbiguousActionResolution.VerifyLater(retryAt(), "허용하지 않는 레이드 자동 행동입니다.")
        }
    }

    private fun reconcileRaidAbort(
        accountId: Long,
        entryId: Long,
        payload: StoredTypedActionPayload.RaidCycleAbort,
    ): AmbiguousActionResolution {
        val progress = contentProgress
            ?: return AmbiguousActionResolution.VerifyLater(retryAt(), "레이드 사이클 저장 연결을 기다립니다.")
        progress.raidClosed(accountId, payload.raidId)
        workLifecycle.completeRaidCycle(accountId, entryId)
        return AmbiguousActionResolution.Applied()
    }

    private companion object {
        const val RAID_REWARD_COOLDOWN_PROOF_SECONDS = 2 * 60 * 60
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
