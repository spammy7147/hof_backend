package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity
import app.spammy.hof.automation.raid.HofRaidObservationAdapter
import app.spammy.hof.automation.raid.RaidAttempt
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.raid.RaidRecordResult
import app.spammy.hof.automation.raid.RaidResultObservation
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.model.HomeQuestState
import app.spammy.hof.town.home.service.HomeService
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.service.FishingService
import app.spammy.hof.town.raid.dto.RaidPubActionRequest
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.service.RaidPubService
import java.io.IOException
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * 공통 실행 loop와 자동화 행동 family 사이의 seam이다.
 *
 * 새 판단 결과와 저장된 행동을 같은 수명주기 행동으로 복원해 저장 표현, 실행,
 * 불명확 결과 조정, 작업 귀속과 사용자 설명이 서로 갈라지지 않게 한다.
 */
interface AutomationActionLifecycleModule {
    fun describe(action: PreparedAutomationAction): AutomationActionDescriptor

    fun prepare(
        accountId: Long,
        entryId: Long,
        action: PreparedAutomationAction,
    ): ManagedAutomationAction

    fun restore(
        row: TypedAutomationActionRunEntity,
        expectedAccountId: Long,
    ): ManagedAutomationAction
}

interface ManagedAutomationAction {
    val storedAction: StoredTypedAutomationAction
    val descriptor: AutomationActionDescriptor

    fun execute(): TypedAutomationExecution
    fun reconcile(): AmbiguousActionResolution

    /**
     * 일부 action family는 공용 RECONCILING 대신 자체 복구 상태가 불명확 제출을 소유한다.
     * 반환값이 없으면 기존 공용 조정 절차를 사용한다.
     */
    fun handoffAmbiguousSubmission(
        submittedAt: java.time.Instant?,
        reason: String,
    ): AmbiguousActionResolution.HandedOff? = null
}

data class AutomationActionDescriptor(
    val source: AutomationType,
    val storageKind: String,
    val actionKind: String,
    val actionLabel: String,
    val context: String,
    val targetKey: String? = null,
    val targetName: String? = null,
    val display: StoredActionDisplay? = null,
    val battleCount: Int? = null,
)

data class AutomationWorkAssignment(
    val type: AutomationWorkType,
    val targetKey: String,
    val targetCount: Int? = null,
)

fun interface AutomationWorkOwnership {
    fun ensure(accountId: Long, entryId: Long, assignment: AutomationWorkAssignment)
}

@Service
class UnifiedAutomationActionLifecycleModule(
    private val codec: StoredTypedAutomationActionCodec,
    private val workOwnership: AutomationWorkOwnership,
    private val homeService: HomeService,
    private val questGateway: QuestGatewayService,
    private val questWorkCycle: QuestWorkCycleModule,
    private val battleSubmission: AutomationBattleSubmission,
    private val battleHandler: BattleMapAutomationHandler,
    private val battleOutcomeReconciler: BattleOutcomeReconciler,
    private val battleMapService: BattleMapService,
    private val workLifecycle: AutomationWorkLifecycle,
    private val unionProgress: UnionAutomationProgressService,
    private val fishingService: FishingService,
    private val raidPubService: RaidPubService,
    private val raidCycleModule: RaidCycleModule,
    private val raidObservationAdapter: HofRaidObservationAdapter,
    private val executionSignals: AutomationExecutionSignals,
    private val sessionRecovery: HofSessionRecoveryExecutor,
    private val timeProvider: TimeProvider,
) : AutomationActionLifecycleModule {
    override fun describe(action: PreparedAutomationAction): AutomationActionDescriptor = when (action) {
        is HomeQuestAutomationAction -> descriptor(action.questId, action.questName, action.action)
        is QuestAction.Accept -> questDescriptor(action.questKey, action.questName, QUEST_ACCEPT_STORAGE_KIND)
        is QuestAction.Claim -> questDescriptor(action.questKey, action.questName, QUEST_CLAIM_STORAGE_KIND)
        is QuestAction.Battle -> questBattleDescriptor(
            action.questKey,
            action.questName,
            action.missionLabel,
            action.missionCurrent,
            action.missionRequired,
            action.categoryId,
            action.mapCode,
            action.mapName,
            action.battleCount,
        )
        is BattleMapAutomationAction -> {
            require(action.source in MANAGED_BATTLE_MAP_SOURCES) {
                "Prepared battle source ${action.source} is not owned by the lifecycle module."
            }
            battleMapDescriptor(action)
        }
        is AdventureMapAutomationAction -> adventureDescriptor(action)
        is FishingTownAutomationAction -> fishingDescriptor(action)
        is RaidTownAutomationAction -> raidTownDescriptor(action)
        is RaidCycleAbortAutomationAction -> raidAbortDescriptor(action)
    }

    override fun prepare(
        accountId: Long,
        entryId: Long,
        action: PreparedAutomationAction,
    ): ManagedAutomationAction = when (action) {
        is HomeQuestAutomationAction -> prepareHomeQuest(accountId, entryId, action)
        is QuestAction.Accept -> {
            workOwnership.ensure(
                accountId,
                entryId,
                AutomationWorkAssignment(AutomationWorkType.QUEST, action.questKey),
            )
            manage(
                accountId,
                StoredTypedAutomationAction(
                    entryId = entryId,
                    executionIdentity = UUID.randomUUID().toString(),
                    payload = StoredTypedActionPayload.QuestAccept(
                        questKey = action.questKey,
                        actionNo = action.actionNo,
                        display = StoredActionDisplay(questName = action.questName),
                    ),
                ),
            )
        }
        is QuestAction.Claim -> {
            workOwnership.ensure(
                accountId,
                entryId,
                AutomationWorkAssignment(AutomationWorkType.QUEST, action.questKey),
            )
            manage(
                accountId,
                StoredTypedAutomationAction(
                    entryId = entryId,
                    executionIdentity = UUID.randomUUID().toString(),
                    payload = StoredTypedActionPayload.QuestClaim(
                        questKey = action.questKey,
                        actionNo = action.actionNo,
                        display = StoredActionDisplay(questName = action.questName),
                    ),
                ),
            )
        }
        is QuestAction.Battle -> {
            workOwnership.ensure(
                accountId,
                entryId,
                AutomationWorkAssignment(AutomationWorkType.QUEST, action.questKey),
            )
            val presetId = action.preset.resolvedPresetId ?: action.preset.presetId
                ?: throw AutomationConfigurationException()
            val party = action.resolvedParty ?: throw AutomationConfigurationException("The prepared party is missing.")
            manage(
                accountId,
                StoredTypedAutomationAction(
                    entryId = entryId,
                    executionIdentity = UUID.randomUUID().toString(),
                    payload = StoredTypedActionPayload.QuestBattle(
                        questKey = action.questKey,
                        questCycle = action.questCycle,
                        missionKey = action.missionKey,
                        missionType = action.missionType,
                        categoryId = action.categoryId,
                        mapCode = action.mapCode,
                        presetMode = action.preset.mode,
                        presetId = presetId,
                        battleCount = action.battleCount,
                        battleRequest = RunBattleRequest(
                            action.categoryId,
                            action.mapCode,
                            party.characterIds,
                            party.patternLoads,
                            action.battleCount,
                        ),
                        display = StoredActionDisplay(
                            questName = action.questName,
                            missionLabel = action.missionLabel,
                            missionCurrent = action.missionCurrent,
                            missionRequired = action.missionRequired,
                            mapName = action.mapName,
                        ),
                        observedCurrent = action.missionCurrent,
                        observedRequired = action.missionRequired,
                    ),
                ),
            )
        }
        is BattleMapAutomationAction -> prepareBattleMap(accountId, entryId, action)
        is AdventureMapAutomationAction -> prepareAdventure(accountId, entryId, action)
        is FishingTownAutomationAction -> prepareFishing(accountId, entryId, action)
        is RaidTownAutomationAction -> prepareRaidTown(accountId, entryId, action)
        is RaidCycleAbortAutomationAction -> prepareRaidAbort(accountId, entryId, action)
    }

    private fun prepareHomeQuest(
        accountId: Long,
        entryId: Long,
        action: HomeQuestAutomationAction,
    ): ManagedAutomationAction {
        require(action.accountId == accountId) { "Prepared home quest account mismatch." }
        workOwnership.ensure(
            accountId,
            entryId,
            AutomationWorkAssignment(AutomationWorkType.HOME_QUEST, action.questId),
        )
        return manage(
            accountId,
            StoredTypedAutomationAction(
                entryId = entryId,
                executionIdentity = UUID.randomUUID().toString(),
                payload = StoredTypedActionPayload.HomeQuest(
                    questId = action.questId,
                    actionId = action.actionId,
                    action = action.action,
                    display = StoredActionDisplay(questName = action.questName),
                ),
            ),
        )
    }

    override fun restore(
        row: TypedAutomationActionRunEntity,
        expectedAccountId: Long,
    ): ManagedAutomationAction {
        require(row.actionKind in setOf(
                HOME_QUEST_STORAGE_KIND,
                QUEST_ACCEPT_STORAGE_KIND,
                QUEST_CLAIM_STORAGE_KIND,
                QUEST_BATTLE_STORAGE_KIND,
                BATTLE_MAP_STORAGE_KIND,
                ADVENTURE_MAP_STORAGE_KIND,
                FISHING_TOWN_STORAGE_KIND,
                RAID_TOWN_STORAGE_KIND,
                RAID_CYCLE_ABORT_STORAGE_KIND,
            )
        ) { "Stored action kind ${row.actionKind} is not owned by the lifecycle module." }
        val stored = codec.verifyPersisted(row, expectedAccountId)
        require(
            stored.payload !is StoredTypedActionPayload.BattleMap ||
                stored.payload.source in MANAGED_BATTLE_MAP_SOURCES,
        ) { "Stored battle source belongs to an unsupported lifecycle family." }
        require(
            stored.payload is StoredTypedActionPayload.HomeQuest ||
                stored.payload is StoredTypedActionPayload.QuestAccept ||
                stored.payload is StoredTypedActionPayload.QuestClaim ||
                stored.payload is StoredTypedActionPayload.QuestBattle ||
                stored.payload is StoredTypedActionPayload.BattleMap ||
                stored.payload is StoredTypedActionPayload.AdventureMap ||
                stored.payload is StoredTypedActionPayload.FishingTown ||
                stored.payload is StoredTypedActionPayload.RaidTown ||
                stored.payload is StoredTypedActionPayload.RaidCycleAbort,
        ) {
            "Stored action payload does not match the action lifecycle family."
        }
        return manage(expectedAccountId, stored)
    }

    private fun manage(accountId: Long, stored: StoredTypedAutomationAction): ManagedAutomationAction {
        return when (val payload = stored.payload) {
            is StoredTypedActionPayload.HomeQuest -> object : ManagedAutomationAction {
                override val storedAction = stored
                override val descriptor = payload.descriptor()

                override fun execute(): TypedAutomationExecution {
                    runMutation(accountId, "Home quest") {
                        homeService.runHomeQuest(accountId, payload.actionId)
                    }
                    return TypedAutomationExecution.Completed
                }

                override fun reconcile(): AmbiguousActionResolution = reconcileHomeQuest(accountId, payload)
            }
            is StoredTypedActionPayload.QuestAccept -> object : ManagedAutomationAction {
                override val storedAction = stored
                override val descriptor = payload.questDescriptor()

                override fun execute(): TypedAutomationExecution {
                    val quests = runMutation(accountId, "Quest") {
                        questGateway.accept(accountId, payload.actionNo, HofRequestOrigin.AUTOMATION)
                    }
                    requireRecordedQuestResult(
                        recordQuestResultAfterSubmission(
                            accountId,
                            QuestAttempt.Accept(stored.executionIdentity, payload.questKey, payload.actionNo),
                            QuestResultObservation.Page(quests),
                        ),
                    )
                    return TypedAutomationExecution.Completed
                }

                override fun reconcile(): AmbiguousActionResolution =
                    reconcileQuestAccept(accountId, stored.executionIdentity, payload)
            }
            is StoredTypedActionPayload.QuestClaim -> object : ManagedAutomationAction {
                override val storedAction = stored
                override val descriptor = payload.questDescriptor()

                override fun execute(): TypedAutomationExecution {
                    val quests = runMutation(accountId, "Quest") {
                        questGateway.claim(accountId, payload.actionNo, HofRequestOrigin.AUTOMATION)
                    }
                    requireRecordedQuestResult(
                        recordQuestResultAfterSubmission(
                            accountId,
                            QuestAttempt.Claim(stored.executionIdentity, payload.questKey, payload.actionNo),
                            QuestResultObservation.Page(quests),
                        ),
                    )
                    return TypedAutomationExecution.Completed
                }

                override fun reconcile(): AmbiguousActionResolution =
                    reconcileQuestClaim(accountId, stored.executionIdentity, payload)
            }
            is StoredTypedActionPayload.QuestBattle -> object : ManagedAutomationAction {
                override val storedAction = stored
                override val descriptor = payload.questBattleDescriptor()

                override fun execute(): TypedAutomationExecution = executeQuestBattle(accountId, stored, payload)

                override fun reconcile(): AmbiguousActionResolution = reconcileQuestBattle(accountId, stored, payload)
            }
            is StoredTypedActionPayload.BattleMap -> {
                require(payload.source in MANAGED_BATTLE_MAP_SOURCES) {
                    "Stored battle-map source ${payload.source} is not owned by this lifecycle family."
                }
                object : ManagedAutomationAction {
                    override val storedAction = stored
                    override val descriptor = payload.battleMapDescriptor()

                    override fun execute(): TypedAutomationExecution = executeBattleMap(accountId, stored, payload)

                    override fun reconcile(): AmbiguousActionResolution = reconcileBattleMap(accountId, stored, payload)

                    override fun handoffAmbiguousSubmission(
                        submittedAt: java.time.Instant?,
                        reason: String,
                    ): AmbiguousActionResolution.HandedOff? =
                        handoffAmbiguousRaidBattle(accountId, stored, payload, submittedAt, reason)
                }
            }
            is StoredTypedActionPayload.AdventureMap -> object : ManagedAutomationAction {
                override val storedAction = stored
                override val descriptor = payload.adventureDescriptor()

                override fun execute(): TypedAutomationExecution = executeAdventure(accountId, stored, payload)

                override fun reconcile(): AmbiguousActionResolution = reconcileAdventure(accountId, stored, payload)
            }
            is StoredTypedActionPayload.FishingTown -> object : ManagedAutomationAction {
                override val storedAction = stored
                override val descriptor = payload.fishingDescriptor()

                override fun execute(): TypedAutomationExecution {
                    runMutation(accountId, "Fishing") {
                        fishingService.act(accountId, payload.action)
                    }
                    return TypedAutomationExecution.Completed
                }

                override fun reconcile(): AmbiguousActionResolution = reconcileFishing(accountId, payload)
            }
            is StoredTypedActionPayload.RaidTown -> object : ManagedAutomationAction {
                override val storedAction = stored
                override val descriptor = payload.raidTownDescriptor()

                override fun execute(): TypedAutomationExecution = executeRaidTown(accountId, stored, payload)

                override fun reconcile(): AmbiguousActionResolution = reconcileRaidTown(accountId, stored, payload)
            }
            is StoredTypedActionPayload.RaidCycleAbort -> object : ManagedAutomationAction {
                override val storedAction = stored
                override val descriptor = payload.raidAbortDescriptor()

                override fun execute(): TypedAutomationExecution = executeRaidAbort(accountId, stored, payload)

                override fun reconcile(): AmbiguousActionResolution = reconcileRaidAbort(accountId, stored, payload)
            }
        }
    }

    private fun prepareBattleMap(
        accountId: Long,
        entryId: Long,
        action: BattleMapAutomationAction,
    ): ManagedAutomationAction {
        require(action.source in MANAGED_BATTLE_MAP_SOURCES) {
            "Prepared battle source ${action.source} is not owned by the lifecycle module."
        }
        require(action.accountId == accountId) { "Prepared battle-map account mismatch." }
        val presetId = action.presetId ?: throw AutomationConfigurationException()
        val party = action.resolvedParty ?: throw AutomationConfigurationException("The prepared party is missing.")
        val workType = when (action.source) {
            BattleAutomationActionSource.BATTLE_MAP_AUTOMATION -> AutomationWorkType.BATTLE_MAP
            BattleAutomationActionSource.UNION_AUTOMATION -> AutomationWorkType.UNION
            BattleAutomationActionSource.FISHING_AUTOMATION -> AutomationWorkType.FISHING
            BattleAutomationActionSource.RAID_AUTOMATION -> AutomationWorkType.RAID
            else -> error("Unsupported managed battle-map source ${action.source}.")
        }
        val targetKey = when (workType) {
            AutomationWorkType.FISHING -> FISHING_CYCLE_TARGET
            AutomationWorkType.RAID -> action.sourceTargetKey
                ?: throw AutomationConfigurationException("The prepared raid target is missing.")
            else -> "${action.categoryId}/${action.mapCode}"
        }
        workOwnership.ensure(
            accountId,
            entryId,
            AutomationWorkAssignment(
                workType,
                targetKey,
            ),
        )
        return manage(
            accountId,
            StoredTypedAutomationAction(
                entryId = entryId,
                executionIdentity = action.executionIdentity,
                payload = StoredTypedActionPayload.BattleMap(
                    progressDate = action.progressDate,
                    categoryId = action.categoryId,
                    mapCode = action.mapCode,
                    presetMode = action.presetMode,
                    presetId = presetId,
                    battleCount = action.battleCount,
                    battleRequest = RunBattleRequest(
                        action.categoryId,
                        action.mapCode,
                        party.characterIds,
                        party.patternLoads,
                        action.battleCount,
                    ),
                    display = StoredActionDisplay(mapName = action.mapName),
                    source = action.source,
                    sourceTargetKey = action.sourceTargetKey,
                    recoveryChainId = action.recoveryChainId,
                    raidRetransmissionCount = action.raidRetransmissionCount,
                    raidSubmittedFromRunnable = action.raidSubmittedFromRunnable,
                ),
            ),
        )
    }

    private fun prepareAdventure(
        accountId: Long,
        entryId: Long,
        action: AdventureMapAutomationAction,
    ): ManagedAutomationAction {
        require(action.accountId == accountId) { "Prepared adventure-map account mismatch." }
        val party = action.resolvedParty ?: throw AutomationConfigurationException("The prepared party is missing.")
        workOwnership.ensure(
            accountId,
            entryId,
            AutomationWorkAssignment(AutomationWorkType.ADVENTURE_MAP, "${action.categoryId}/${action.mapCode}"),
        )
        return manage(
            accountId,
            StoredTypedAutomationAction(
                entryId = entryId,
                executionIdentity = action.executionIdentity,
                payload = StoredTypedActionPayload.AdventureMap(
                    categoryId = action.categoryId,
                    mapCode = action.mapCode,
                    presetMode = action.presetMode,
                    presetId = action.presetId,
                    battleCount = action.battleCount,
                    settingIdentity = action.settingIdentity,
                    battleRequest = RunBattleRequest(
                        action.categoryId,
                        action.mapCode,
                        party.characterIds,
                        party.patternLoads,
                        action.battleCount,
                    ),
                    display = StoredActionDisplay(mapName = action.mapName),
                    observedCooldownUntil = action.observedCooldownUntil,
                    observedAttemptRemaining = action.observedAttemptRemaining,
                    observedWinRemaining = action.observedWinRemaining,
                    observedAvailableCount = action.observedAvailableCount,
                ),
            ),
        )
    }

    private fun prepareFishing(
        accountId: Long,
        entryId: Long,
        action: FishingTownAutomationAction,
    ): ManagedAutomationAction {
        require(action.accountId == accountId) { "Prepared fishing account mismatch." }
        workOwnership.ensure(
            accountId,
            entryId,
            AutomationWorkAssignment(AutomationWorkType.FISHING, FISHING_CYCLE_TARGET),
        )
        return manage(
            accountId,
            StoredTypedAutomationAction(
                entryId = entryId,
                executionIdentity = UUID.randomUUID().toString(),
                payload = StoredTypedActionPayload.FishingTown(
                    action = action.action,
                    observedPrimaryAction = action.observedPrimaryAction,
                    observedRemainingCasts = action.observedRemainingCasts,
                ),
            ),
        )
    }

    private fun prepareRaidTown(
        accountId: Long,
        entryId: Long,
        action: RaidTownAutomationAction,
    ): ManagedAutomationAction {
        require(action.accountId == accountId) { "Prepared raid account mismatch." }
        val targetRaidId = action.targetRaidId ?: action.raidId
            ?: throw AutomationConfigurationException("The prepared raid target is missing.")
        workOwnership.ensure(
            accountId,
            entryId,
            AutomationWorkAssignment(AutomationWorkType.RAID, targetRaidId),
        )
        return manage(
            accountId,
            StoredTypedAutomationAction(
                entryId = entryId,
                executionIdentity = UUID.randomUUID().toString(),
                payload = StoredTypedActionPayload.RaidTown(
                    action = action.action,
                    raidId = action.raidId,
                    targetRaidId = targetRaidId,
                    display = StoredActionDisplay(
                        mapName = action.raidName,
                        missionLabel = action.observedStatus,
                    ),
                ),
            ),
        )
    }

    private fun prepareRaidAbort(
        accountId: Long,
        entryId: Long,
        action: RaidCycleAbortAutomationAction,
    ): ManagedAutomationAction {
        require(action.accountId == accountId) { "Prepared raid account mismatch." }
        workOwnership.ensure(
            accountId,
            entryId,
            AutomationWorkAssignment(AutomationWorkType.RAID, action.raidId),
        )
        return manage(
            accountId,
            StoredTypedAutomationAction(
                entryId = entryId,
                executionIdentity = UUID.randomUUID().toString(),
                payload = StoredTypedActionPayload.RaidCycleAbort(action.raidId, action.reason),
            ),
        )
    }

    private fun reconcileHomeQuest(
        accountId: Long,
        payload: StoredTypedActionPayload.HomeQuest,
    ): AmbiguousActionResolution {
        val latest = homeService.load(accountId, HomeMode.HOME)
        val quest = latest.quests.singleOrNull { it.id == payload.questId }
        if (quest == null) {
            return if (payload.action == HomeQuestAutomationActionType.CLAIM) {
                AmbiguousActionResolution.Applied()
            } else {
                verifyLater("수락한 자택 퀘스트가 아직 관측되지 않습니다.")
            }
        }
        val originalState = if (payload.action == HomeQuestAutomationActionType.ACCEPT) {
            HomeQuestState.AVAILABLE
        } else {
            HomeQuestState.CLAIMABLE
        }
        return when {
            quest.state != originalState -> AmbiguousActionResolution.Applied()
            quest.actionId == payload.actionId -> AmbiguousActionResolution.Resubmit
            else -> verifyLater("자택 퀘스트 실행 결과를 아직 확정할 수 없습니다.")
        }
    }

    private fun reconcileQuestAccept(
        accountId: Long,
        executionIdentity: String,
        payload: StoredTypedActionPayload.QuestAccept,
    ): AmbiguousActionResolution {
        val quests = sessionRecovery.execute(accountId) {
            questGateway.load(accountId, HofRequestOrigin.AUTOMATION)
        }
        return questWorkCycle.recordObservedResult(
            accountId,
            QuestAttempt.Accept(executionIdentity, payload.questKey, payload.actionNo),
            QuestResultObservation.Page(quests),
        ).toAmbiguousResolution()
    }

    private fun reconcileQuestClaim(
        accountId: Long,
        executionIdentity: String,
        payload: StoredTypedActionPayload.QuestClaim,
    ): AmbiguousActionResolution {
        val quests = sessionRecovery.execute(accountId) {
            questGateway.load(accountId, HofRequestOrigin.AUTOMATION)
        }
        return questWorkCycle.recordObservedResult(
            accountId,
            QuestAttempt.Claim(executionIdentity, payload.questKey, payload.actionNo),
            QuestResultObservation.Page(quests),
        ).toAmbiguousResolution()
    }

    private fun reconcileQuestBattle(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.QuestBattle,
    ): AmbiguousActionResolution {
        val probe = payload.toBattleProbe(accountId, stored.executionIdentity)
        return when (val reconciliation = battleOutcomeReconciler.reloadRecentAuthoritativeEvidence(probe)) {
            is BattleOutcomeReconciliation.Proven -> {
                val evidence = reconciliation.evidence
                if (!evidence.binds(probe) || !evidence.isCompleteTerminal()) {
                    verifyLater("다시 읽은 퀘스트 전투 결과가 저장 행동과 정확히 일치하지 않습니다.")
                } else {
                    when (val result = questWorkCycle.recordObservedResult(
                        accountId,
                        QuestAttempt.Battle(stored.executionIdentity, payload.toQuestAction()),
                        QuestResultObservation.BattleRounds(evidence.outcomes),
                    )) {
                        is QuestRecordResult.Recorded -> appliedQuestBattle(payload)
                        is QuestRecordResult.NotApplied -> verifyLater(result.message)
                        is QuestRecordResult.NeedsRecheck -> verifyLater(result.message)
                    }
                }
            }
            is BattleOutcomeReconciliation.Unproven -> {
                val quests = sessionRecovery.execute(accountId) {
                    questGateway.load(accountId, HofRequestOrigin.AUTOMATION)
                }
                when (val result = questWorkCycle.recordObservedResult(
                    accountId,
                    QuestAttempt.Battle(stored.executionIdentity, payload.toQuestAction()),
                    QuestResultObservation.Page(quests),
                )) {
                    is QuestRecordResult.Recorded -> appliedQuestBattle(payload)
                    is QuestRecordResult.NotApplied -> verifyLater(result.message)
                    is QuestRecordResult.NeedsRecheck -> verifyLater(result.message)
                }
            }
        }
    }

    private fun appliedQuestBattle(payload: StoredTypedActionPayload.QuestBattle) =
        AmbiguousActionResolution.Applied(
            TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode),
        )

    private fun executeBattleMap(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.BattleMap,
    ): TypedAutomationExecution {
        val submission = battleSubmission.submit(
            accountId,
            stored.executionIdentity,
            payload.battleRequest,
            payload.source,
        )
        if (submission is AutomationBattleSubmissionResult.SharedCooldown) {
            return TypedAutomationExecution.SharedCooldown(payload.categoryId, payload.mapCode, submission.retryAt)
        }
        submission as AutomationBattleSubmissionResult.Completed
        val raidCompletion = applyCompletedBattleMap(
            accountId,
            stored,
            payload,
            submission.resultIdentity,
            submission.outcomes,
        )
        emitBattleSignals(accountId, payload.source, submission)
        return raidCompletion?.let(TypedAutomationExecution::RaidCycleFinished)
            ?: TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode)
    }

    private fun reconcileBattleMap(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.BattleMap,
    ): AmbiguousActionResolution {
        val action = payload.toPrepared(accountId, stored.executionIdentity)
        return when (val reconciliation = battleOutcomeReconciler.reloadRecentAuthoritativeEvidence(action)) {
            is BattleOutcomeReconciliation.Proven -> {
                val evidence = reconciliation.evidence
                if (!evidence.binds(action) || !evidence.isCompleteTerminal()) {
                    verifyLater("다시 읽은 전투 결과가 저장 행동과 정확히 일치하지 않습니다.")
                } else {
                    val raidCompletion = applyCompletedBattleMap(
                        accountId,
                        stored,
                        payload,
                        evidence.resultIdentity,
                        evidence.outcomes,
                    )
                    AmbiguousActionResolution.Applied(
                        raidCompletion?.let(TypedAutomationExecution::RaidCycleFinished)
                            ?: TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode),
                    )
                }
            }
            is BattleOutcomeReconciliation.Unproven -> verifyLater(reconciliation.message)
        }
    }

    private fun handoffAmbiguousRaidBattle(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.BattleMap,
        submittedAt: java.time.Instant?,
        reason: String,
    ): AmbiguousActionResolution.HandedOff? {
        if (payload.source != BattleAutomationActionSource.RAID_AUTOMATION) return null
        val raidId = payload.sourceTargetKey ?: return null
        val attempt = RaidAttempt(
            entryId = stored.entryId,
            kind = RaidIntentKind.BATTLE,
            raidId = raidId,
            requestRaidId = null,
            executionIdentity = stored.executionIdentity,
            categoryId = payload.categoryId,
            mapCode = payload.mapCode,
            recoveryChainId = payload.recoveryChainId,
            retransmissionCount = payload.raidRetransmissionCount,
            submittedAt = submittedAt ?: timeProvider.now(),
            submittedFromRunnable = payload.raidSubmittedFromRunnable,
        )
        val result = raidCycleModule.recordObservedResult(
            accountId,
            attempt,
            RaidResultObservation.BattleAmbiguous(reason),
        )
        val recheck = result as? RaidRecordResult.BattleRecoveryStarted
            ?: error("Ambiguous raid battle must enter the raid recovery state.")
        try {
            workLifecycle.waitForRaid(
                accountId,
                stored.entryId,
                raidId,
                recheck.at,
                recheck.message,
            )
        } catch (error: Throwable) {
            raidCycleModule.recordObservedResult(accountId, attempt, RaidResultObservation.ManualStop)
            throw error
        }
        return AmbiguousActionResolution.HandedOff(recheck.at, recheck.message)
    }

    private fun applyCompletedBattleMap(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.BattleMap,
        resultIdentity: String,
        outcomes: List<BattleAutomationRoundOutcome>,
    ): RaidCycleOutcome? {
        val action = payload.toPrepared(accountId, stored.executionIdentity)
        return when (payload.source) {
            BattleAutomationActionSource.BATTLE_MAP_AUTOMATION -> {
                val resolution = battleHandler.onBattleCompleted(
                    action,
                    payload.source,
                    resultIdentity,
                    outcomes,
                    battleOutcomeReconciler,
                )
                if (resolution is BattleOutcomeResolution.Fatal) {
                    throw AmbiguousAutomationSubmissionException(resolution.evaluation.message)
                }
                workLifecycle.completeBattleMapAction(
                    accountId,
                    stored.entryId,
                    payload.categoryId,
                    payload.mapCode,
                )
                null
            }
            BattleAutomationActionSource.UNION_AUTOMATION -> {
                unionProgress.battleCompleted(
                    accountId,
                    stored.entryId,
                    payload.categoryId,
                    payload.mapCode,
                )
                null
            }
            BattleAutomationActionSource.FISHING_AUTOMATION -> null
            BattleAutomationActionSource.RAID_AUTOMATION -> {
                val raidId = payload.sourceTargetKey ?: return null
                recordRaidResult(
                    accountId,
                    RaidAttempt(stored.entryId, RaidIntentKind.BATTLE, raidId, null),
                    RaidResultObservation.BattleCompleted,
                )
            }
            else -> error("Unsupported managed battle-map source ${payload.source}.")
        }
    }

    private fun StoredTypedActionPayload.BattleMap.toPrepared(
        accountId: Long,
        executionIdentity: String,
    ) = BattleMapAutomationAction(
        accountId = accountId,
        progressDate = progressDate,
        categoryId = categoryId,
        mapCode = mapCode,
        presetMode = presetMode,
        presetId = presetId,
        battleCount = battleCount,
        executionIdentity = executionIdentity,
        source = source,
        resolvedParty = ResolvedAutomationParty(battleRequest.characterIds, battleRequest.patternLoads),
        mapName = display?.mapName,
        sourceTargetKey = sourceTargetKey,
        recoveryChainId = recoveryChainId,
        raidRetransmissionCount = raidRetransmissionCount,
        raidSubmittedFromRunnable = raidSubmittedFromRunnable,
    )

    private fun battleMapDescriptor(action: BattleMapAutomationAction) = AutomationActionDescriptor(
        source = when (action.source) {
            BattleAutomationActionSource.UNION_AUTOMATION -> AutomationType.UNION
            BattleAutomationActionSource.FISHING_AUTOMATION -> AutomationType.FISHING
            BattleAutomationActionSource.RAID_AUTOMATION -> AutomationType.RAID
            else -> AutomationType.BATTLE_MAP
        },
        storageKind = BATTLE_MAP_STORAGE_KIND,
        actionKind = BATTLE_MAP_STORAGE_KIND,
        actionLabel = when (action.source) {
            BattleAutomationActionSource.UNION_AUTOMATION -> "유니온"
            BattleAutomationActionSource.FISHING_AUTOMATION -> "낚시"
            BattleAutomationActionSource.RAID_AUTOMATION -> "레이드"
            else -> "전투맵"
        },
        context = listOf(
            when (action.source) {
                BattleAutomationActionSource.UNION_AUTOMATION -> "유니온 전투"
                BattleAutomationActionSource.FISHING_AUTOMATION -> "낚시 방해 전투"
                BattleAutomationActionSource.RAID_AUTOMATION -> "레이드 누적 전투"
                else -> "일반 전투"
            },
            "맵 ${action.mapName ?: "${action.categoryId}/${action.mapCode}"}",
            "${action.battleCount}회",
            "파티 ${action.resolvedParty?.characterIds?.size ?: 0}명",
        ).joinToString(" · "),
        targetKey = action.sourceTargetKey ?: "${action.categoryId}/${action.mapCode}",
        targetName = action.mapName,
        display = StoredActionDisplay(mapName = action.mapName),
        battleCount = action.battleCount,
    )

    private fun StoredTypedActionPayload.BattleMap.battleMapDescriptor() =
        battleMapDescriptor(toPrepared(0L, "descriptor"))

    private fun executeAdventure(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.AdventureMap,
    ): TypedAutomationExecution {
        return when (val submission = battleSubmission.submit(
            accountId,
            stored.executionIdentity,
            payload.battleRequest,
            BattleAutomationActionSource.ADVENTURE_AUTOMATION,
        )) {
            is AutomationBattleSubmissionResult.SharedCooldown -> TypedAutomationExecution.SharedCooldown(
                payload.categoryId,
                payload.mapCode,
                submission.retryAt,
            )
            is AutomationBattleSubmissionResult.Completed -> {
                workLifecycle.completeAdventureAction(
                    accountId,
                    stored.entryId,
                    payload.categoryId,
                    payload.mapCode,
                )
                TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode)
            }
        }
    }

    private fun reconcileAdventure(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.AdventureMap,
    ): AmbiguousActionResolution {
        val action = payload.toPrepared(accountId, stored.executionIdentity)
        when (val battle = battleOutcomeReconciler.reloadRecentAuthoritativeEvidence(action.asBattleProbe())) {
            is BattleOutcomeReconciliation.Proven -> {
                if (battle.evidence.binds(action.asBattleProbe()) && battle.evidence.isCompleteTerminal()) {
                    return completeAdventure(accountId, stored.entryId, payload)
                }
                return verifyLater("다시 읽은 모험맵 전투 결과가 저장 행동과 정확히 일치하지 않습니다.")
            }
            is BattleOutcomeReconciliation.Unproven -> Unit
        }
        val current = sessionRecovery.execute(accountId) {
            battleMapService.findMaps(accountId, payload.categoryId, HofRequestOrigin.AUTOMATION)
        }.singleOrNull { it.mapCode == payload.mapCode }
            ?: return verifyLater("Adventure map ${payload.categoryId}/${payload.mapCode} is absent.")
        val comparisons = listOf(
            payload.observedAttemptRemaining to current.attemptCount,
            payload.observedWinRemaining to current.winCount,
            payload.observedAvailableCount to current.availableCount,
        )
        val hasBaseline = comparisons.any { (before, after) -> before != null && after != null }
        val decreased = comparisons.any { (before, after) -> before != null && after != null && after < before }
        val cooldownStarted = current.cooldownRemainingSeconds?.let { it > 0 } == true
        if (decreased || cooldownStarted || (current.resolved && !current.enabled)) {
            return completeAdventure(accountId, stored.entryId, payload)
        }
        val exhausted = listOf(current.attemptCount, current.winCount, current.availableCount)
            .any { it != null && it <= 0 }
        val runnable = current.resolved && current.enabled && !exhausted &&
            current.keyCount != 0 && (current.cooldownRemainingSeconds ?: 0) <= 0
        if (runnable && hasBaseline) return AmbiguousActionResolution.Resubmit
        val next = current.cooldownRemainingSeconds
            ?.takeIf { it > 0 }
            ?.let { timeProvider.now().plusSeconds(it) }
        return AmbiguousActionResolution.VerifyLater(
            next ?: timeProvider.now().plusSeconds(10),
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

    private fun adventureDescriptor(action: AdventureMapAutomationAction) = AutomationActionDescriptor(
        source = AutomationType.ADVENTURE_MAP,
        storageKind = ADVENTURE_MAP_STORAGE_KIND,
        actionKind = ADVENTURE_MAP_STORAGE_KIND,
        actionLabel = "모험맵",
        context = listOfNotNull(
            "모험 맵 전투 · ${action.mapName ?: "${action.categoryId}/${action.mapCode}"}",
            "${action.battleCount}회",
            action.observedAttemptRemaining?.let { "실행 전 남은 도전 ${it}회" },
            action.observedWinRemaining?.let { "실행 전 남은 승리 ${it}회" },
            action.observedAvailableCount?.let { "실행 가능 ${it}회" },
            action.observedCooldownUntil?.let { "관측 쿨다운 $it" },
        ).joinToString(" · "),
        targetKey = "${action.categoryId}/${action.mapCode}",
        targetName = action.mapName,
        display = StoredActionDisplay(mapName = action.mapName),
        battleCount = action.battleCount,
    )

    private fun StoredTypedActionPayload.AdventureMap.adventureDescriptor() =
        adventureDescriptor(toPrepared(0L, "descriptor"))

    private fun StoredTypedActionPayload.AdventureMap.toPrepared(
        accountId: Long,
        executionIdentity: String,
    ) = AdventureMapAutomationAction(
        accountId = accountId,
        categoryId = categoryId,
        mapCode = mapCode,
        presetMode = presetMode,
        presetId = presetId,
        battleCount = battleCount,
        settingIdentity = settingIdentity,
        executionIdentity = executionIdentity,
        resolvedParty = ResolvedAutomationParty(battleRequest.characterIds, battleRequest.patternLoads),
        mapName = display?.mapName,
        observedCooldownUntil = observedCooldownUntil,
        observedAttemptRemaining = observedAttemptRemaining,
        observedWinRemaining = observedWinRemaining,
        observedAvailableCount = observedAvailableCount,
    )

    private fun AdventureMapAutomationAction.asBattleProbe() = BattleMapAutomationAction(
        accountId = accountId,
        progressDate = timeProvider.now().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate(),
        categoryId = categoryId,
        mapCode = mapCode,
        presetMode = presetMode,
        presetId = presetId,
        battleCount = battleCount,
        executionIdentity = executionIdentity,
        source = BattleAutomationActionSource.ADVENTURE_AUTOMATION,
    )

    private fun reconcileFishing(
        accountId: Long,
        payload: StoredTypedActionPayload.FishingTown,
    ): AmbiguousActionResolution {
        val latest = sessionRecovery.execute(accountId) { fishingService.load(accountId) }
        val remainingDecreased = payload.observedRemainingCasts != null &&
            latest.remainingCasts != null &&
            latest.remainingCasts < payload.observedRemainingCasts
        if (latest.primaryAction != payload.observedPrimaryAction || remainingDecreased) {
            return AmbiguousActionResolution.Applied()
        }
        return verifyLater("낚시 실행 결과를 아직 확정할 수 없어 같은 동작을 다시 보내지 않습니다.")
    }

    private fun fishingDescriptor(action: FishingTownAutomationAction) = AutomationActionDescriptor(
        source = AutomationType.FISHING,
        storageKind = FISHING_TOWN_STORAGE_KIND,
        actionKind = action.action.name,
        actionLabel = "낚시",
        context = fishingContext(action.action, action.observedRemainingCasts),
    )

    private fun StoredTypedActionPayload.FishingTown.fishingDescriptor() = AutomationActionDescriptor(
        source = AutomationType.FISHING,
        storageKind = FISHING_TOWN_STORAGE_KIND,
        actionKind = action.name,
        actionLabel = "낚시",
        context = fishingContext(action, observedRemainingCasts),
    )

    private fun fishingContext(action: FishingAction, observedRemainingCasts: Int?): String = when (action) {
        FishingAction.START -> "낚시 사이클 시작 · 다음 필수 단계 잡기(CATCH)" +
            (observedRemainingCasts?.let { " · 실행 전 남은 ${it}회" } ?: "")
        FishingAction.CATCH -> "낚시 사이클 잡기 · 이후 물고기 획득/전투 발생 결과와 남은 횟수 재확인" +
            (observedRemainingCasts?.let { " · 실행 전 남은 ${it}회" } ?: "")
        else -> "낚시 ${action.name}"
    }

    private fun executeRaidTown(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.RaidTown,
    ): TypedAutomationExecution {
        val response = runMutation(accountId, "Raid") {
            raidPubService.action(accountId, RaidPubActionRequest(payload.action, payload.raidId))
        }
        val completion = recordRaidResult(
            accountId,
            payload.toRaidAttempt(stored.entryId),
            RaidResultObservation.Page(raidObservationAdapter.from(response)),
        )
        return completion?.let(TypedAutomationExecution::RaidCycleFinished)
            ?: TypedAutomationExecution.Completed
    }

    private fun reconcileRaidTown(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.RaidTown,
    ): AmbiguousActionResolution {
        val attempt = payload.toRaidAttemptOrNull(stored.entryId)
            ?: return verifyLater("저장된 레이드 대상이 없어 결과를 안전하게 확인할 수 없습니다.")
        return reconcileRaidResult(
            accountId,
            attempt,
            RaidResultObservation.Page(raidObservationAdapter.read(accountId)),
        )
    }

    private fun executeRaidAbort(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.RaidCycleAbort,
    ): TypedAutomationExecution {
        val completion = recordRaidResult(
            accountId,
            payload.toRaidAttempt(stored.entryId),
            payload.toObservation(),
        )
        return completion?.let(TypedAutomationExecution::RaidCycleFinished)
            ?: TypedAutomationExecution.Completed
    }

    private fun reconcileRaidAbort(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.RaidCycleAbort,
    ): AmbiguousActionResolution = reconcileRaidResult(
        accountId,
        payload.toRaidAttempt(stored.entryId),
        payload.toObservation(),
    )

    private fun recordRaidResult(
        accountId: Long,
        attempt: RaidAttempt,
        observation: RaidResultObservation,
    ): RaidCycleOutcome? = when (val result = raidCycleModule.recordObservedResult(accountId, attempt, observation)) {
        is RaidRecordResult.Recorded -> result.completion?.also {
            workLifecycle.completeRaidCycle(accountId, attempt.entryId)
        }
        is RaidRecordResult.NotApplied -> throw AmbiguousAutomationSubmissionException(result.message)
        is RaidRecordResult.NeedsRecheck -> throw AmbiguousAutomationSubmissionException(result.message)
        is RaidRecordResult.BattleRecoveryStarted -> throw AmbiguousAutomationSubmissionException(result.message)
    }

    private fun reconcileRaidResult(
        accountId: Long,
        attempt: RaidAttempt,
        observation: RaidResultObservation,
        defaultExecution: TypedAutomationExecution = TypedAutomationExecution.Completed,
    ): AmbiguousActionResolution = when (
        val result = raidCycleModule.recordObservedResult(accountId, attempt, observation)
    ) {
        is RaidRecordResult.Recorded -> {
            result.completion?.let { workLifecycle.completeRaidCycle(accountId, attempt.entryId) }
            AmbiguousActionResolution.Applied(
                result.completion?.let(TypedAutomationExecution::RaidCycleFinished) ?: defaultExecution,
            )
        }
        is RaidRecordResult.NotApplied -> AmbiguousActionResolution.Resubmit
        is RaidRecordResult.NeedsRecheck -> AmbiguousActionResolution.VerifyLater(result.at, result.message)
        is RaidRecordResult.BattleRecoveryStarted ->
            AmbiguousActionResolution.HandedOff(result.at, result.message)
    }

    private fun StoredTypedActionPayload.RaidTown.toRaidAttempt(entryId: Long): RaidAttempt {
        return toRaidAttemptOrNull(entryId)
            ?: throw AmbiguousAutomationSubmissionException("Stored raid action has no target raid id.")
    }

    private fun StoredTypedActionPayload.RaidTown.toRaidAttemptOrNull(entryId: Long): RaidAttempt? =
        (targetRaidId ?: raidId)?.let { target ->
            RaidAttempt(entryId, action.toRaidIntentKind(), target, raidId)
        }

    private fun StoredTypedActionPayload.RaidCycleAbort.toRaidAttempt(entryId: Long) =
        RaidAttempt(entryId, RaidIntentKind.REFRESH, raidId, null)

    private fun StoredTypedActionPayload.RaidCycleAbort.toObservation() =
        RaidResultObservation.LegacyCycleAbort(
            when (reason) {
                RaidCycleAbortReason.CLOSED -> RaidCycleOutcomeKind.ABORTED_CLOSED
                RaidCycleAbortReason.REGISTRATION_LOST -> RaidCycleOutcomeKind.ABORTED_REGISTRATION_LOST
            },
        )

    private fun raidTownDescriptor(action: RaidTownAutomationAction) = AutomationActionDescriptor(
        source = AutomationType.RAID,
        storageKind = RAID_TOWN_STORAGE_KIND,
        actionKind = action.action.name,
        actionLabel = "레이드",
        context = raidTownContext(action.action, action.observedStatus),
        targetKey = action.targetRaidId ?: action.raidId,
        targetName = action.raidName,
        display = StoredActionDisplay(mapName = action.raidName, missionLabel = action.observedStatus),
    )

    private fun StoredTypedActionPayload.RaidTown.raidTownDescriptor() = AutomationActionDescriptor(
        source = AutomationType.RAID,
        storageKind = RAID_TOWN_STORAGE_KIND,
        actionKind = action.name,
        actionLabel = "레이드",
        context = raidTownContext(action, display?.missionLabel),
        targetKey = targetRaidId ?: raidId,
        targetName = display?.mapName,
        display = display,
    )

    private fun raidTownContext(action: RaidAction, observedStatus: String?): String {
        val phase = when (action) {
            RaidAction.REGISTER -> "파티 등록"
            RaidAction.START -> "전투 시작"
            RaidAction.REWARD -> "보상 수령"
            RaidAction.REFRESH -> "상태 갱신"
            RaidAction.RESET -> "레이드 리셋"
            else -> action.name
        }
        return "$phase 단계${observedStatus?.let { " · 관측 상태: $it" } ?: ""}"
    }

    private fun raidAbortDescriptor(action: RaidCycleAbortAutomationAction) = AutomationActionDescriptor(
        source = AutomationType.RAID,
        storageKind = RAID_CYCLE_ABORT_STORAGE_KIND,
        actionKind = "CYCLE_ABORT",
        actionLabel = "레이드",
        context = raidAbortContext(action.raidId, action.reason),
        targetKey = action.raidId,
    )

    private fun StoredTypedActionPayload.RaidCycleAbort.raidAbortDescriptor() = AutomationActionDescriptor(
        source = AutomationType.RAID,
        storageKind = RAID_CYCLE_ABORT_STORAGE_KIND,
        actionKind = "CYCLE_ABORT",
        actionLabel = "레이드",
        context = raidAbortContext(raidId, reason),
        targetKey = raidId,
    )

    private fun raidAbortContext(raidId: String, reason: RaidCycleAbortReason) = when (reason) {
        RaidCycleAbortReason.CLOSED -> "레이드 사이클 중단 · $raidId"
        RaidCycleAbortReason.REGISTRATION_LOST -> "레이드 등록 상태 유실 복구 · $raidId"
    }

    private fun StoredTypedActionPayload.QuestAccept.questDescriptor() =
        questDescriptor(questKey, display?.questName, QUEST_ACCEPT_STORAGE_KIND)

    private fun StoredTypedActionPayload.QuestClaim.questDescriptor() =
        questDescriptor(questKey, display?.questName, QUEST_CLAIM_STORAGE_KIND)

    private fun StoredTypedActionPayload.QuestBattle.questBattleDescriptor() = questBattleDescriptor(
        questKey,
        display?.questName,
        display?.missionLabel,
        observedCurrent,
        observedRequired,
        categoryId,
        mapCode,
        display?.mapName,
        battleCount,
    )

    private fun questBattleDescriptor(
        questKey: String,
        questName: String?,
        missionLabel: String?,
        observedCurrent: Int?,
        observedRequired: Int?,
        categoryId: String,
        mapCode: String,
        mapName: String?,
        battleCount: Int,
    ) = AutomationActionDescriptor(
        source = AutomationType.QUEST,
        storageKind = QUEST_BATTLE_STORAGE_KIND,
        actionKind = QUEST_BATTLE_STORAGE_KIND,
        actionLabel = "퀘스트 전투",
        context = listOfNotNull(
            "퀘스트 전투 · ${questName ?: questKey}",
            missionLabel,
            observedCurrent?.let { current -> "실행 전 진행 $current/${observedRequired ?: "?"}" },
            "맵 ${mapName ?: "$categoryId/$mapCode"}",
            "${battleCount}회 전투",
        ).joinToString(" · "),
        targetKey = "$categoryId/$mapCode",
        targetName = mapName,
        display = StoredActionDisplay(
            questName = questName,
            missionLabel = missionLabel,
            missionCurrent = observedCurrent,
            missionRequired = observedRequired,
            mapName = mapName,
        ),
        battleCount = battleCount,
    )

    private fun executeQuestBattle(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.QuestBattle,
    ): TypedAutomationExecution {
        val submission = battleSubmission.submit(
            accountId,
            stored.executionIdentity,
            payload.battleRequest,
            BattleAutomationActionSource.QUEST_AUTOMATION,
        )
        if (submission is AutomationBattleSubmissionResult.SharedCooldown) {
            return TypedAutomationExecution.SharedCooldown(payload.categoryId, payload.mapCode, submission.retryAt)
        }
        submission as AutomationBattleSubmissionResult.Completed
        requireRecordedQuestResult(
            recordQuestResultAfterSubmission(
                accountId,
                QuestAttempt.Battle(
                    stored.executionIdentity,
                    payload.toQuestAction(),
                ),
                QuestResultObservation.BattleRounds(submission.outcomes),
            ),
        )
        emitBattleSignals(accountId, BattleAutomationActionSource.QUEST_AUTOMATION, submission)
        return TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode)
    }

    private fun StoredTypedActionPayload.QuestBattle.toQuestAction() = QuestAction.Battle(
        questKey = questKey,
        questCycle = questCycle,
        missionKey = missionKey,
        missionType = missionType,
        categoryId = categoryId,
        mapCode = mapCode,
        mapName = display?.mapName ?: mapCode,
        preset = QuestPresetSelection(presetMode, presetId),
        battleCount = battleCount,
        questName = display?.questName,
        missionLabel = display?.missionLabel,
        missionCurrent = observedCurrent,
        missionRequired = observedRequired,
    )

    private fun StoredTypedActionPayload.QuestBattle.toBattleProbe(
        accountId: Long,
        executionIdentity: String,
    ) = BattleMapAutomationAction(
        accountId = accountId,
        progressDate = timeProvider.now().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate(),
        categoryId = categoryId,
        mapCode = mapCode,
        presetMode = presetMode,
        presetId = presetId,
        battleCount = battleCount,
        executionIdentity = executionIdentity,
        source = BattleAutomationActionSource.QUEST_AUTOMATION,
        resolvedParty = ResolvedAutomationParty(battleRequest.characterIds, battleRequest.patternLoads),
        mapName = display?.mapName,
        sourceTargetKey = questKey,
    )

    private fun emitBattleSignals(
        accountId: Long,
        source: BattleAutomationActionSource,
        submission: AutomationBattleSubmissionResult.Completed,
    ) {
        val rounds = submission.response.rounds.takeIf(List<*>::isNotEmpty)
        executionSignals.afterBattle(
            accountId = accountId,
            source = source,
            outcomes = submission.outcomes,
            lootNames = rounds?.flatMap { it.loots.map { loot -> loot.name } }
                ?: submission.response.loots.map { it.name },
            questTexts = rounds?.mapNotNull { it.quest?.takeIf(String::isNotBlank) }
                ?: listOfNotNull(submission.response.quest?.takeIf(String::isNotBlank)),
        )
    }

    private fun questDescriptor(
        questKey: String,
        questName: String?,
        actionKind: String,
    ): AutomationActionDescriptor {
        val verb = if (actionKind == QUEST_ACCEPT_STORAGE_KIND) "수락" else "보상 수령"
        return AutomationActionDescriptor(
            source = AutomationType.QUEST,
            storageKind = actionKind,
            actionKind = actionKind,
            actionLabel = if (actionKind == QUEST_ACCEPT_STORAGE_KIND) "퀘스트 수락" else "퀘스트 완료",
            context = "퀘스트 $verb · ${questName ?: questKey}",
            targetKey = questKey,
            targetName = questName,
            display = questName?.let { StoredActionDisplay(questName = it) },
        )
    }

    private fun StoredTypedActionPayload.HomeQuest.descriptor(): AutomationActionDescriptor {
        return descriptor(questId, display?.questName, action)
    }

    private fun descriptor(
        questId: String,
        questName: String?,
        action: HomeQuestAutomationActionType,
    ): AutomationActionDescriptor {
        val verb = if (action == HomeQuestAutomationActionType.ACCEPT) "수락" else "완료"
        val name = questName ?: questId
        return AutomationActionDescriptor(
            source = AutomationType.HOME_QUEST,
            storageKind = HOME_QUEST_STORAGE_KIND,
            actionKind = "HOME_${action.name}",
            actionLabel = "자택 퀘스트",
            context = "자택 퀘스트 $verb · $name",
            targetKey = questId,
            targetName = questName,
            display = questName?.let { StoredActionDisplay(questName = it) },
        )
    }

    private fun verifyLater(reason: String) = AmbiguousActionResolution.VerifyLater(
        timeProvider.now().plusSeconds(10),
        reason,
    )

    private fun QuestRecordResult.toAmbiguousResolution(): AmbiguousActionResolution = when (this) {
        is QuestRecordResult.Recorded -> AmbiguousActionResolution.Applied()
        is QuestRecordResult.NotApplied -> AmbiguousActionResolution.Resubmit
        is QuestRecordResult.NeedsRecheck -> verifyLater(message)
    }

    private fun requireRecordedQuestResult(result: QuestRecordResult) {
        if (result is QuestRecordResult.Recorded) return
        val message = when (result) {
            is QuestRecordResult.NotApplied -> result.message
            is QuestRecordResult.NeedsRecheck -> result.message
            is QuestRecordResult.Recorded -> error("unreachable")
        }
        throw AmbiguousAutomationSubmissionException(
            "$message The quest action will be reconciled before any retry.",
        )
    }

    private fun recordQuestResultAfterSubmission(
        accountId: Long,
        attempt: QuestAttempt,
        observation: QuestResultObservation,
    ): QuestRecordResult = try {
        questWorkCycle.recordObservedResult(accountId, attempt, observation)
    } catch (error: Throwable) {
        throw AmbiguousAutomationSubmissionException(
            "The HOF quest response was observed, but local cycle recording must be reconciled.",
            error,
        )
    }

    private fun <T> runMutation(accountId: Long, family: String, operation: () -> T): T =
        try {
            sessionRecovery.execute(accountId, operation)
        } catch (error: Throwable) {
            val causes = generateSequence(error) { it.cause }.toList()
            causes.filterIsInstance<HofAutomationDeferredException>().firstOrNull()?.let { throw it }
            if (causes.any { it is AutomationLoginRequiredException }) throw error
            causes.filterIsInstance<ApiException>().firstOrNull()?.let { api ->
                if (api.errorCode in setOf(
                        ErrorCode.HOF_SESSION_EXPIRED,
                        ErrorCode.HOF_LOGIN_FAILED,
                        ErrorCode.CAPTCHA_REQUIRED,
                    )
                ) {
                    throw error
                }
                if (api.errorCode == ErrorCode.HOF_REQUEST_FAILED) {
                    throw AmbiguousAutomationSubmissionException(
                        "$family side-effect outcome is not provable; it will not be resent.",
                        error,
                    )
                }
            }
            if (causes.any { it is IOException }) {
                throw AmbiguousAutomationSubmissionException(
                    "$family side-effect outcome is not provable; it will not be resent.",
                    error,
                )
            }
            throw error
        }

    private companion object {
        const val HOME_QUEST_STORAGE_KIND = "HOME_QUEST"
        const val QUEST_ACCEPT_STORAGE_KIND = "QUEST_ACCEPT"
        const val QUEST_CLAIM_STORAGE_KIND = "QUEST_CLAIM"
        const val QUEST_BATTLE_STORAGE_KIND = "QUEST_BATTLE"
        const val BATTLE_MAP_STORAGE_KIND = "BATTLE_MAP"
        const val ADVENTURE_MAP_STORAGE_KIND = "ADVENTURE_MAP"
        const val FISHING_TOWN_STORAGE_KIND = "FISHING_TOWN"
        const val RAID_TOWN_STORAGE_KIND = "RAID_TOWN"
        const val RAID_CYCLE_ABORT_STORAGE_KIND = "RAID_CYCLE_ABORT"
        val MANAGED_BATTLE_MAP_SOURCES = setOf(
            BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
            BattleAutomationActionSource.UNION_AUTOMATION,
            BattleAutomationActionSource.FISHING_AUTOMATION,
            BattleAutomationActionSource.RAID_AUTOMATION,
        )
    }
}
