package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.model.HomeQuestState
import app.spammy.hof.town.home.service.HomeService
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
    fun describe(action: PreparedAutomationAction): AutomationActionDescriptor?

    fun prepare(
        accountId: Long,
        entryId: Long,
        action: PreparedAutomationAction,
    ): ManagedAutomationAction?

    fun restore(
        row: TypedAutomationActionRunEntity,
        expectedAccountId: Long,
    ): ManagedAutomationAction?
}

interface ManagedAutomationAction {
    val storedAction: StoredTypedAutomationAction
    val descriptor: AutomationActionDescriptor

    fun execute(): TypedAutomationExecution
    fun reconcile(): AmbiguousActionResolution
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
    val questCycle: String? = null,
    val missionKey: String? = null,
    val missionType: String? = null,
    val observedCurrent: Int? = null,
    val observedRequired: Int? = null,
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
    private val questHandler: QuestAutomationHandler,
    private val battleSubmission: AutomationBattleSubmission,
    private val executionSignals: AutomationExecutionSignals,
    private val sessionRecovery: HofSessionRecoveryExecutor,
    private val timeProvider: TimeProvider,
) : AutomationActionLifecycleModule {
    override fun describe(action: PreparedAutomationAction): AutomationActionDescriptor? = when (action) {
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
        else -> null
    }

    override fun prepare(
        accountId: Long,
        entryId: Long,
        action: PreparedAutomationAction,
    ): ManagedAutomationAction? = when (action) {
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
                AutomationWorkAssignment(
                    type = AutomationWorkType.QUEST,
                    targetKey = action.questKey,
                    questCycle = action.questCycle,
                    missionKey = action.missionKey,
                    missionType = action.missionType.name,
                    observedCurrent = action.missionCurrent,
                    observedRequired = action.missionRequired,
                ),
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
        else -> null
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
    ): ManagedAutomationAction? {
        if (row.actionKind !in setOf(
                HOME_QUEST_STORAGE_KIND,
                QUEST_ACCEPT_STORAGE_KIND,
                QUEST_CLAIM_STORAGE_KIND,
                QUEST_BATTLE_STORAGE_KIND,
            )
        ) return null
        val stored = codec.verifyPersisted(row, expectedAccountId)
        require(
            stored.payload is StoredTypedActionPayload.HomeQuest ||
                stored.payload is StoredTypedActionPayload.QuestAccept ||
                stored.payload is StoredTypedActionPayload.QuestClaim ||
                stored.payload is StoredTypedActionPayload.QuestBattle,
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
                    runMutation(accountId, "Quest") {
                        questGateway.accept(accountId, payload.actionNo, HofRequestOrigin.AUTOMATION)
                    }
                    questHandler.onAcceptSucceeded(
                        accountId,
                        stored.executionIdentity,
                        QuestAction.Accept(payload.questKey, payload.actionNo),
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
                    runMutation(accountId, "Quest") {
                        questGateway.claim(accountId, payload.actionNo, HofRequestOrigin.AUTOMATION)
                    }
                    return TypedAutomationExecution.Completed
                }

                override fun reconcile(): AmbiguousActionResolution = reconcileQuestClaim(accountId, payload)
            }
            is StoredTypedActionPayload.QuestBattle -> object : ManagedAutomationAction {
                override val storedAction = stored
                override val descriptor = payload.questBattleDescriptor()

                override fun execute(): TypedAutomationExecution = executeQuestBattle(accountId, stored, payload)

                override fun reconcile(): AmbiguousActionResolution = reconcileQuestBattle(accountId, payload)
            }
            else -> error("Stored action ${payload.kind()} is not owned by the action lifecycle module.")
        }
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
        val quest = sessionRecovery.execute(accountId) {
            questGateway.load(accountId, HofRequestOrigin.AUTOMATION)
        }.singleOrNull { it.questKey == payload.questKey }
            ?: return verifyLater("Quest ${payload.questKey} is absent from the authoritative page.")
        return when {
            quest.state in setOf(
                app.spammy.hof.quest.model.QuestState.ACTIVE,
                app.spammy.hof.quest.model.QuestState.CLAIMABLE,
                app.spammy.hof.quest.model.QuestState.COMPLETED,
            ) -> {
                questHandler.onAcceptSucceeded(
                    accountId,
                    executionIdentity,
                    QuestAction.Accept(payload.questKey, payload.actionNo),
                )
                AmbiguousActionResolution.Applied()
            }
            quest.state == app.spammy.hof.quest.model.QuestState.AVAILABLE && quest.actionNo == payload.actionNo ->
                AmbiguousActionResolution.Resubmit
            else -> verifyLater("Quest accept outcome is not yet authoritative.")
        }
    }

    private fun reconcileQuestClaim(
        accountId: Long,
        payload: StoredTypedActionPayload.QuestClaim,
    ): AmbiguousActionResolution {
        val quest = sessionRecovery.execute(accountId) {
            questGateway.load(accountId, HofRequestOrigin.AUTOMATION)
        }.singleOrNull { it.questKey == payload.questKey }
            ?: return AmbiguousActionResolution.Applied()
        return when {
            quest.state == app.spammy.hof.quest.model.QuestState.COMPLETED ->
                AmbiguousActionResolution.Applied()
            quest.state == app.spammy.hof.quest.model.QuestState.CLAIMABLE && quest.actionNo == payload.actionNo ->
                AmbiguousActionResolution.Resubmit
            else -> verifyLater("Quest claim outcome is not yet authoritative.")
        }
    }

    private fun reconcileQuestBattle(
        accountId: Long,
        payload: StoredTypedActionPayload.QuestBattle,
    ): AmbiguousActionResolution {
        val quest = sessionRecovery.execute(accountId) {
            questGateway.load(accountId, HofRequestOrigin.AUTOMATION)
        }.singleOrNull { it.questKey == payload.questKey }
            ?: return verifyLater("Quest ${payload.questKey} is absent from the authoritative page.")
        if (quest.state in setOf(
                app.spammy.hof.quest.model.QuestState.CLAIMABLE,
                app.spammy.hof.quest.model.QuestState.COMPLETED,
            )
        ) {
            return appliedQuestBattle(payload)
        }
        val mission = quest.missions.singleOrNull { it.key == payload.missionKey }
            ?: return verifyLater("Quest mission ${payload.missionKey} is absent from the authoritative page.")
        val current = mission.progress?.current
            ?: return verifyLater("Quest mission progress is not authoritative yet.")
        val baseline = payload.observedCurrent
            ?: return verifyLater("Stored quest mission has no pre-submit progress.")
        return when {
            current > baseline -> appliedQuestBattle(payload)
            current == baseline && quest.state == app.spammy.hof.quest.model.QuestState.ACTIVE ->
                verifyLater("Quest battle may have completed without mission progress; it will not be resent.")
            else -> verifyLater("Quest battle outcome is not yet authoritative.")
        }
    }

    private fun appliedQuestBattle(payload: StoredTypedActionPayload.QuestBattle) =
        AmbiguousActionResolution.Applied(
            TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode),
        )

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
        questHandler.onBattleCompleted(
            accountId,
            submission.resultIdentity,
            QuestAction.Battle(
                payload.questKey,
                payload.questCycle,
                payload.missionKey,
                payload.missionType,
                payload.categoryId,
                payload.mapCode,
                payload.mapCode,
                QuestPresetSelection(payload.presetMode, payload.presetId),
                payload.battleCount,
            ),
            submission.outcomes,
        )
        val rounds = submission.response.rounds.takeIf(List<*>::isNotEmpty)
        executionSignals.afterBattle(
            accountId = accountId,
            source = BattleAutomationActionSource.QUEST_AUTOMATION,
            outcomes = submission.outcomes,
            lootNames = rounds?.flatMap { it.loots.map { loot -> loot.name } }
                ?: submission.response.loots.map { it.name },
            questTexts = rounds?.mapNotNull { it.quest?.takeIf(String::isNotBlank) }
                ?: listOfNotNull(submission.response.quest?.takeIf(String::isNotBlank)),
        )
        return TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode)
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
    }
}
