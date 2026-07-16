package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationActionRunEntity
import app.spammy.hof.automation.entity.AutomationActionStatus
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.push.service.PushOutboxService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito

class AutomationCheckpointServiceTest {
    private val jobs = Mockito.mock(AutomationJobQueryRepository::class.java)
    private val locks = Mockito.mock(AutomationActionLockCoordinator::class.java)
    private val pushOutbox = Mockito.mock(PushOutboxService::class.java)
    private val service = AutomationCheckpointService(jobs, locks, TimeProvider { NOW }, pushOutbox)

    @Test
    fun `success advances only the exact running attempt and clears compatibility fields`() {
        val job = job().apply {
            currentModule = "TIME_BURN"
            currentModuleConfigId = 51L
            currentAction = "Goblin"
        }
        val action = action(job)
        Mockito.`when`(locks.lock(action.id)).thenReturn(LockedAutomationAction(job, action))

        assertTrue(service.succeed(AutomationExecutionToken(action.id, 1)))

        assertEquals(AutomationActionStatus.SUCCEEDED, action.status)
        assertEquals(NOW, action.finishedAt)
        assertEquals(1, job.currentStepIndex)
        assertNull(job.currentModule)
        assertNull(job.currentModuleConfigId)
        assertNull(job.currentAction)
        assertEquals(NOW, job.nextRunAt)
    }

    @Test
    fun `stale completion cannot overwrite a newer attempt`() {
        val job = job()
        val action = action(job).apply { attemptCount = 2 }
        Mockito.`when`(locks.lock(action.id)).thenReturn(LockedAutomationAction(job, action))

        assertFalse(service.succeed(AutomationExecutionToken(action.id, 1)))

        assertEquals(AutomationActionStatus.RUNNING, action.status)
        assertEquals(0, job.currentStepIndex)
    }

    @Test
    fun `captcha failure pauses the linked action without scheduling retry`() {
        val job = job()
        val action = action(job)
        Mockito.`when`(locks.lock(action.id)).thenReturn(LockedAutomationAction(job, action))

        val next = service.fail(
            AutomationExecutionToken(action.id, 1),
            ApiException(ErrorCode.CAPTCHA_REQUIRED, "captcha"),
        )

        assertNull(next)
        assertEquals(AutomationActionStatus.WAITING_CAPTCHA, action.status)
        assertEquals("WAITING_CAPTCHA", job.status)
        assertEquals("인증이 필요합니다.", job.message)
    }

    @Test
    fun `configuration failure stops retries clears current action and alerts login`() {
        val job = job().apply {
            currentModule = "TIME_BURN"
            currentModuleConfigId = 51L
            currentAction = "Goblin"
        }
        val action = action(job)
        Mockito.`when`(locks.lock(action.id)).thenReturn(LockedAutomationAction(job, action))

        val next = service.fail(
            AutomationExecutionToken(action.id, 1),
            AutomationConfigurationException("파티 설정을 확인해 주세요."),
        )

        assertNull(next)
        assertEquals(AutomationActionStatus.WAITING_CONFIG, action.status)
        assertEquals("WAITING_CONFIG", job.status)
        assertNull(job.currentModuleConfigId)
        Mockito.verify(pushOutbox).enqueueLoginRequired(job.account)
    }

    @Test
    fun `login failure waits for explicit login without mutating the stored attempt`() {
        val job = job()
        val action = action(job)
        Mockito.`when`(locks.lock(action.id)).thenReturn(LockedAutomationAction(job, action))

        val next = service.fail(
            AutomationExecutionToken(action.id, 1),
            AutomationLoginRequiredException("로그인이 필요합니다."),
        )

        assertNull(next)
        assertEquals(AutomationActionStatus.RETRY_WAIT, action.status)
        assertEquals("WAITING_LOGIN", job.status)
        assertNull(job.nextRunAt)
    }

    @Test
    fun `network failure schedules bounded retry`() {
        val job = job()
        val action = action(job)
        Mockito.`when`(locks.lock(action.id)).thenReturn(LockedAutomationAction(job, action))

        val next = service.fail(AutomationExecutionToken(action.id, 1), RuntimeException("network"))

        assertEquals(NOW.plusSeconds(5), next)
        assertEquals(AutomationActionStatus.RETRY_WAIT, action.status)
        assertEquals(next, action.nextAttemptAt)
        assertEquals(next, job.nextRunAt)
    }

    @Test
    fun `block and sleep clear compatibility action display`() {
        val blocked = job().apply {
            currentModule = "TIME_BURN"
            currentModuleConfigId = 51L
            currentAction = "Goblin"
        }
        val sleeping = job(id = 12L).apply {
            currentModule = "TIME_BURN"
            currentModuleConfigId = 52L
            currentAction = "Orc"
        }
        Mockito.`when`(jobs.findCurrentByAccountIdAndStatusesForJobId(11L, setOf("RUNNING"))).thenReturn(blocked)
        Mockito.`when`(jobs.findCurrentByAccountIdAndStatusesForJobId(12L, setOf("RUNNING"))).thenReturn(sleeping)

        service.blockForConfig(11L, "설정 필요")
        service.sleep(12L, NOW.plusSeconds(30))

        assertEquals("WAITING_CONFIG", blocked.status)
        assertNull(blocked.currentModuleConfigId)
        assertEquals(NOW.plusSeconds(30), sleeping.nextRunAt)
        assertNull(sleeping.currentModuleConfigId)
    }

    private fun action(job: AutomationJobEntity) = AutomationActionRunEntity(
        id = 31L,
        job = job,
        moduleConfigId = 51L,
        moduleType = "TIME_BURN",
        actionType = "RUN_BATTLE",
        actionKey = "gb0",
        status = AutomationActionStatus.RUNNING,
        requestKey = "job:${job.id}:step:0",
        payloadJson = "{}",
        attemptCount = 1,
        createdAt = NOW.minusSeconds(1),
        startedAt = NOW.minusSeconds(1),
        updatedAt = NOW.minusSeconds(1),
    )

    private fun job(id: Long = 11L): AutomationJobEntity {
        val account = HofAccountEntity(ACCOUNT_ID, "login", "encrypted", NOW)
        val profile = AutomationProfileEntity(
            id = 21L,
            account = account,
            name = "profile",
            mode = "BATTLE_MAP",
            enabled = true,
            createdAt = NOW,
            updatedAt = NOW,
        )
        return AutomationJobEntity(
            id = id,
            account = account,
            profile = profile,
            status = "RUNNING",
            currentStepIndex = 0,
            message = null,
            createdAt = NOW,
            startedAt = NOW,
            updatedAt = NOW,
            finishedAt = null,
        )
    }

    private companion object {
        const val ACCOUNT_ID = 7L
        val NOW: Instant = Instant.parse("2026-07-16T00:00:00Z")
    }
}
