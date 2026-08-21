package app.spammy.hof.automation.service

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.town.home.dto.HomeQuestResponse
import app.spammy.hof.town.home.dto.HomeResponse
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.model.HomeQuestState
import app.spammy.hof.town.home.service.HomeService
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.mockito.Mockito
import tools.jackson.module.kotlin.jacksonObjectMapper

class AutomationActionLifecycleModuleTest {
    private val now = Instant.parse("2026-08-21T00:00:00Z")
    private val home = Mockito.mock(HomeService::class.java)
    private val workOwnership = Mockito.mock(AutomationWorkOwnership::class.java)
    private val module: AutomationActionLifecycleModule = HomeQuestAutomationActionLifecycleModule(
        StoredTypedAutomationActionCodec(jacksonObjectMapper()),
        workOwnership,
        home,
        HofSessionRecoveryExecutor(
            HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java)),
        ),
        TimeProvider { now },
    )

    @Test
    fun `자택 퀘스트 수락을 저장하고 같은 descriptor로 정확히 한 번 실행한다`() {
        val prepared = HomeQuestAutomationAction(
            accountId = 7L,
            questId = "home-1",
            questName = "빗자루 제작",
            actionId = "accept-action",
            action = HomeQuestAutomationActionType.ACCEPT,
        )

        val managed = assertNotNull(module.prepare(7L, 12L, prepared))
        val payload = assertIs<StoredTypedActionPayload.HomeQuest>(managed.storedAction.payload)

        assertEquals("home-1", payload.questId)
        assertEquals("accept-action", payload.actionId)
        assertEquals(HomeQuestAutomationActionType.ACCEPT, payload.action)
        assertEquals(AutomationType.HOME_QUEST, managed.descriptor.source)
        assertEquals("HOME_ACCEPT", managed.descriptor.actionKind)
        assertEquals("자택 퀘스트 수락 · 빗자루 제작", managed.descriptor.context)
        assertEquals("home-1", managed.descriptor.targetKey)
        assertEquals("빗자루 제작", managed.descriptor.targetName)
        assertEquals(managed.descriptor, module.describe(prepared))
        Mockito.verify(workOwnership).ensure(
            7L,
            12L,
            AutomationWorkAssignment(AutomationWorkType.HOME_QUEST, "home-1"),
        )

        assertEquals(TypedAutomationExecution.Completed, managed.execute())
        Mockito.verify(home, Mockito.times(1)).runHomeQuest(7L, "accept-action")
    }

    @Test
    fun `권위 있는 자택 퀘스트 상태로 적용 재제출 재확인을 구분한다`() {
        val accept = assertNotNull(module.prepare(7L, 12L, homeAction(HomeQuestAutomationActionType.ACCEPT)))
        Mockito.`when`(home.load(7L, HomeMode.HOME)).thenReturn(
            homeResponse(HomeQuestState.ACTIVE, null),
            homeResponse(HomeQuestState.AVAILABLE, "action-1"),
            homeResponse(HomeQuestState.AVAILABLE, "changed-action"),
        )

        assertIs<AmbiguousActionResolution.Applied>(accept.reconcile())
        assertIs<AmbiguousActionResolution.Resubmit>(accept.reconcile())
        val verifyLater = assertIs<AmbiguousActionResolution.VerifyLater>(accept.reconcile())
        assertEquals(now.plusSeconds(10), verifyLater.retryAt)

        val claim = assertNotNull(module.prepare(7L, 12L, homeAction(HomeQuestAutomationActionType.CLAIM)))
        Mockito.`when`(home.load(7L, HomeMode.HOME)).thenReturn(
            HomeResponse(HomeMode.HOME, emptyList(), emptyList(), null, null),
        )
        assertIs<AmbiguousActionResolution.Applied>(claim.reconcile())
        assertEquals(TypedAutomationExecution.Completed, claim.execute())
        Mockito.verify(home).runHomeQuest(7L, "action-1")
    }

    @Test
    fun `자택 퀘스트 제출 결과가 불명확하면 즉시 재실행하지 않고 조정 대상으로 남긴다`() {
        val managed = assertNotNull(
            module.prepare(7L, 12L, homeAction(HomeQuestAutomationActionType.ACCEPT)),
        )
        Mockito.doThrow(IllegalStateException("connection closed", IOException("connection closed")))
            .`when`(home).runHomeQuest(7L, "action-1")

        assertFailsWith<AmbiguousAutomationSubmissionException> { managed.execute() }
        Mockito.verify(home, Mockito.times(1)).runHomeQuest(7L, "action-1")
    }

    @Test
    fun `다른 행동군은 기존 경로를 위해 처리하지 않는다`() {
        val action = QuestAction.Accept("quest-1", "accept-1")

        assertNull(module.prepare(7L, 12L, action))
        Mockito.verifyNoInteractions(workOwnership, home)
    }

    @Test
    fun `기존 자택 퀘스트 저장 action은 무결성을 검증한 뒤 복원한다`() {
        val codec = StoredTypedAutomationActionCodec(jacksonObjectMapper())
        val stored = StoredTypedAutomationAction(
            12L,
            "legacy-home-execution",
            StoredTypedActionPayload.HomeQuest(
                "home-1",
                "action-1",
                HomeQuestAutomationActionType.CLAIM,
            ),
        )
        val encoded = codec.encode(stored)
        val account = HofAccountEntity(7L, "login", "encrypted", now)
        val entry = AutomationEntryEntity(12L, account, AutomationType.HOME_QUEST, 0, true, now, now)
        val row = TypedAutomationActionRunEntity(
            88L,
            account,
            entry,
            stored.executionIdentity,
            "HOME_QUEST",
            encoded.json,
            encoded.fingerprint,
            TypedAutomationActionStatus.PREPARED,
            leaseToken = "token",
            createdAt = now,
            updatedAt = now,
        )

        val restored = assertNotNull(module.restore(row, 7L))

        assertEquals(stored, restored.storedAction)
        assertEquals("HOME_CLAIM", restored.descriptor.actionKind)
        val tampered = TypedAutomationActionRunEntity(
            89L,
            account,
            entry,
            stored.executionIdentity,
            "HOME_QUEST",
            encoded.json,
            "0".repeat(64),
            TypedAutomationActionStatus.PREPARED,
            leaseToken = "token",
            createdAt = now,
            updatedAt = now,
        )
        assertFailsWith<IllegalArgumentException> {
            module.restore(tampered, 7L)
        }
    }

    private fun homeAction(type: HomeQuestAutomationActionType) = HomeQuestAutomationAction(
        accountId = 7L,
        questId = "home-1",
        questName = "빗자루 제작",
        actionId = "action-1",
        action = type,
    )

    private fun homeResponse(state: HomeQuestState, actionId: String?) = HomeResponse(
        mode = HomeMode.HOME,
        quests = listOf(
            HomeQuestResponse("home-1", "빗자루 제작", state, null, null, emptyList(), actionId),
        ),
        actions = emptyList(),
        restStatus = null,
        result = null,
    )
}
