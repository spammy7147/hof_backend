package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofAutomationDeferredException
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
class HomeQuestAutomationActionLifecycleModule(
    private val codec: StoredTypedAutomationActionCodec,
    private val workOwnership: AutomationWorkOwnership,
    private val homeService: HomeService,
    private val sessionRecovery: HofSessionRecoveryExecutor,
    private val timeProvider: TimeProvider,
) : AutomationActionLifecycleModule {
    override fun describe(action: PreparedAutomationAction): AutomationActionDescriptor? =
        (action as? HomeQuestAutomationAction)?.let {
            descriptor(it.questId, it.questName, it.action)
        }

    override fun prepare(
        accountId: Long,
        entryId: Long,
        action: PreparedAutomationAction,
    ): ManagedAutomationAction? {
        if (action !is HomeQuestAutomationAction) return null
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
        if (row.actionKind != HOME_QUEST_STORAGE_KIND) return null
        val stored = codec.verifyPersisted(row, expectedAccountId)
        require(stored.payload is StoredTypedActionPayload.HomeQuest) {
            "Stored home quest action payload mismatch."
        }
        return manage(expectedAccountId, stored)
    }

    private fun manage(accountId: Long, stored: StoredTypedAutomationAction): ManagedAutomationAction {
        val payload = stored.payload as StoredTypedActionPayload.HomeQuest
        return object : ManagedAutomationAction {
            override val storedAction = stored
            override val descriptor = payload.descriptor()

            override fun execute(): TypedAutomationExecution {
                runHomeQuestMutation(accountId) {
                    homeService.runHomeQuest(accountId, payload.actionId)
                }
                return TypedAutomationExecution.Completed
            }

            override fun reconcile(): AmbiguousActionResolution {
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
        }
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

    private fun <T> runHomeQuestMutation(accountId: Long, operation: () -> T): T =
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
                        "Home quest side-effect outcome is not provable; it will not be resent.",
                        error,
                    )
                }
            }
            if (causes.any { it is IOException }) {
                throw AmbiguousAutomationSubmissionException(
                    "Home quest side-effect outcome is not provable; it will not be resent.",
                    error,
                )
            }
            throw error
        }

    private companion object {
        const val HOME_QUEST_STORAGE_KIND = "HOME_QUEST"
    }
}
