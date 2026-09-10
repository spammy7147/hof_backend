package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.AutomationActionEvidence
import app.spammy.hof.automation.convergence.FishingObservedState
import app.spammy.hof.automation.convergence.HomeQuestObservedState
import app.spammy.hof.automation.convergence.ProductionEvidenceShapes
import app.spammy.hof.automation.convergence.QuestObservedState
import app.spammy.hof.automation.convergence.RaidObservedState
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity
import app.spammy.hof.automation.raid.HofRaidObservationAdapter
import app.spammy.hof.automation.raid.RaidAttempt
import app.spammy.hof.automation.raid.RaidBattlePreSubmitGuard
import app.spammy.hof.automation.raid.RaidBattlePreSubmitResult
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.raid.RaidObservedStatus
import app.spammy.hof.automation.raid.RaidRecordResult
import app.spammy.hof.automation.raid.RaidRewardResultEvidence
import app.spammy.hof.automation.raid.RaidResultObservation
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.model.hasUsableKey
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.battle.service.CurrentBattleMapObservationStatus
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.model.HomeQuestState
import app.spammy.hof.town.home.service.HomeService
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.model.FishingOutcome
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import app.spammy.hof.town.fishing.service.FishingService
import app.spammy.hof.town.raid.dto.RaidPubActionRequest
import app.spammy.hof.town.raid.dto.RaidPubResponse
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.model.RaidBattleObservationStatus
import app.spammy.hof.town.raid.model.RaidRegistrationResultEvidence
import app.spammy.hof.town.raid.model.RaidStatus
import app.spammy.hof.town.raid.model.isRaidRegistrationAvailable
import app.spammy.hof.town.raid.model.isRaidResetRequiredStatus
import app.spammy.hof.town.raid.service.RaidActionPreconditionChangedException
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

    /** Runtime checkpoint가 persistence discriminator를 검증한 뒤 복원하는 경계다. */
    fun restoreVerified(
        action: StoredTypedAutomationAction,
        expectedAccountId: Long,
    ): ManagedAutomationAction
}

interface ManagedAutomationAction {
    val storedAction: StoredTypedAutomationAction
    val descriptor: AutomationActionDescriptor
    val diagnosticContext: String? get() = null

    /** Remote mutation 직전 최신 권위 상태를 다시 읽는 GET-only 검증 경계다. */
    fun validateBeforeSubmission() = Unit
    fun execute(): TypedAutomationExecution

    val directResponse: AutomationDirectResponse? get() = null

    /** 원래 수신한 정규화 입력을 복원한다. 원격 조회나 행동 전송은 수행하지 않는다. */
    fun restoreDirectResponse(response: AutomationDirectResponse): TypedAutomationExecution =
        error("This action does not support stored direct responses.")

    /** Policy가 직접 적용을 인정한 뒤에만 실행할 local domain projection 경계다. */
    fun applyPolicyAcceptedExecution(execution: TypedAutomationExecution): TypedAutomationExecution = execution

    /** Policy가 미적용·대체로 닫은 직접 응답에 포함된 행동별 최신 상태만 local cycle에 반영한다. */
    fun applyPolicyResolvedExecution(
        execution: TypedAutomationExecution,
        evidence: AutomationActionEvidence,
    ) = Unit

    /** SHADOW/LEGACY에서 기존 direct-response 판정과 projection을 그대로 재현하는 경계다. */
    fun applyLegacyExecution(execution: TypedAutomationExecution): TypedAutomationExecution = execution

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

interface ManagedFishingAutomationAction : ManagedAutomationAction {
    val cycleObservation: app.spammy.hof.town.fishing.service.FishingAutomationObservation?

    fun executeObservedResponse(): FishingDirectExecution?

    fun observeDirectResponse(
        response: app.spammy.hof.town.fishing.dto.FishingResponse,
    ): TypedAutomationExecution.ActionCompleted
}

data class FishingDirectExecution(
    val response: app.spammy.hof.town.fishing.dto.FishingResponse,
    val execution: TypedAutomationExecution.ActionCompleted,
)

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
    val presetId: Long? = null,
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
    private val raidBattlePreSubmitGuard: RaidBattlePreSubmitGuard = RaidBattlePreSubmitGuard.AllowAll,
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
            action.preset.resolvedPresetId ?: action.preset.presetId,
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
                        questCycle = action.questCycle,
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
                        questCycle = action.questCycle,
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
        return restoreVerified(codec.verifyPersisted(row, expectedAccountId), expectedAccountId)
    }

    override fun restoreVerified(
        action: StoredTypedAutomationAction,
        expectedAccountId: Long,
    ): ManagedAutomationAction {
        val stored = action
        require(stored.payload.kind() in setOf(
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
        ) { "Stored action kind ${stored.payload.kind()} is not owned by the lifecycle module." }
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

    private fun manage(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        fishingObservation: app.spammy.hof.town.fishing.service.FishingAutomationObservation? = null,
    ): ManagedAutomationAction {
        return when (val payload = stored.payload) {
            is StoredTypedActionPayload.HomeQuest -> object : ManagedAutomationAction {
                override val storedAction = stored
                override val descriptor = payload.descriptor()
                private var submittedResponse: AutomationDirectResponse.HomePage? = null

                override val directResponse: AutomationDirectResponse? get() = submittedResponse

                override fun restoreDirectResponse(response: AutomationDirectResponse): TypedAutomationExecution {
                    require(response is AutomationDirectResponse.HomePage)
                    submittedResponse = response
                    return homeActionCompleted(payload, response)
                }

                override fun validateBeforeSubmission() = validateHomeQuestBeforeSubmission(accountId, payload)

                override fun execute(): TypedAutomationExecution {
                    val response = runMutation(accountId, "Home quest") {
                        homeService.runHomeQuest(accountId, payload.actionId, HofRequestOrigin.AUTOMATION)
                    }
                    return restoreDirectResponse(AutomationDirectResponse.HomePage(
                        response.quests.map {
                            AutomationDirectResponse.HomePage.Quest(it.id, it.state, it.actionId, it.stateObserved)
                        },
                        response.result?.status,
                    ))
                }

                override fun applyLegacyExecution(execution: TypedAutomationExecution): TypedAutomationExecution {
                    val response = requireNotNull(submittedResponse) {
                        "Home quest response is missing from the legacy execution."
                    }
                    submittedResponse = null
                    requireHomeDirectApplied(payload, response)
                    return execution
                }

                override fun reconcile(): AmbiguousActionResolution = reconcileHomeQuest(accountId, payload)
            }
            is StoredTypedActionPayload.QuestAccept -> object : ManagedAutomationAction {
                override val storedAction = stored
                override val descriptor = payload.questDescriptor()
                private var submittedObservation: QuestResultObservation? = null

                override val directResponse: AutomationDirectResponse?
                    get() = (submittedObservation as? QuestResultObservation.Page)?.let {
                        AutomationDirectResponse.QuestPage(it.quests, it.complete)
                    }

                override fun restoreDirectResponse(response: AutomationDirectResponse): TypedAutomationExecution {
                    require(response is AutomationDirectResponse.QuestPage)
                    submittedObservation = QuestResultObservation.Page(response.quests, response.complete)
                    return questActionCompleted(payload, response.quests, response.complete)
                }

                override fun validateBeforeSubmission() = validateQuestBeforeSubmission(
                    accountId,
                    payload.questKey,
                    payload.actionNo,
                    app.spammy.hof.quest.model.QuestState.AVAILABLE,
                )

                override fun execute(): TypedAutomationExecution {
                    val observation = runMutation(accountId, "Quest") {
                        questGateway.acceptObservation(accountId, payload.actionNo, HofRequestOrigin.AUTOMATION)
                    }
                    submittedObservation = QuestResultObservation.Page(observation.quests, observation.complete)
                    return questActionCompleted(payload, observation.quests, observation.complete)
                }

                private fun applyProjection(execution: TypedAutomationExecution): TypedAutomationExecution {
                    val observation = requireNotNull(submittedObservation) {
                        "Quest accept observation is missing from the accepted execution."
                    }
                    submittedObservation = null
                    requireRecordedQuestResult(
                        recordQuestResultAfterSubmission(
                            accountId,
                            QuestAttempt.Accept(stored.executionIdentity, payload.questKey, payload.actionNo),
                            observation,
                        ),
                    )
                    return execution
                }

                override fun applyPolicyAcceptedExecution(execution: TypedAutomationExecution) = applyProjection(execution)

                override fun applyLegacyExecution(execution: TypedAutomationExecution) = applyProjection(execution)

                override fun reconcile(): AmbiguousActionResolution =
                    reconcileQuestAccept(accountId, stored.executionIdentity, payload)
            }
            is StoredTypedActionPayload.QuestClaim -> object : ManagedAutomationAction {
                override val storedAction = stored
                override val descriptor = payload.questDescriptor()
                private var submittedObservation: QuestResultObservation? = null

                override val directResponse: AutomationDirectResponse?
                    get() = (submittedObservation as? QuestResultObservation.Page)?.let {
                        AutomationDirectResponse.QuestPage(it.quests, it.complete)
                    }

                override fun restoreDirectResponse(response: AutomationDirectResponse): TypedAutomationExecution {
                    require(response is AutomationDirectResponse.QuestPage)
                    submittedObservation = QuestResultObservation.Page(response.quests, response.complete)
                    return questActionCompleted(payload, response.quests, response.complete)
                }

                override fun validateBeforeSubmission() = validateQuestBeforeSubmission(
                    accountId,
                    payload.questKey,
                    payload.actionNo,
                    app.spammy.hof.quest.model.QuestState.CLAIMABLE,
                )

                override fun execute(): TypedAutomationExecution {
                    val observation = runMutation(accountId, "Quest") {
                        questGateway.claimObservation(accountId, payload.actionNo, HofRequestOrigin.AUTOMATION)
                    }
                    submittedObservation = QuestResultObservation.Page(observation.quests, observation.complete)
                    return questActionCompleted(payload, observation.quests, observation.complete)
                }

                private fun applyProjection(execution: TypedAutomationExecution): TypedAutomationExecution {
                    val observation = requireNotNull(submittedObservation) {
                        "Quest claim observation is missing from the accepted execution."
                    }
                    submittedObservation = null
                    requireRecordedQuestResult(
                        recordQuestResultAfterSubmission(
                            accountId,
                            QuestAttempt.Claim(stored.executionIdentity, payload.questKey, payload.actionNo),
                            observation,
                        ),
                    )
                    return execution
                }

                override fun applyPolicyAcceptedExecution(execution: TypedAutomationExecution) = applyProjection(execution)

                override fun applyLegacyExecution(execution: TypedAutomationExecution) = applyProjection(execution)

                override fun reconcile(): AmbiguousActionResolution =
                    reconcileQuestClaim(accountId, stored.executionIdentity, payload)
            }
            is StoredTypedActionPayload.QuestBattle -> object : ManagedAutomationAction {
                override val storedAction = stored
                override val descriptor = payload.questBattleDescriptor()
                private var submittedBattle: AutomationBattleSubmissionResult.Completed? = null

                override fun validateBeforeSubmission() =
                    validateBattleBeforeSubmission(accountId, payload.battleRequest)

                override fun execute(): TypedAutomationExecution = executeQuestBattle(accountId, stored, payload) {
                    submittedBattle = it
                }

                private fun applyProjection(execution: TypedAutomationExecution): TypedAutomationExecution {
                    if (execution is TypedAutomationExecution.SharedCooldown) return execution
                    val submission = requireNotNull(submittedBattle) {
                        "Quest battle response is missing from the accepted execution."
                    }
                    submittedBattle = null
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
                    return execution
                }

                override fun applyPolicyAcceptedExecution(execution: TypedAutomationExecution) = applyProjection(execution)

                override fun applyLegacyExecution(execution: TypedAutomationExecution) = applyProjection(execution)

                override fun reconcile(): AmbiguousActionResolution = reconcileQuestBattle(accountId, stored, payload)
            }
            is StoredTypedActionPayload.BattleMap -> {
                require(payload.source in MANAGED_BATTLE_MAP_SOURCES) {
                    "Stored battle-map source ${payload.source} is not owned by this lifecycle family."
                }
                object : ManagedAutomationAction {
                    override val storedAction = stored
                    override val descriptor = payload.battleMapDescriptor()

                    override fun validateBeforeSubmission() = if (
                        payload.source == BattleAutomationActionSource.RAID_AUTOMATION
                    ) {
                        validateRaidBattleBeforeSubmission(accountId, payload)
                    } else {
                        validateBattleBeforeSubmission(accountId, payload.battleRequest)
                    }

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

                override fun validateBeforeSubmission() =
                    validateBattleBeforeSubmission(accountId, payload.battleRequest)

                override fun execute(): TypedAutomationExecution = executeAdventure(accountId, stored, payload)

                override fun reconcile(): AmbiguousActionResolution = reconcileAdventure(accountId, stored, payload)
            }
            is StoredTypedActionPayload.FishingTown -> object : ManagedFishingAutomationAction {
                override val storedAction = stored
                override val descriptor = payload.fishingDescriptor()
                override val cycleObservation = fishingObservation
                private var submittedResponse: app.spammy.hof.town.fishing.dto.FishingResponse? = null
                override var diagnosticContext: String? = null
                    private set

                override fun validateBeforeSubmission() {
                    if (cycleObservation == null) validateFishingBeforeSubmission(accountId, payload)
                }

                override fun execute(): TypedAutomationExecution {
                    executeObservedResponse()?.let { return it.execution }
                    val response = runMutation(accountId, "Fishing") {
                        fishingService.act(accountId, payload.action, HofRequestOrigin.AUTOMATION)
                    }
                    return observeDirectResponse(response)
                }

                override fun executeObservedResponse(): FishingDirectExecution? {
                    val observation = cycleObservation ?: return null
                    val response = fishingService.executeObservedActionForAutomation(
                        accountId,
                        payload.action,
                        observation,
                    )
                    return FishingDirectExecution(response, observeDirectResponse(response))
                }

                override fun observeDirectResponse(
                    response: app.spammy.hof.town.fishing.dto.FishingResponse,
                ): TypedAutomationExecution.ActionCompleted {
                    submittedResponse = response
                    diagnosticContext = AutomationDecisionDiagnostics.fishingAction(
                        stored, response, "DIRECT_RESPONSE", timeProvider.now(),
                    )
                    return fishingActionCompleted(payload, response)
                }

                private fun applyFishingExecution(execution: TypedAutomationExecution): TypedAutomationExecution {
                    val response = requireNotNull(submittedResponse) {
                        "Fishing response is missing from the legacy execution."
                    }
                    submittedResponse = null
                    requireFishingDirectApplied(payload, response)
                    if (payload.action == FishingAction.CATCH) {
                        workLifecycle.completeFishingCycle(accountId, stored.entryId, stored.executionIdentity)
                    }
                    return execution
                }

                override fun applyPolicyResolvedExecution(
                    execution: TypedAutomationExecution,
                    evidence: AutomationActionEvidence,
                ) {
                    if (evidence is AutomationActionEvidence.StateAdvanced && submittedResponse?.blockedByBattle == true) {
                        submittedResponse = null
                        workLifecycle.completeFishingCycle(accountId, stored.entryId, stored.executionIdentity)
                    }
                }

                override fun applyPolicyAcceptedExecution(execution: TypedAutomationExecution) =
                    applyFishingExecution(execution)

                override fun applyLegacyExecution(execution: TypedAutomationExecution) =
                    applyFishingExecution(execution)

                override fun reconcile(): AmbiguousActionResolution = reconcileFishing(accountId, stored.entryId, payload) { latest ->
                    diagnosticContext = AutomationDecisionDiagnostics.fishingAction(
                        stored, latest, "LATEST_OBSERVATION", timeProvider.now(),
                    )
                }
            }
            is StoredTypedActionPayload.RaidTown -> object : ManagedAutomationAction {
                override val storedAction = stored
                override val descriptor = payload.raidTownDescriptor()
                private var submittedResponse: RaidPubResponse? = null

                override fun validateBeforeSubmission() = validateRaidBeforeSubmission(accountId, payload)

                override fun execute(): TypedAutomationExecution {
                    val response = submitRaidTown(accountId, payload)
                    submittedResponse = response
                    return raidActionCompleted(payload, response, outcome = null)
                }

                private fun applyProjection(execution: TypedAutomationExecution): TypedAutomationExecution {
                    val response = requireNotNull(submittedResponse) {
                        "Raid response is missing from the accepted execution."
                    }
                    submittedResponse = null
                    val projection = recordRaidResult(
                        accountId,
                        payload.toRaidAttempt(stored.entryId,
                            stored.executionIdentity.takeIf { payload.action == RaidAction.REWARD }),
                        RaidResultObservation.Page(raidObservationAdapter.from(response, payload.action)),
                    )
                    return (execution as TypedAutomationExecution.ActionCompleted).copy(
                        raidOutcome = projection.completion,
                        raidWait = projection.waiting,
                    )
                }

                override fun applyPolicyAcceptedExecution(execution: TypedAutomationExecution): TypedAutomationExecution =
                    applyProjection(execution)

                override fun applyPolicyResolvedExecution(
                    execution: TypedAutomationExecution,
                    evidence: AutomationActionEvidence,
                ) {
                    val response = submittedResponse ?: return
                    val registrationRecovery = payload.action == RaidAction.REFRESH && response.pageComplete ||
                        payload.action == RaidAction.REGISTER && (response.applyWait ||
                            RaidRegistrationResultEvidence.hasStaleBattleConflict(response.result?.messages.orEmpty()))
                    if (
                        registrationRecovery &&
                        (evidence is AutomationActionEvidence.DirectRejected ||
                            evidence is AutomationActionEvidence.StateAdvanced)
                    ) {
                        applyProjection(execution)
                    }
                }

                override fun applyLegacyExecution(execution: TypedAutomationExecution): TypedAutomationExecution =
                    applyPolicyAcceptedExecution(execution)

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

    private fun validateBattleBeforeSubmission(accountId: Long, request: RunBattleRequest) {
        val observation = sessionRecovery.execute(accountId) {
            battleMapService.observeCurrentlyAvailableMaps(
                accountId,
                request.categoryId,
                HofRequestOrigin.AUTOMATION,
            )
        }
        when (observation.status) {
            CurrentBattleMapObservationStatus.INCOMPLETE ->
                throw AutomationPreSubmitObservationIncompleteException(
                    "최신 전투 맵 화면을 완전하게 관측하지 못했습니다.",
                )
            CurrentBattleMapObservationStatus.ABSENT ->
                throw AutomationActionPreconditionChangedException(
                    "최신 전투 맵 화면에서 대상 카테고리가 사라졌습니다.",
                )
            CurrentBattleMapObservationStatus.OBSERVED -> Unit
        }
        val target = observation.maps.singleOrNull { it.mapCode == request.mapCode }
            ?: throw AutomationActionPreconditionChangedException(
                "최신 전투 맵 화면에서 저장된 대상이 사라졌습니다.",
            )
        if (!target.resolved) {
            throw AutomationPreSubmitObservationIncompleteException(
                "최신 전투 맵 대상의 실행 식별자를 완전하게 해석하지 못했습니다.",
            )
        }
        val unavailable = !target.enabled ||
            (target.cooldownRemainingSeconds ?: 0L) > 0L ||
            !target.keyMode.hasUsableKey(target.keyCount) ||
            (target.availableCount != null && target.availableCount <= 0) ||
            (target.attemptCount != null && target.attemptCount <= 0) ||
            (target.winCount != null && target.winCount <= 0) ||
            (request.resolvedBattleCount() == 3 && !target.supportsThreeBattles)
        if (unavailable) {
            throw AutomationActionPreconditionChangedException(
                "최신 전투 맵 상태에서 저장된 전투를 더 이상 실행할 수 없습니다.",
            )
        }
    }

    private fun validateRaidBattleBeforeSubmission(
        accountId: Long,
        payload: StoredTypedActionPayload.BattleMap,
    ) {
        val raidId = payload.sourceTargetKey ?: throw AutomationPreSubmitObservationIncompleteException(
            "레이드 전투 대상 식별자가 없어 최신 공유 상태를 확인할 수 없습니다.",
        )
        if (raidBattlePreSubmitGuard === RaidBattlePreSubmitGuard.AllowAll) {
            validateLegacyRaidBattleBeforeSubmission(accountId, raidId, payload)
            return
        }
        when (val result = raidBattlePreSubmitGuard.validate(
            accountId,
            raidId,
            payload.categoryId,
            payload.mapCode,
        )) {
            RaidBattlePreSubmitResult.Ready -> Unit
            is RaidBattlePreSubmitResult.Incomplete ->
                throw AutomationPreSubmitObservationIncompleteException(result.message)
            is RaidBattlePreSubmitResult.Changed ->
                throw AutomationActionPreconditionChangedException(result.message)
        }
    }

    private fun validateLegacyRaidBattleBeforeSubmission(
        accountId: Long,
        raidId: String,
        payload: StoredTypedActionPayload.BattleMap,
    ) {
        val latest = sessionRecovery.execute(accountId) {
            raidPubService.load(accountId, HofRequestOrigin.AUTOMATION)
        }
        if (latest.battleObservationStatus == RaidBattleObservationStatus.INCOMPLETE) {
            throw AutomationPreSubmitObservationIncompleteException(
                "최신 레이드 화면에서 전투 상태를 완전하게 관측하지 못했습니다.",
            )
        }
        val raid = latest.raids.singleOrNull { it.id == raidId }
            ?: throw AutomationActionPreconditionChangedException(
                "최신 레이드 공유 상태에서 저장된 전투 대상이 사라졌습니다.",
            )
        val target = raid.battleTarget
        val runnable = raid.joined && raid.playable && raid.status == RaidStatus.IN_BATTLE &&
            latest.battleObservationStatus == RaidBattleObservationStatus.OBSERVED &&
            target != null && target.categoryId == payload.categoryId && target.mapCode == payload.mapCode &&
            (target.cooldownRemainingSeconds ?: 0L) <= 0L
        if (!runnable) {
            throw AutomationActionPreconditionChangedException(
                "최신 레이드 공유 상태가 바뀌어 저장된 전투를 더 이상 실행할 수 없습니다.",
            )
        }
    }

    private fun validateHomeQuestBeforeSubmission(
        accountId: Long,
        payload: StoredTypedActionPayload.HomeQuest,
    ) {
        val latest = sessionRecovery.execute(accountId) {
            homeService.load(accountId, HomeMode.HOME, HofRequestOrigin.AUTOMATION)
        }
        val quest = latest.quests.singleOrNull { it.id == payload.questId }
            ?: throw AutomationActionPreconditionChangedException(
                "최신 자택 상태에서 저장된 퀘스트가 사라졌습니다.",
            )
        val expectedState = if (payload.action == HomeQuestAutomationActionType.ACCEPT) {
            HomeQuestState.AVAILABLE
        } else {
            HomeQuestState.CLAIMABLE
        }
        if (quest.state != expectedState) {
            throw AutomationActionPreconditionChangedException(
                "최신 자택 상태에서 저장된 퀘스트 단계가 바뀌었습니다.",
            )
        }
        val actionId = quest.actionId ?: throw AutomationPreSubmitObservationIncompleteException(
            "최신 자택 상태에서 퀘스트 실행 식별자를 완전하게 관측하지 못했습니다.",
        )
        if (actionId != payload.actionId) {
            throw AutomationActionPreconditionChangedException(
                "최신 자택 상태에서 퀘스트 실행 식별자가 바뀌었습니다.",
            )
        }
    }

    private fun validateQuestBeforeSubmission(
        accountId: Long,
        questKey: String,
        actionNo: String,
        expectedState: app.spammy.hof.quest.model.QuestState,
    ) {
        val observation = sessionRecovery.execute(accountId) {
            questGateway.loadObservation(accountId, HofRequestOrigin.AUTOMATION)
        }
        if (!observation.complete) {
            throw AutomationPreSubmitObservationIncompleteException(
                "최신 퀘스트 페이지의 완전성을 확인하지 못했습니다.",
            )
        }
        val quest = observation.quests.singleOrNull { it.questKey == questKey }
            ?: throw AutomationActionPreconditionChangedException(
                "최신 퀘스트 상태에서 저장된 대상이 사라졌습니다.",
            )
        if (quest.state != expectedState) {
            throw AutomationActionPreconditionChangedException(
                "최신 퀘스트 상태에서 저장된 단계가 바뀌었습니다.",
            )
        }
        val currentActionNo = quest.actionNo ?: throw AutomationPreSubmitObservationIncompleteException(
            "최신 퀘스트 상태에서 실행 식별자를 완전하게 관측하지 못했습니다.",
        )
        if (currentActionNo != actionNo) {
            throw AutomationActionPreconditionChangedException(
                "최신 퀘스트 상태에서 실행 식별자가 바뀌었습니다.",
            )
        }
    }

    private fun validateFishingBeforeSubmission(
        accountId: Long,
        payload: StoredTypedActionPayload.FishingTown,
    ) {
        val latest = sessionRecovery.execute(accountId) {
            fishingService.load(accountId, HofRequestOrigin.AUTOMATION)
        }
        if (!latest.battleObservationComplete) throw AutomationPreSubmitObservationIncompleteException(
            "최신 낚시 전투 목록의 완전성을 확인하지 못했습니다.",
        )
        if (
            latest.primaryAction != payload.observedPrimaryAction ||
            payload.action !in latest.availableActions
        ) {
            throw AutomationActionPreconditionChangedException(
                "최신 낚시 상태에서 저장된 동작을 더 이상 실행할 수 없습니다.",
            )
        }
    }

    private fun validateRaidBeforeSubmission(
        accountId: Long,
        payload: StoredTypedActionPayload.RaidTown,
    ) {
        val latest = sessionRecovery.execute(accountId) {
            raidPubService.load(accountId, HofRequestOrigin.AUTOMATION)
        }
        val targetActions = setOf(RaidAction.REGISTER, RaidAction.LEAVE, RaidAction.START, RaidAction.RESET)
        val runnable = if (payload.action in targetActions) {
            val raidId = payload.raidId ?: payload.targetRaidId
            val raid = latest.raids.singleOrNull { it.id == raidId }
            raid != null && payload.action in raid.actions && raid.playable && when (payload.action) {
                RaidAction.REGISTER -> !raid.joined && !latest.applyWait && isRaidRegistrationAvailable(raid.status)
                RaidAction.LEAVE -> raid.joined
                RaidAction.START -> raid.joined && raid.status == RaidStatus.READY
                RaidAction.RESET -> raid.status == RaidStatus.COMPLETED &&
                    isRaidResetRequiredStatus(raid.statusText)
                else -> false
            }
        } else if (payload.action == RaidAction.REWARD) {
            if (payload.selectedAt == null) {
                throw AutomationActionPreconditionChangedException(
                    "배포 전 저장된 보상 payload는 재생하지 않고 최신 상태에서 새로 판단합니다.",
                )
            }
            val target = payload.targetRaidId?.let { id -> latest.raids.singleOrNull { it.id == id } }
            target != null && target.status == RaidStatus.COMPLETED &&
                target.joined && !isRaidResetRequiredStatus(target.statusText) && !latest.applyWait &&
                target.rewardWindowStatus in setOf(
                    app.spammy.hof.town.raid.model.RaidRewardWindowStatus.AVAILABLE,
                    app.spammy.hof.town.raid.model.RaidRewardWindowStatus.CLAIM_WINDOW,
                )
        } else {
            payload.action in latest.globalActions
        }
        if (!runnable) {
            throw AutomationActionPreconditionChangedException(
                "최신 레이드 상태에서 저장된 동작을 더 이상 실행할 수 없습니다.",
            )
        }
    }

    private fun homeActionCompleted(
        payload: StoredTypedActionPayload.HomeQuest,
        response: AutomationDirectResponse.HomePage,
    ): TypedAutomationExecution.ActionCompleted {
        val matches = response.quests.filter { it.id == payload.questId }
        val quest = matches.singleOrNull()
        val multiplicity = targetMultiplicity(matches.size)
        val snippet = "HomeResponse|targetMultiplicity=$multiplicity|targetPresent=${quest != null}|" +
            "state=${quest?.state?.name ?: "ABSENT"}|" +
            "actionIdPresent=${quest?.actionId != null}|stateObserved=${quest?.stateObserved == true}|" +
            "resultStatus=${response.resultStatus ?: "NONE"}"
        val expectedState = if (payload.action == HomeQuestAutomationActionType.ACCEPT) {
            HomeQuestState.AVAILABLE
        } else {
            HomeQuestState.CLAIMABLE
        }
        val unchanged = quest?.state == expectedState && quest.actionId == payload.actionId
        return TypedAutomationExecution.ActionCompleted(
            observedState = HomeQuestObservedState(
                fingerprint = if (unchanged) {
                    ProductionEvidenceShapes.fingerprint(
                        "home|${payload.action}|${payload.questId}|${payload.actionId}",
                    )
                } else {
                    ProductionEvidenceShapes.fingerprint(snippet)
                },
                present = quest != null,
                state = quest?.state,
                actionId = quest?.actionId?.let { "present" },
            ),
            responseShapeMaterial = responseShapeMaterial(
                ProductionEvidenceShapes.HOME_RESPONSE,
                structurallyKnown = matches.size <= 1 &&
                    quest != null &&
                    (knownTownResultStatus(response.resultStatus) ||
                        (quest.stateObserved && homeActionPoststateApplied(payload.action, quest))),
            ),
            sanitizedSnippet = snippet,
        )
    }

    private fun requireHomeDirectApplied(
        payload: StoredTypedActionPayload.HomeQuest,
        response: AutomationDirectResponse.HomePage,
    ) {
        val quest = response.quests.singleOrNull { it.id == payload.questId }
        if (!homeActionPoststateApplied(payload.action, quest)) {
            throw AmbiguousAutomationSubmissionException(
                "Home quest direct response did not prove the action-specific poststate.",
            )
        }
    }

    private fun homeActionPoststateApplied(
        action: HomeQuestAutomationActionType,
        quest: AutomationDirectResponse.HomePage.Quest?,
    ): Boolean = when (action) {
        HomeQuestAutomationActionType.ACCEPT -> quest?.state in setOf(HomeQuestState.ACTIVE, HomeQuestState.CLAIMABLE)
        HomeQuestAutomationActionType.CLAIM -> quest == null || quest.state in setOf(HomeQuestState.WAITING, HomeQuestState.COMPLETED)
    }

    private fun questActionCompleted(
        payload: StoredTypedActionPayload.QuestAccept,
        quests: List<app.spammy.hof.quest.model.QuestSnapshot>,
        complete: Boolean,
    ): TypedAutomationExecution.ActionCompleted = questActionCompleted(
        questKey = payload.questKey,
        actionNo = payload.actionNo,
        questCycle = payload.questCycle,
        expectedState = QuestState.AVAILABLE,
        baselineAction = "accept",
        quests = quests,
        complete = complete,
    )

    private fun questActionCompleted(
        payload: StoredTypedActionPayload.QuestClaim,
        quests: List<app.spammy.hof.quest.model.QuestSnapshot>,
        complete: Boolean,
    ): TypedAutomationExecution.ActionCompleted = questActionCompleted(
        questKey = payload.questKey,
        actionNo = payload.actionNo,
        questCycle = payload.questCycle,
        expectedState = QuestState.CLAIMABLE,
        baselineAction = "claim",
        quests = quests,
        complete = complete,
    )

    private fun questActionCompleted(
        questKey: String,
        actionNo: String,
        questCycle: String?,
        expectedState: QuestState,
        baselineAction: String,
        quests: List<app.spammy.hof.quest.model.QuestSnapshot>,
        complete: Boolean,
    ): TypedAutomationExecution.ActionCompleted {
        val matches = quests.filter { it.questKey == questKey }
        val quest = matches.singleOrNull()
        val progress = quest?.missions?.mapNotNull { it.progress?.current }?.sum()
        val snippet = "QuestResponse|targetMultiplicity=${targetMultiplicity(matches.size)}|" +
            "targetPresent=${quest != null}|state=${quest?.state?.name ?: "ABSENT"}|" +
            "actionNoPresent=${quest?.actionNo != null}|progressPresent=${progress != null}"
        return TypedAutomationExecution.ActionCompleted(
            observedState = QuestObservedState(
                fingerprint = if (quest?.state == expectedState && quest.actionNo == actionNo) {
                    ProductionEvidenceShapes.fingerprint("quest|$baselineAction|$questKey|$questCycle|$actionNo")
                } else {
                    ProductionEvidenceShapes.fingerprint(snippet)
                },
                present = quest != null,
                state = quest?.state,
                actionNo = quest?.actionNo?.let { "present" },
                progressCurrent = progress,
            ),
            responseShapeMaterial = responseShapeMaterial(
                ProductionEvidenceShapes.QUEST_RESPONSE,
                structurallyKnown = complete && matches.size <= 1,
            ),
            sanitizedSnippet = snippet,
        )
    }

    private fun fishingActionCompleted(
        payload: StoredTypedActionPayload.FishingTown,
        response: app.spammy.hof.town.fishing.dto.FishingResponse,
    ): TypedAutomationExecution.ActionCompleted {
        val snippet = "FishingResponse|primaryAction=${response.primaryAction.name}|" +
            "remainingCastsPresent=${response.remainingCasts != null}|lastOutcome=${response.lastOutcome?.name ?: "NONE"}|" +
            "blockedByBattle=${response.blockedByBattle}|resultStatus=${response.result?.status ?: "NONE"}"
        val unchanged = response.primaryAction == payload.observedPrimaryAction &&
            response.remainingCasts == payload.observedRemainingCasts &&
            response.lastOutcome == null && !response.blockedByBattle
        val actionPoststateComplete = fishingActionPoststateApplied(payload.action, response)
        return TypedAutomationExecution.ActionCompleted(
            observedState = FishingObservedState(
                fingerprint = if (unchanged) {
                    ProductionEvidenceShapes.fingerprint(
                        "fishing|${payload.progressDate}|${payload.action}|" +
                            "${payload.observedPrimaryAction}|${payload.observedRemainingCasts}",
                    )
                } else {
                    ProductionEvidenceShapes.fingerprint(snippet)
                },
                primaryAction = response.primaryAction,
                remainingCasts = response.remainingCasts,
                lastOutcome = response.lastOutcome,
                blockedByBattle = response.blockedByBattle,
            ),
            responseShapeMaterial = responseShapeMaterial(
                ProductionEvidenceShapes.FISHING_RESPONSE,
                structurallyKnown = payload.action in setOf(FishingAction.START, FishingAction.CATCH) &&
                    (knownTownResultStatus(response.result?.status) || actionPoststateComplete),
            ),
            sanitizedSnippet = snippet,
        )
    }

    private fun requireFishingDirectApplied(
        payload: StoredTypedActionPayload.FishingTown,
        response: app.spammy.hof.town.fishing.dto.FishingResponse,
    ) {
        if (!fishingActionPoststateApplied(payload.action, response)) {
            throw AmbiguousAutomationSubmissionException(
                "Fishing direct response did not prove the action-specific poststate.",
            )
        }
    }

    private fun fishingActionPoststateApplied(
        action: FishingAction,
        response: app.spammy.hof.town.fishing.dto.FishingResponse,
    ): Boolean = when (action) {
        FishingAction.START -> response.primaryAction == FishingPrimaryAction.CATCH ||
            response.lastOutcome == FishingOutcome.STARTED
        FishingAction.CATCH -> response.lastOutcome in setOf(
            FishingOutcome.CAUGHT,
            FishingOutcome.ESCAPED,
        ) || response.primaryAction == FishingPrimaryAction.START || response.blockedByBattle
        else -> false
    }

    private fun raidActionCompleted(
        payload: StoredTypedActionPayload.RaidTown,
        response: RaidPubResponse,
        outcome: RaidCycleOutcome?,
    ): TypedAutomationExecution.ActionCompleted {
        val targetId = payload.targetRaidId ?: payload.raidId
        val matches = targetId?.let { id -> response.raids.filter { it.id == id } }.orEmpty()
        val raid = matches.singleOrNull()
        val rewardAvailable = RaidAction.REWARD in response.globalActions
        val rewardResult = RaidRewardResultEvidence.from(response.result)
        val snippet = "RaidPubResponse|targetMultiplicity=${targetMultiplicity(matches.size)}|" +
            "targetPresent=${raid != null}|joined=${raid?.joined == true}|" +
            "status=${raid?.status?.name ?: "ABSENT"}|battleTargetPresent=${raid?.battleTarget != null}|" +
            "rewardAvailable=$rewardAvailable|pageComplete=${response.pageComplete}|" +
            "resultStatus=${response.result?.status ?: "NONE"}|rewardResult=${rewardResult?.name ?: "NONE"}"
        val actionPoststateComplete = response.pageComplete && matches.size <= 1 && when (payload.action) {
            RaidAction.REGISTER,
            RaidAction.START,
            -> raid != null && raid.status != RaidStatus.UNKNOWN
            RaidAction.RESET,
            RaidAction.REWARD,
            -> raid == null || raid.status != RaidStatus.UNKNOWN
            RaidAction.REFRESH -> response.registrationStateObserved && response.raids.all { it.status != RaidStatus.UNKNOWN }
            else -> false
        }
        return TypedAutomationExecution.ActionCompleted(
            observedState = RaidObservedState(
                fingerprint = ProductionEvidenceShapes.fingerprint(snippet),
                joined = raid?.joined == true,
                sharedStatus = raid?.status?.name ?: "ABSENT",
                personalCooldown = raid?.battleTarget?.cooldownRemainingSeconds?.let { it > 0L } == true,
                rewardAvailable = rewardAvailable,
                rewardResult = rewardResult,
                registrationStateObserved = response.registrationStateObserved,
            ),
            responseShapeMaterial = responseShapeMaterial(
                ProductionEvidenceShapes.RAID_RESPONSE,
                structurallyKnown = actionPoststateComplete,
            ),
            sanitizedSnippet = snippet,
            actionSuccessMarker = response.hasActionSuccessMarker(payload.action),
            explicitRejected = response.result?.status == "FAILURE" && rewardResult == null,
            rejectionReason = response.result?.status
                ?.takeIf { it == "FAILURE" && rewardResult == null }
                ?.let { "RAID_ACTION_REJECTED" },
            raidOutcome = outcome,
        )
    }

    private fun responseShapeMaterial(base: String, structurallyKnown: Boolean): String =
        if (structurallyKnown) base else "$base|variant=UNCLASSIFIED"

    private fun knownTownResultStatus(status: String?): Boolean =
        status == null || status in setOf("SUCCESS", "FAILURE")

    private fun targetMultiplicity(size: Int): String = when (size) {
        0 -> "NONE"
        1 -> "ONE"
        else -> "MULTIPLE"
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
                    progressDate = action.progressDate,
                ),
            ),
            action.observation,
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
                    selectedAt = timeProvider.now(),
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
        val latest = homeService.load(accountId, HomeMode.HOME, HofRequestOrigin.AUTOMATION)
        if (latest.quests.isEmpty()) {
            return verifyLater("자택 퀘스트 영역이 비어 있어 실행 결과를 완전하게 확인할 수 없습니다.")
        }
        // 다른 행의 존재만으로 전체 목록의 완전성이나 대상 부재를 증명할 수 없다.
        val quest = latest.quests.singleOrNull { it.id == payload.questId }
            ?: return verifyLater("자택 퀘스트 대상을 하나로 확인하지 못했습니다.")
        val originalState = if (payload.action == HomeQuestAutomationActionType.ACCEPT) {
            HomeQuestState.AVAILABLE
        } else {
            HomeQuestState.CLAIMABLE
        }
        return when {
            !quest.stateObserved ->
                verifyLater("자택 퀘스트 상태를 완전하게 확인하지 못했습니다.")
            quest.state != originalState -> AmbiguousActionResolution.Superseded(
                "최신 자택 상태가 바뀌었습니다. 이전 행동의 성공으로 귀속하지 않고 새로 판단합니다.",
            )
            quest.actionId == payload.actionId -> AmbiguousActionResolution.Resubmit
            quest.actionId != null -> AmbiguousActionResolution.Superseded(
                "최신 자택 행동 식별자가 바뀌었습니다. 이전 행동의 성공으로 귀속하지 않고 새로 판단합니다.",
            )
            else -> verifyLater("자택 퀘스트 실행 결과를 아직 확정할 수 없습니다.")
        }
    }

    private fun reconcileQuestAccept(
        accountId: Long,
        executionIdentity: String,
        payload: StoredTypedActionPayload.QuestAccept,
    ): AmbiguousActionResolution {
        val observation = sessionRecovery.execute(accountId) {
            questGateway.loadObservation(accountId, HofRequestOrigin.AUTOMATION)
        }
        if (!observation.complete) {
            return verifyLater("퀘스트 영역이 불완전해 수락 결과를 확인할 수 없습니다.")
        }
        return questWorkCycle.recordObservedResult(
            accountId,
            QuestAttempt.Accept(executionIdentity, payload.questKey, payload.actionNo),
            QuestResultObservation.Page(observation.quests, complete = true),
        ).toAmbiguousResolution()
    }

    private fun reconcileQuestClaim(
        accountId: Long,
        executionIdentity: String,
        payload: StoredTypedActionPayload.QuestClaim,
    ): AmbiguousActionResolution {
        val observation = sessionRecovery.execute(accountId) {
            questGateway.loadObservation(accountId, HofRequestOrigin.AUTOMATION)
        }
        if (!observation.complete) {
            return verifyLater("퀘스트 영역이 불완전해 보상 결과를 확인할 수 없습니다.")
        }
        return questWorkCycle.recordObservedResult(
            accountId,
            QuestAttempt.Claim(executionIdentity, payload.questKey, payload.actionNo),
            QuestResultObservation.Page(observation.quests, complete = true),
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
                        is QuestRecordResult.FreshDecision -> AmbiguousActionResolution.FreshDecision(result.message)
                    }
                }
            }
            is BattleOutcomeReconciliation.Unproven -> {
                val observation = sessionRecovery.execute(accountId) {
                    questGateway.loadObservation(accountId, HofRequestOrigin.AUTOMATION)
                }
                when (val result = questWorkCycle.recordObservedResult(
                    accountId,
                    QuestAttempt.Battle(stored.executionIdentity, payload.toQuestAction()),
                    QuestResultObservation.Page(observation.quests, observation.complete),
                )) {
                    is QuestRecordResult.Recorded -> AmbiguousActionResolution.FreshDecision(
                        "최신 퀘스트 진행을 반영했습니다. 이전 전투 결과는 귀속하지 않고 새로 판단합니다.",
                    )
                    is QuestRecordResult.NotApplied -> verifyLater(result.message)
                    is QuestRecordResult.NeedsRecheck -> verifyLater(result.message)
                    is QuestRecordResult.FreshDecision -> AmbiguousActionResolution.FreshDecision(result.message)
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
        return TypedAutomationExecution.BattleCompleted(
            categoryId = payload.categoryId,
            mapCode = payload.mapCode,
            terminalOutcomes = submission.outcomes.map { it.name },
            raidOutcome = raidCompletion,
        )
    }

    private fun reconcileBattleMap(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.BattleMap,
    ): AmbiguousActionResolution {
        if (payload.source == BattleAutomationActionSource.UNION_AUTOMATION) {
            return reconcileUnionBattleMap(accountId, payload)
        }
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
            is BattleOutcomeReconciliation.Unproven -> {
                if (payload.source == BattleAutomationActionSource.BATTLE_MAP_AUTOMATION) {
                    reconcileGenericBattleMapPoststate(accountId, stored, payload, reconciliation.message)
                } else {
                    verifyLater(reconciliation.message)
                }
            }
        }
    }

    private fun reconcileGenericBattleMapPoststate(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.BattleMap,
        unprovenReason: String,
    ): AmbiguousActionResolution {
        val observation = battleMapService.observeCurrentlyAvailableMaps(
            accountId,
            payload.categoryId,
            HofRequestOrigin.AUTOMATION,
        )
        if (observation.status != CurrentBattleMapObservationStatus.OBSERVED) {
            return verifyLater("$unprovenReason 최신 전투맵 화면도 완전하지 않습니다.")
        }
        val target = observation.maps.firstOrNull { it.mapCode == payload.mapCode }
        val advanced = target == null || !target.enabled ||
            (target.cooldownRemainingSeconds ?: 0L) > 0L
        if (!advanced) {
            return verifyLater("$unprovenReason 대상 맵이 아직 실행 가능한 상태입니다.")
        }
        return AmbiguousActionResolution.Superseded(
            "$unprovenReason 최신 전투맵 상태가 바뀌어 저장 행동을 현재 계정 성공으로 귀속하지 않습니다.",
        )
    }

    /**
     * 유니온은 전투 결과 식별자보다 최신 유니온 페이지의 맵 소멸/쿨타임이 더 직접적인 후상태다.
     * 불완전한 페이지나 동일하게 실행 가능한 맵은 성공으로 추정하지 않는다.
     */
    private fun reconcileUnionBattleMap(
        accountId: Long,
        payload: StoredTypedActionPayload.BattleMap,
    ): AmbiguousActionResolution {
        val observation = battleMapService.observeCurrentlyAvailableMaps(
            accountId,
            payload.categoryId,
            HofRequestOrigin.AUTOMATION,
        )
        val target = observation.maps.firstOrNull { it.mapCode == payload.mapCode }
        if (
            observation.status == CurrentBattleMapObservationStatus.ABSENT ||
            observation.status == CurrentBattleMapObservationStatus.OBSERVED &&
            (target == null || !target.enabled)
        ) {
            return AmbiguousActionResolution.Superseded(
                "유니온 대상 맵이 사라져 다른 사용자의 상태 변경으로 종료합니다.",
            )
        }
        val personalCooldownStarted = observation.status == CurrentBattleMapObservationStatus.OBSERVED &&
            target != null && (target.cooldownRemainingSeconds ?: 0L) > 0L
        if (!personalCooldownStarted) {
            return verifyLater(
                if (observation.status == CurrentBattleMapObservationStatus.INCOMPLETE) {
                    "유니온 최신 페이지가 완전하지 않아 대상 소멸 여부를 판정할 수 없습니다."
                } else {
                    "유니온 대상 맵이 아직 실행 가능한 상태로 관측됩니다."
                },
            )
        }
        return AmbiguousActionResolution.Superseded(
            "유니온 개인 쿨다운이 시작됐지만 terminal 결과가 없어 저장 행동을 성공으로 귀속하지 않습니다.",
        )
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
                    stored.executionIdentity,
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
                workLifecycle.completeUnionCycle(accountId, stored.entryId, stored.executionIdentity)
                null
            }
            BattleAutomationActionSource.FISHING_AUTOMATION -> {
                workLifecycle.completeFishingCycle(accountId, stored.entryId, stored.executionIdentity)
                null
            }
            BattleAutomationActionSource.RAID_AUTOMATION -> {
                val raidId = payload.sourceTargetKey ?: return null
                recordRaidResult(
                    accountId,
                    RaidAttempt(
                        entryId = stored.entryId,
                        kind = RaidIntentKind.BATTLE,
                        raidId = raidId,
                        requestRaidId = null,
                        executionIdentity = stored.executionIdentity,
                        categoryId = payload.categoryId,
                        mapCode = payload.mapCode,
                        recoveryChainId = payload.recoveryChainId,
                        retransmissionCount = payload.raidRetransmissionCount,
                        finishedAt = timeProvider.now(),
                        submittedFromRunnable = payload.raidSubmittedFromRunnable,
                    ),
                    RaidResultObservation.BattleCompleted,
                ).completion
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
        presetId = action.presetId,
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
                    stored.executionIdentity,
                )
                TypedAutomationExecution.BattleCompleted(
                    payload.categoryId,
                    payload.mapCode,
                    submission.outcomes.map { it.name },
                )
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
        val observation = sessionRecovery.execute(accountId) {
            battleMapService.observeCurrentlyAvailableMaps(
                accountId,
                payload.categoryId,
                HofRequestOrigin.AUTOMATION,
            )
        }
        if (observation.status != CurrentBattleMapObservationStatus.OBSERVED) {
            return verifyLater("Adventure map page is not a fresh authoritative observation.")
        }
        val current = observation.maps.singleOrNull { it.mapCode == payload.mapCode }
            ?: return AmbiguousActionResolution.Superseded(
                "Adventure map ${payload.categoryId}/${payload.mapCode} is absent from the complete latest page.",
            )
        val comparisons = listOf(
            payload.observedAttemptRemaining to current.attemptCount,
            payload.observedWinRemaining to current.winCount,
            payload.observedAvailableCount to current.availableCount,
        )
        val hasBaseline = comparisons.any { (before, after) -> before != null && after != null }
        val decreased = comparisons.any { (before, after) -> before != null && after != null && after < before }
        val cooldownStarted = current.cooldownRemainingSeconds?.let { it > 0 } == true
        if (decreased || cooldownStarted || (current.resolved && !current.enabled)) {
            return AmbiguousActionResolution.Superseded(
                "Adventure map authoritative state changed without a matching terminal battle result.",
            )
        }
        val exhausted = listOf(current.attemptCount, current.winCount, current.availableCount)
            .any { it != null && it <= 0 }
        val runnable = current.resolved && current.enabled && !exhausted &&
            current.keyCount != 0 && (current.cooldownRemainingSeconds ?: 0) <= 0
        val comparableBaselineComplete = comparisons
            .filter { (before, _) -> before != null }
            .all { (before, after) -> after != null && after == before }
        val comparableBaselineChanged = comparisons.any { (before, after) ->
            before != null && after != null && after != before
        }
        if (runnable && hasBaseline && comparableBaselineComplete) {
            return AmbiguousActionResolution.Resubmit
        }
        if (runnable && comparableBaselineChanged) {
            return AmbiguousActionResolution.Superseded(
                "Adventure map authoritative capacity changed from the stored baseline.",
            )
        }
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
        presetId = action.presetId,
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
        entryId: Long,
        payload: StoredTypedActionPayload.FishingTown,
        observed: (app.spammy.hof.town.fishing.dto.FishingResponse) -> Unit,
    ): AmbiguousActionResolution {
        val latest = sessionRecovery.execute(accountId) {
            fishingService.load(accountId, HofRequestOrigin.AUTOMATION)
        }
        observed(latest)
        if (!latest.battleObservationComplete) return verifyLater("낚시 전투 목록을 완전하게 확인하지 못했습니다.")
        if (latest.blockedByBattle) {
            workLifecycle.completeFishingCycle(accountId, entryId)
            return AmbiguousActionResolution.Superseded(
                "최신 낚시 상태에서 방해 전투를 확인했습니다. 이전 제출은 성공으로 귀속하지 않고 새 판단에서 전투를 선택합니다.",
            )
        }
        val remainingDecreased = payload.observedRemainingCasts != null &&
            latest.remainingCasts != null &&
            latest.remainingCasts < payload.observedRemainingCasts
        val terminal = (
            latest.lastOutcome in setOf(FishingOutcome.CAUGHT, FishingOutcome.ESCAPED) ||
                remainingDecreased && latest.primaryAction == FishingPrimaryAction.START ||
                payload.action == FishingAction.CATCH && latest.primaryAction == FishingPrimaryAction.START
            )
        if (latest.primaryAction != payload.observedPrimaryAction || remainingDecreased || terminal) {
            if (terminal) {
                workLifecycle.completeFishingCycle(accountId, entryId)
            } else {
                workLifecycle.waitFishingCycle(
                    accountId,
                    entryId,
                    timeProvider.now(),
                    "낚시 상태가 바뀌어 현재 가능한 단계부터 다시 판단합니다.",
                )
            }
            return AmbiguousActionResolution.Superseded(
                "최신 낚시 상태가 바뀌었습니다. 이전 행동의 성공으로 귀속하지 않고 현재 단계에서 이어갑니다.",
            )
        }
        return AmbiguousActionResolution.Resubmit
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

    private fun submitRaidTown(
        accountId: Long,
        payload: StoredTypedActionPayload.RaidTown,
    ): RaidPubResponse = try {
            runMutation(accountId, "Raid") {
                raidPubService.actionForAutomation(
                    accountId,
                    RaidPubActionRequest(payload.action, payload.raidId),
                    payload.targetRaidId,
                )
            }
        } catch (error: RaidActionPreconditionChangedException) {
            throw AutomationActionPreconditionChangedException(
                error.message ?: "최신 레이드 상태에서 저장 행동의 사전조건이 사라졌습니다.",
                error,
            )
        }

    private fun reconcileRaidTown(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.RaidTown,
    ): AmbiguousActionResolution {
        val attempt = payload.toRaidAttemptOrNull(
            stored.entryId,
            stored.executionIdentity.takeIf { payload.action == RaidAction.REWARD },
        )
            ?: return verifyLater("저장된 레이드 대상이 없어 결과를 안전하게 확인할 수 없습니다.")
        val observation = if (payload.action == RaidAction.REGISTER) {
            raidObservationAdapter.refresh(accountId, attempt.raidId)
        } else {
            raidObservationAdapter.read(accountId)
        }
        if (
            payload.action == RaidAction.REFRESH &&
            (!observation.fresh || !observation.registrationStateObserved ||
                observation.raids.any { it.status == RaidObservedStatus.UNKNOWN })
        ) {
            return verifyLater("레이드 신청 상태가 완전하지 않아 갱신 결과를 다시 확인합니다.")
        }
        if (
            payload.action == RaidAction.START &&
            !observation.actionSuccessMarker &&
            observation.raids.singleOrNull { it.id == attempt.raidId }?.status in
            setOf(RaidObservedStatus.IN_BATTLE, RaidObservedStatus.COMPLETED)
        ) {
            return AmbiguousActionResolution.Superseded(
                "레이드가 시작됐지만 현재 요청의 성공 표식이 없어 다른 참가자의 시작으로 처리합니다.",
            )
        }
        return reconcileRaidResult(
            accountId,
            attempt,
            RaidResultObservation.Page(observation),
        )
    }

    private fun executeRaidAbort(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.RaidCycleAbort,
    ): TypedAutomationExecution {
        val projection = recordRaidResult(
            accountId,
            payload.toRaidAttempt(stored.entryId),
            payload.toObservation(),
        )
        return projection.waiting
            ?: projection.completion?.let(TypedAutomationExecution::RaidCycleFinished)
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
    ): RaidRecordProjection = when (val result = raidCycleModule.recordObservedResult(accountId, attempt, observation)) {
        is RaidRecordResult.Recorded -> RaidRecordProjection(
            completion = result.completion?.also {
                workLifecycle.completeRaidCycle(accountId, attempt.entryId, attempt.executionIdentity)
            },
        )
        is RaidRecordResult.EntryWait -> {
            workLifecycle.waitForRaid(accountId, attempt.entryId, result.raidId, result.at, result.warning)
            RaidRecordProjection(
                completion = result.completion,
                waiting = result.toExecution(),
            )
        }
        is RaidRecordResult.EntrySkipped -> {
            workLifecycle.completeRaidCycle(accountId, attempt.entryId, attempt.executionIdentity)
            RaidRecordProjection()
        }
        is RaidRecordResult.NotApplied -> throw AmbiguousAutomationSubmissionException(result.message)
        is RaidRecordResult.FreshDecision -> throw AmbiguousAutomationSubmissionException(result.message)
        is RaidRecordResult.NeedsRecheck -> throw AmbiguousAutomationSubmissionException(result.message)
        is RaidRecordResult.BattleRecoveryStarted -> throw AmbiguousAutomationSubmissionException(result.message)
        is RaidRecordResult.RewardRetryReady -> throw AmbiguousAutomationSubmissionException(result.message)
        is RaidRecordResult.RewardHeld -> throw AmbiguousAutomationSubmissionException(result.message)
    }

    private fun reconcileRaidResult(
        accountId: Long,
        attempt: RaidAttempt,
        observation: RaidResultObservation,
        defaultExecution: TypedAutomationExecution = TypedAutomationExecution.Completed,
    ): AmbiguousActionResolution {
        val sharedState = observation is RaidResultObservation.Page
        return when (val result = raidCycleModule.recordObservedResult(accountId, attempt, observation)) {
            is RaidRecordResult.Recorded -> {
                result.completion?.let { workLifecycle.completeRaidCycle(accountId, attempt.entryId) }
                if (sharedState) {
                    AmbiguousActionResolution.Superseded("현재 레이드 상태에서 이어갑니다. 이전 행동 결과는 귀속하지 않습니다.")
                } else {
                    AmbiguousActionResolution.Applied(
                        result.completion?.let(TypedAutomationExecution::RaidCycleFinished) ?: defaultExecution,
                    )
                }
            }
            is RaidRecordResult.EntryWait -> {
                workLifecycle.waitForRaid(accountId, attempt.entryId, result.raidId, result.at, result.warning)
                if (sharedState) {
                    AmbiguousActionResolution.Superseded(result.message)
                } else {
                    AmbiguousActionResolution.Applied(result.toExecution())
                }
            }
            is RaidRecordResult.EntrySkipped -> {
                workLifecycle.completeRaidCycle(accountId, attempt.entryId)
                if (sharedState) {
                    AmbiguousActionResolution.Superseded(result.message)
                } else {
                    AmbiguousActionResolution.Applied(defaultExecution)
                }
            }
            is RaidRecordResult.NotApplied -> if (attempt.kind == RaidIntentKind.REWARD) {
                AmbiguousActionResolution.Superseded(result.message)
            } else {
                AmbiguousActionResolution.Resubmit
            }
            is RaidRecordResult.FreshDecision -> AmbiguousActionResolution.FreshDecision(result.message)
            is RaidRecordResult.NeedsRecheck -> AmbiguousActionResolution.VerifyLater(result.at, result.message)
            is RaidRecordResult.BattleRecoveryStarted ->
                AmbiguousActionResolution.HandedOff(result.at, result.message)
            is RaidRecordResult.RewardRetryReady -> AmbiguousActionResolution.Superseded(result.message)
            is RaidRecordResult.RewardHeld -> AmbiguousActionResolution.Superseded(result.message)
        }
    }

    private fun RaidRecordResult.EntryWait.toExecution() = TypedAutomationExecution.RaidWaiting(
        retryAt = at,
        raidId = raidId,
        reasonCode = reasonCode,
        message = message,
        releaseCondition = releaseCondition,
        completedCycle = completion,
    )

    private data class RaidRecordProjection(
        val completion: RaidCycleOutcome? = null,
        val waiting: TypedAutomationExecution.RaidWaiting? = null,
    )

    private fun StoredTypedActionPayload.RaidTown.toRaidAttempt(
        entryId: Long,
        executionIdentity: String? = null,
    ): RaidAttempt {
        return toRaidAttemptOrNull(entryId, executionIdentity)
            ?: throw AmbiguousAutomationSubmissionException("Stored raid action has no target raid id.")
    }

    private fun StoredTypedActionPayload.RaidTown.toRaidAttemptOrNull(
        entryId: Long,
        executionIdentity: String? = null,
    ): RaidAttempt? =
        (targetRaidId ?: raidId)?.let { target ->
            RaidAttempt(
                entryId = entryId,
                kind = action.toRaidIntentKind(),
                raidId = target,
                requestRaidId = raidId,
                executionIdentity = executionIdentity,
            )
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
        presetId,
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
        presetId: Long?,
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
        presetId = presetId,
    )

    private fun executeQuestBattle(
        accountId: Long,
        stored: StoredTypedAutomationAction,
        payload: StoredTypedActionPayload.QuestBattle,
        captureSubmission: (AutomationBattleSubmissionResult.Completed) -> Unit,
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
        captureSubmission(submission)
        return TypedAutomationExecution.BattleCompleted(
            payload.categoryId,
            payload.mapCode,
            submission.outcomes.map { it.name },
        )
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
        is QuestRecordResult.Recorded -> AmbiguousActionResolution.Superseded(
            "최신 퀘스트 상태를 반영했습니다. 이전 행동의 성공으로 귀속하지 않고 새로 판단합니다.",
        )
        is QuestRecordResult.NotApplied -> AmbiguousActionResolution.Resubmit
        is QuestRecordResult.NeedsRecheck -> verifyLater(message)
        is QuestRecordResult.FreshDecision -> AmbiguousActionResolution.FreshDecision(message)
    }

    private fun requireRecordedQuestResult(result: QuestRecordResult) {
        if (result is QuestRecordResult.Recorded) return
        val message = when (result) {
            is QuestRecordResult.NotApplied -> result.message
            is QuestRecordResult.NeedsRecheck -> result.message
            is QuestRecordResult.FreshDecision -> result.message
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
                if (api.errorCode == ErrorCode.INVALID_REQUEST) {
                    throw AutomationActionPreconditionChangedException(
                        api.message,
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
